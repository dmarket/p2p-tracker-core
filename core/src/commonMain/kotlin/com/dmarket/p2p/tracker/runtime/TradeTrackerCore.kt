package com.dmarket.p2p.tracker.runtime

import com.dmarket.p2p.tracker.adapter.host.NoOpEventObserver
import com.dmarket.p2p.tracker.adapter.host.NoOpPushChannel
import com.dmarket.p2p.tracker.adapter.host.SystemClock
import com.dmarket.p2p.tracker.adapter.notary.NoOpNotaryProver
import com.dmarket.p2p.tracker.adapter.platformCredentialVault
import com.dmarket.p2p.tracker.adapter.platformKeyValueStore
import com.dmarket.p2p.tracker.adapter.steam.NoOpSteamInventoryReader
import com.dmarket.p2p.tracker.adapter.steam.NoOpSteamNotificationReader
import com.dmarket.p2p.tracker.adapter.steam.NoOpSteamOfferCanceller
import com.dmarket.p2p.tracker.adapter.steam.NoOpSteamOfferCreator
import com.dmarket.p2p.tracker.adapter.steam.NoOpSteamSessionRefresher
import com.dmarket.p2p.tracker.client.steam.RefreshingSteamReadClient
import com.dmarket.p2p.tracker.credential.steam.SteamCredentialProvider
import com.dmarket.p2p.tracker.game.GameRegistry
import com.dmarket.p2p.tracker.loop.DealWriteClaimStore
import com.dmarket.p2p.tracker.loop.InMemoryLoopStateStore
import com.dmarket.p2p.tracker.loop.InMemoryTrackerProgressStore
import com.dmarket.p2p.tracker.loop.LoopConfig
import com.dmarket.p2p.tracker.loop.LoopStateStore
import com.dmarket.p2p.tracker.loop.NotaryProofThrottleStore
import com.dmarket.p2p.tracker.loop.PersistedDealWriteClaimStore
import com.dmarket.p2p.tracker.loop.PersistedNotaryProofThrottleStore
import com.dmarket.p2p.tracker.loop.PersistedSteamWriteThrottleStore
import com.dmarket.p2p.tracker.loop.SteamWriteThrottleStore
import com.dmarket.p2p.tracker.loop.TrackerProgressStore
import com.dmarket.p2p.tracker.loop.TradeTrackerLoop
import com.dmarket.p2p.tracker.port.host.Clock
import com.dmarket.p2p.tracker.port.host.CredentialVault
import com.dmarket.p2p.tracker.port.host.DeviceIdStore
import com.dmarket.p2p.tracker.port.host.EventObserver
import com.dmarket.p2p.tracker.port.host.PushChannel
import com.dmarket.p2p.tracker.port.host.Scheduler
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceClient
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceCredentialProvider
import com.dmarket.p2p.tracker.port.notary.NotaryProver
import com.dmarket.p2p.tracker.port.steam.SteamInventoryReader
import com.dmarket.p2p.tracker.port.steam.SteamNotificationReader
import com.dmarket.p2p.tracker.port.steam.SteamOfferCanceller
import com.dmarket.p2p.tracker.port.steam.SteamOfferCreator
import com.dmarket.p2p.tracker.port.steam.SteamReadClient
import com.dmarket.p2p.tracker.port.steam.SteamSessionRefresher
import com.dmarket.p2p.tracker.port.steam.SteamSessionScraper

/**
 * Composition root for the trade-tracker core. [createLoop] wires all ports into a [TradeTrackerLoop]
 * running the golden C1 heartbeat + directives cycle.
 */
class TradeTrackerCore(private val games: GameRegistry = GameRegistry.v1()) {

    /** How many games are enabled at runtime (CS2 only at v1). */
    fun enabledGameCount(): Int = games.enabledGames.size

