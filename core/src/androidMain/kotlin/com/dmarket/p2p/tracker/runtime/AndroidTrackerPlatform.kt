package com.dmarket.p2p.tracker.runtime

import android.content.Context
import android.content.SharedPreferences
import com.dmarket.p2p.tracker.adapter.AndroidCredentialVault
import com.dmarket.p2p.tracker.adapter.KeystoreAesGcmCipher
import com.dmarket.p2p.tracker.adapter.SealedKeyValueStore
import com.dmarket.p2p.tracker.adapter.SecretCipher
import com.dmarket.p2p.tracker.adapter.SharedPreferencesKeyValueStore
import com.dmarket.p2p.tracker.adapter.host.PersistedDeviceIdStore
import com.dmarket.p2p.tracker.adapter.host.SystemClock
import com.dmarket.p2p.tracker.adapter.notary.MarketplaceNotaryTokenProvider
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
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.port.host.Clock
import com.dmarket.p2p.tracker.port.host.CredentialVault
import com.dmarket.p2p.tracker.port.host.DeviceIdStore
import com.dmarket.p2p.tracker.port.host.DeviceKeyValueStore
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceCredentialProvider
import com.dmarket.p2p.tracker.port.notary.NotaryTokenProvider
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What the Android tracker runs on: the per-account state on disk, the vault, the install-scoped device id, the clock,
 * the HTTP engine and DMarket auth through the host's own token layer. Nothing secret passes through the host.
 *
 * **Files.** Two private `SharedPreferences` files, fixed names so the app's `dataExtractionRules` can list them
 * (README): [INSTALL_FILE] (the sealed device id and Steam credential, and which account the state belongs to) and
 * [ACCOUNT_FILE] (that account's state). Only the install file holds secrets, and only sealed with the Keystore key;
 * the account file holds no secret.
 *
 * **Install scope.** The device id and the Steam credential are sealed with a Keystore key, which is never backed up
 * and never moves to another device. On a restored or transferred install neither opens: the device id is generated
 * anew, the vault reads empty, and the state that came along with them is erased before anything reads it. This holds
 * even if the app's `dataExtractionRules` let the files through; those rules (README) keep them off the backup in the
 * first place.
 *
 * **Account scope.** State belongs to one Steam account at a time. [storageFor] another account erases the previous
 * one's state first, and [clearAccountState] (logout) erases it with the Steam credential, so a new account always
 * starts from empty stores and nothing of the previous one is read or reported. The device id stays.
 *
 * @param tokens the host's DMarket token layer (on the DMarket app, `TokenManager.getTokenData(forceRefresh)`).
 * @param httpEngine the HTTP engine for DMarket and Steam; `null` takes the platform's (OkHttp).
 */
class AndroidTrackerPlatform internal constructor(
    private val prefs: (String) -> SharedPreferences,
    cipher: SecretCipher,
    tokens: MarketplaceTokenSupplier,
    val clock: Clock = SystemClock(),
    val httpEngine: HttpClientEngine? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(
        context: Context,
        tokens: MarketplaceTokenSupplier,
        clock: Clock = SystemClock(),
        httpEngine: HttpClientEngine? = null,
    ) : this(
        prefs = { name -> context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE) },
        cipher = KeystoreAesGcmCipher(),
        tokens = tokens,
        clock = clock,
        httpEngine = httpEngine,
    )

    private val mutex = Mutex()
    private val install = SharedPreferencesKeyValueStore(prefs(INSTALL_FILE), io)
    private val sealed = SealedKeyValueStore(install, cipher)
    private val accountFile = SharedPreferencesKeyValueStore(prefs(ACCOUNT_FILE), io)
    private val ids = PersistedDeviceIdStore(sealed)

    private val sealedVault = AndroidCredentialVault(sealed)

    /** The Steam credential, sealed in the install file. Each call settles the install first, like [deviceIds]. */
    val vault: CredentialVault = object : CredentialVault {
        override suspend fun readSteamCredential(): SteamCredential? = inInstall { sealedVault.readSteamCredential() }

        override suspend fun writeSteamCredential(credential: SteamCredential) = inInstall { sealedVault.writeSteamCredential(credential) }

        override suspend fun clearSteamCredential() = inInstall { sealedVault.clearSteamCredential() }
    }

    /** The install's device id; a restored or transferred install gets a new one. */
    val deviceIds: DeviceIdStore = object : DeviceIdStore {
        override suspend fun current(): DeviceId = mutex.withLock { currentInstallLocked() }
    }

    /** DMarket credentials straight from the host's token layer; the library refreshes nothing itself. */
    val marketplaceCredentials: MarketplaceCredentialProvider = HostTokenMarketplaceCredentialProvider(supplier = tokens)

    /** Attaches the host's token raw in `Authorization` and asks it for one forced refresh on a 401. */
    val marketplaceAuthenticator: MarketplaceAuthenticator = CredentialMarketplaceAuthenticator(marketplaceCredentials)

    /** The notary's DMarket token: the same live token as every marketplace call. */
    val notaryTokens: NotaryTokenProvider = MarketplaceNotaryTokenProvider(marketplaceCredentials)

    /**
     * The state storage of [account] (a steamid64). Another account's state, if any, is erased first, so the stores
     * built over this start empty for a new account and keep what they had for the same one. Stop the previous
     * account's loop first: its stores hold state in memory and would write it into the new account's file.
     */
    suspend fun storageFor(account: SteamId): DeviceKeyValueStore = mutex.withLock {
        require(STEAM_ID_64.matches(account.value)) { "not a steamid64" }
        currentInstallLocked()
        val active = install.get(ACTIVE_ACCOUNT)
        if (active != account.value) {
            accountFile.clear()
            install.set(ACTIVE_ACCOUNT, account.value)
        }
        accountFile
    }

    /** The loop's persisted stores for [account], over [storageFor]: the same set the headless tracker builds. */
    suspend fun stores(account: SteamId, tunables: TrackerConfig = TrackerConfig.defaults()): AndroidTrackerStores {
        val storage = storageFor(account)
        return AndroidTrackerStores(
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
        accountFile.clear()
        install.remove(ACTIVE_ACCOUNT)
        sealedVault.clearSteamCredential()
    }

    /**
     * The device id, made on first use. Its absence means a fresh install, or one restored or transferred without the
     * Keystore key (the sealed id did not open and is gone): state that came along belongs to another install and is
     * erased before anything reads it.
     */
    private suspend fun currentInstallLocked(): DeviceId {
        if (sealed.get(DeviceVaultKeys.DEVICE_ID) == null) {
            accountFile.clear()
            install.remove(ACTIVE_ACCOUNT)
            sealedVault.clearSteamCredential()
        }
        return ids.current()
    }

    private suspend fun <T> inInstall(block: suspend () -> T): T = mutex.withLock {
        currentInstallLocked()
        block()
    }

    companion object {
        /** The install file: the sealed device id and Steam credential, and whose state [ACCOUNT_FILE] holds. */
        const val INSTALL_FILE: String = "dmarket_p2p_tracker_install"

        /** The current account's state; emptied when the account changes and on logout. */
        const val ACCOUNT_FILE: String = "dmarket_p2p_tracker_account"
        private const val ACTIVE_ACCOUNT = "active_account"
        private val STEAM_ID_64 = Regex("^[0-9]{1,20}$")
    }
}

/** The loop's persisted stores for one account, as [AndroidTrackerPlatform.stores] builds them. */
class AndroidTrackerStores(
    val loopState: LoopStateStore,
    val progress: TrackerProgressStore,
    val claims: DealWriteClaimStore,
    val unprovenClaims: UnprovenClaimStore,
    val offerGuard: OfferGuardStore,
    val throttle: SteamWriteThrottleStore,
    val notaryThrottle: NotaryProofThrottleStore,
)
