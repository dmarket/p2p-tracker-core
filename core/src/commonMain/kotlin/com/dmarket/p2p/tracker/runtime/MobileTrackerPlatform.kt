package com.dmarket.p2p.tracker.runtime

import com.dmarket.p2p.tracker.adapter.host.PersistedDeviceIdStore
import com.dmarket.p2p.tracker.client.marketplace.CredentialMarketplaceAuthenticator
import com.dmarket.p2p.tracker.client.marketplace.MarketplaceAuthenticator
import com.dmarket.p2p.tracker.config.TrackerConfig
import com.dmarket.p2p.tracker.credential.marketplace.HostTokenMarketplaceCredentialProvider
import com.dmarket.p2p.tracker.credential.marketplace.MarketplaceTokenSupplier
import com.dmarket.p2p.tracker.credential.steam.DeviceVaultKeys
import com.dmarket.p2p.tracker.loop.DealWriteClaimStore
import com.dmarket.p2p.tracker.loop.LoopStateStore
import com.dmarket.p2p.tracker.loop.NotaryProofThrottleStore
import com.dmarket.p2p.tracker.loop.OfferGuardStore
import com.dmarket.p2p.tracker.loop.PersistedDealWriteClaimStore
import com.dmarket.p2p.tracker.loop.PersistedLoopStateStore
import com.dmarket.p2p.tracker.loop.PersistedNotaryProofThrottleStore
import com.dmarket.p2p.tracker.loop.PersistedOfferGuardStore
import com.dmarket.p2p.tracker.loop.PersistedSteamWriteThrottleStore
import com.dmarket.p2p.tracker.loop.PersistedTrackerProgressStore
import com.dmarket.p2p.tracker.loop.PersistedUnprovenClaimStore
import com.dmarket.p2p.tracker.loop.SteamWriteThrottleStore
import com.dmarket.p2p.tracker.loop.TrackerProgressStore
import com.dmarket.p2p.tracker.loop.UnprovenClaimStore
import com.dmarket.p2p.tracker.model.DeviceId
import com.dmarket.p2p.tracker.model.RuntimeSurface
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.port.host.Clock
import com.dmarket.p2p.tracker.port.host.CredentialVault
import com.dmarket.p2p.tracker.port.host.DeviceIdStore
import com.dmarket.p2p.tracker.port.host.DeviceKeyValueStore
import com.dmarket.p2p.tracker.port.host.InstallMarker
import com.dmarket.p2p.tracker.port.host.NetworkConditions
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceCredentialProvider
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What the mobile tracker ([MobileTracker]) runs on. A platform (Android, iOS) gives only the adapters, the abstract
 * members; the install and account rules over them are the same on every platform and live here. Nothing secret
 * passes through the host.
 *
 * **Install scope.** The device id and the Steam credential belong to one install on one device ([installMarker]). On
 * another install (a fresh one, a reinstall, a restore or a transfer) the device id is made anew, the vault reads
 * empty, and the state that came along with them is erased before anything reads it.
 *
 * **Account scope.** State belongs to one Steam account at a time. [storageFor] another account erases the previous
 * one's state first, and [clearAccountState] (logout) erases it with the Steam credential, so a new account always
 * starts from empty stores and nothing of the previous one is read or reported. The device id stays.
 *
 * Subclasses must not call into these members from their own initialisers: the rules read the adapters lazily.
 *
 * @param tokens the host's DMarket token layer; the core keeps no DMarket token and refreshes nothing itself.
 */
abstract class MobileTrackerPlatform(tokens: MarketplaceTokenSupplier) {
    /** The time the tracker runs on. */
    abstract val clock: Clock

    /** The surface the heartbeat names. */
    abstract val surface: RuntimeSurface

    /** What the device's network costs, read before every cycle by the proof gate. */
    abstract val network: NetworkConditions

    /** A new engine for the core's own DMarket client (unused when the host gives its client). */
    abstract fun dmarketEngine(): HttpClientEngine

    /** A new engine for the Steam client; it shares no cookie store with anything else. */
    abstract fun steamEngine(): HttpClientEngine

    /** Install-scoped, not secret: whose state [accountStore] holds. */
    protected abstract val installStore: DeviceKeyValueStore

    /** Install-scoped and secret (the device id): sealed or held by the OS's secure storage, never in the clear. */
    protected abstract val sealedStore: DeviceKeyValueStore

    /** The Steam credential in the OS's secure storage. */
    protected abstract val sealedVault: CredentialVault

    /** The current account's state, no secrets; emptied by [clearAccountStore]. */
    protected abstract val accountStore: DeviceKeyValueStore