    /**
     * Creates a [TradeTrackerLoop] with all ports wired in. Builds a [SteamCredentialProvider] from
     * [vault] + [scraper] and wraps [steamReader] in a [RefreshingSteamReadClient].
     *
     * [deviceId] supplies the install-scoped persistent `device_id` (the directive-lease key) — the
     * host provides a persistent implementation. [notary] defaults to [NoOpNotaryProver] (MVP stub).
     * [offerCreator]/[offerCanceller]/[inventoryReader] default to no-ops; the web path injects real
     * ones. [vault] defaults to the lib-owned [platformCredentialVault] so the host never sees the
     * plaintext credential.
     *
     * [directivesEnabled] decides whether the loop executes the directives the backend leases to this
     * device. **Library scope, not a host knob** — which device may execute a directive is the backend's
     * decision (it holds the lease), so this is the seam that will carry that backend-driven value once
     * cross-device arbitration lands. The web facade
     * ([com.dmarket.p2p.tracker.runtime.createBrowserLoop]) enables it; the `false` default serves this
     * entry point's other callers — a host wiring its own ports, and tests driving the watch axis alone.
     * A loop that ignores `directives[]` answers nothing on `/trade-actions`, so the backend re-leases
     * each one every heartbeat while the deal parks; the loop emits a `DirectiveDropped` per ignored
     * directive so that state is at least visible.
     *
     * [claims] — the deal-keyed guard that makes the two non-idempotent Steam writes duplicate-proof —
     * defaults to the shared `PersistedDealWriteClaimStore` over [platformKeyValueStore], so **every**
     * target (web / Android / iOS) gets a process-death-durable guard with no host wiring. Build the loop
     * once per process: the store's lock is what serialises concurrent claims.
     *
     * [throttle] — the `create_offer` back-pressure ledger — defaults the same way and for the same reason:
     * the heartbeat TTL is shorter than the MV3 idle timeout, so a cooldown that lived only in memory would
     * be forgotten on nearly every wake and the client would keep re-hitting a partner Steam is refusing.
     *
     * [notaryThrottle] — the proof-generation ledger — likewise, and it bounds the larger cost of the two:
     * every attempt is a full MPC session, measured at ~30 MB uploaded to the notary.
     */
    fun createLoop(
        config: LoopConfig,
        marketplace: MarketplaceClient,
        steamReader: SteamReadClient,
        scraper: SteamSessionScraper,
        scheduler: Scheduler,
        deviceId: DeviceIdStore,
        vault: CredentialVault = platformCredentialVault(),
        clock: Clock = SystemClock(),
        notary: NotaryProver = NoOpNotaryProver,
        offerCreator: SteamOfferCreator = NoOpSteamOfferCreator,
        offerCanceller: SteamOfferCanceller = NoOpSteamOfferCanceller,
        inventoryReader: SteamInventoryReader = NoOpSteamInventoryReader,
        sessionRefresher: SteamSessionRefresher = NoOpSteamSessionRefresher,
        marketplaceCredentials: MarketplaceCredentialProvider? = null,
        progress: TrackerProgressStore = InMemoryTrackerProgressStore(),
        claims: DealWriteClaimStore = PersistedDealWriteClaimStore(platformKeyValueStore()),
        throttle: SteamWriteThrottleStore = PersistedSteamWriteThrottleStore(
            limits = config.tunables.steamWrites,
            storage = platformKeyValueStore(),
        ),
        notaryThrottle: NotaryProofThrottleStore = PersistedNotaryProofThrottleStore(
            limits = config.tunables.notary.breaker,
            storage = platformKeyValueStore(),
        ),
        loopState: LoopStateStore = InMemoryLoopStateStore(),
        pushChannel: PushChannel = NoOpPushChannel,
        directivesEnabled: Boolean = false,
        eventObserver: EventObserver = NoOpEventObserver,
        notifications: SteamNotificationReader = NoOpSteamNotificationReader,
    ): TradeTrackerLoop {
        val provider = SteamCredentialProvider(
            vault = vault,
            scraper = scraper,
            clock = clock,
            sessionRefresher = sessionRefresher,
            skew = config.tunables.credentials.steamSkew,
        )
        val refreshingReader = RefreshingSteamReadClient(delegate = steamReader, provider = provider)
        return TradeTrackerLoop(
            config = config,
            marketplace = marketplace,
            steamReader = refreshingReader,
            credentials = provider,
            scheduler = scheduler,
            clock = clock,
            deviceId = deviceId,
            inventoryReader = inventoryReader,
            notary = notary,
            offerCreator = offerCreator,
            offerCanceller = offerCanceller,
            marketplaceCredentials = marketplaceCredentials,
            progress = progress,
            claims = claims,
            throttle = throttle,
            notaryThrottle = notaryThrottle,
            loopState = loopState,
            pushChannel = pushChannel,
            directivesEnabled = directivesEnabled,
            eventObserver = eventObserver,
            notifications = notifications,
        )
    }

    companion object {
        // Keep in sync with VERSION_NAME in gradle.properties (the version that is actually published);
        // this is what trackerCoreVersion() reports to consumers and what the loop sends as clientVersion.
        const val VERSION: String = "0.1.0-SNAPSHOT"
    }
}