    /** Whether the install-scoped state is this install's; see [InstallMarker]. */
    protected abstract val installMarker: InstallMarker

    /** Removes every key of [accountStore]. */
    protected abstract suspend fun clearAccountStore()

    private val mutex = Mutex()
    private val ids by lazy { PersistedDeviceIdStore(sealedStore) }

    /** The Steam credential. Each call settles the install first, like [deviceIds]. */
    val vault: CredentialVault = object : CredentialVault {
        override suspend fun readSteamCredential(): SteamCredential? = inInstall { sealedVault.readSteamCredential() }

        override suspend fun writeSteamCredential(credential: SteamCredential) = inInstall { sealedVault.writeSteamCredential(credential) }

        override suspend fun clearSteamCredential() = inInstall { sealedVault.clearSteamCredential() }
    }

    /** The install's device id; another install gets a new one. */
    val deviceIds: DeviceIdStore = object : DeviceIdStore {
        override suspend fun current(): DeviceId = mutex.withLock { currentInstallLocked() }
    }

    /** DMarket credentials straight from the host's token layer. */
    val marketplaceCredentials: MarketplaceCredentialProvider = HostTokenMarketplaceCredentialProvider(
        supplier = tokens,
        // A throw from the host is a transient failure, never a logout: "no session" is `null` (same on iOS).
        treatFailureAsLoggedOut = false,
    )

    /** Attaches the host's token raw in `Authorization` and asks it for one forced refresh on a 401. */
    val marketplaceAuthenticator: MarketplaceAuthenticator = CredentialMarketplaceAuthenticator(marketplaceCredentials)

    /**
     * The state storage of [account] (a steamid64). Another account's state, if any, is erased first, so the stores
     * built over this start empty for a new account and keep what they had for the same one. Stop the previous
     * account's loop first: its stores hold state in memory and would write it into the new account's storage.
     */
    suspend fun storageFor(account: SteamId): DeviceKeyValueStore = mutex.withLock {
        require(STEAM_ID_64.matches(account.value)) { "not a steamid64" }
        currentInstallLocked()
        if (installStore.get(ACTIVE_ACCOUNT) != account.value) {
            clearAccountStore()
            installStore.set(ACTIVE_ACCOUNT, account.value)
        }
        accountStore
    }

    /** The loop's persisted stores for [account], over [storageFor]: the same set the headless tracker builds. */
    suspend fun stores(account: SteamId, tunables: TrackerConfig = TrackerConfig.defaults()): MobileTrackerStores {
        val storage = storageFor(account)
        return MobileTrackerStores(
            loopState = PersistedLoopStateStore(storage),
            progress = PersistedTrackerProgressStore(storage),
            claims = PersistedDealWriteClaimStore(storage),
            unprovenClaims = PersistedUnprovenClaimStore(storage),
            offerGuard = PersistedOfferGuardStore(storage),
            throttle = PersistedSteamWriteThrottleStore(limits = tunables.steamWrites, storage = storage),
            notaryThrottle = PersistedNotaryProofThrottleStore(limits = tunables.notary.breaker, storage = storage),
        )
    }

    /**
     * Logout: erases every key of the current account's state (stores, and whatever else a later part keeps there,
     * proofs included) and the Steam credential. The device id stays: it belongs to the install.
     */
    suspend fun clearAccountState() = mutex.withLock {
        clearAccountStore()
        installStore.remove(ACTIVE_ACCOUNT)
        sealedVault.clearSteamCredential()
    }

    /** The device id, made on first use; what another install left is erased before anything reads it. */
    private suspend fun currentInstallLocked(): DeviceId {
        if (!installMarker.isSameInstall()) {
            clearAccountStore()
            installStore.remove(ACTIVE_ACCOUNT)
            sealedVault.clearSteamCredential()
            sealedStore.remove(DeviceVaultKeys.DEVICE_ID)
            installMarker.markInstall()
        }
        return ids.current()
    }

    private suspend fun <T> inInstall(block: suspend () -> T): T = mutex.withLock {
        currentInstallLocked()
        block()
    }

    private companion object {
        const val ACTIVE_ACCOUNT = "active_account"
        val STEAM_ID_64 = Regex("^[0-9]{1,20}$")
    }
}

/** The loop's persisted stores for one account, as [MobileTrackerPlatform.stores] builds them. */
class MobileTrackerStores(
    val loopState: LoopStateStore,
    val progress: TrackerProgressStore,
    val claims: DealWriteClaimStore,
    val unprovenClaims: UnprovenClaimStore,
    val offerGuard: OfferGuardStore,
    val throttle: SteamWriteThrottleStore,
    val notaryThrottle: NotaryProofThrottleStore,
)
