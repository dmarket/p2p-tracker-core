package com.dmarket.p2p.tracker.runtime

import android.content.Context
import com.dmarket.p2p.tracker.adapter.host.CoroutineScheduler
import com.dmarket.p2p.tracker.client.createMarketplaceHttpClient
import com.dmarket.p2p.tracker.client.createSteamHttpClient
import com.dmarket.p2p.tracker.client.marketplace.KtorMarketplaceClient
import com.dmarket.p2p.tracker.client.marketplace.androidMarketplaceClient
import com.dmarket.p2p.tracker.client.steam.KtorSteamReadClient
import com.dmarket.p2p.tracker.client.steam.KtorSteamSessionScraper
import com.dmarket.p2p.tracker.config.TrackerConfig
import com.dmarket.p2p.tracker.credential.marketplace.MarketplaceTokenSupplier
import com.dmarket.p2p.tracker.game.GameRegistry
import com.dmarket.p2p.tracker.loop.LoopConfig
import com.dmarket.p2p.tracker.loop.TradeTrackerLoop
import com.dmarket.p2p.tracker.model.RuntimeSurface
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.TrackerMode
import com.dmarket.p2p.tracker.port.host.EventObserver
import com.dmarket.p2p.tracker.port.host.NetworkObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/**
 * The Android tracker's process-wide entry. `startTracker` takes no `Context`, so the app hands it over once, in
 * `Application.onCreate`, with [install]; [startTracker] then builds the one [P2pTracker] of the process over an
 * [AndroidTrackerPlatform] and starts it, and [tracker] is what the app and [P2pTrackerWorker] call.
 *
 * One tracker per process is what keeps the account rule: every start and stop goes through its one lock, so the
 * previous account's loop has ended before the next account's stores open.
 *
 * Not production-ready on its own: the Steam session (vault, cookies, offer writes) is DMA-662. Until then the loop
 * reads the session with the shared scraper and leaves `create_offer` / `cancel_offer` leases to lapse.
 */
object AndroidTracker {
    private class Installation(
        val platform: AndroidTrackerPlatform,
        val linkedAccount: suspend () -> SteamId?,
        val hostOkHttp: OkHttpClient?,
        val clientVersion: String,
    )

    @Volatile private var installation: Installation? = null

    /** The process's tracker, once [startTracker] built it; `null` before. */
    @Volatile var tracker: P2pTracker? = null
        private set

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Hands the tracker what only the app has. Call once, in `Application.onCreate`, before [startTracker].
     *
     * @param tokens the app's DMarket token layer (`TokenManager.getTokenData(forceRefresh)`).
     * @param linkedAccount the Steam account the logged-in user links, or `null` when none; read on every start.
     * @param hostOkHttp the app's `OkHttpClient` for DMarket calls; `null` takes the platform's.
     * @param clientVersion the app's version, for the heartbeat.
     */
    fun install(
        context: Context,
        tokens: MarketplaceTokenSupplier,
        linkedAccount: suspend () -> SteamId?,
        hostOkHttp: OkHttpClient? = null,
        clientVersion: String = TradeTrackerCore.VERSION,
    ) {
        installation = Installation(AndroidTrackerPlatform(context, tokens), linkedAccount, hostOkHttp, clientVersion)
    }

    /** The tracker, built on the first call; later calls return the same one, whatever they pass. */
    internal fun trackerFor(
        baseUrl: String,
        config: TrackerConfig,
        networkObserver: NetworkObserver,
        eventObserver: EventObserver,
    ): P2pTracker = tracker ?: synchronized(this) {
        tracker
            ?: build(
                checkNotNull(installation) {
                    "call AndroidTracker.install(...) first"
                },
                baseUrl,
                config,
                networkObserver,
                eventObserver,
            )
                .also { tracker = it }
    }

    private fun build(
        installed: Installation,
        baseUrl: String,
        config: TrackerConfig,
        networkObserver: NetworkObserver,
        eventObserver: EventObserver,
    ): P2pTracker {
        val platform = installed.platform
        val timeoutMs = config.http.requestTimeoutMs.toLong()
        val steamHttp = platform.httpEngine?.let { createSteamHttpClient(it, timeoutMs, networkObserver) }
            ?: createSteamHttpClient(requestTimeoutMs = timeoutMs, observer = networkObserver)
        val marketplace = installed.hostOkHttp?.let { androidMarketplaceClient(it, baseUrl, platform.marketplaceAuthenticator, timeoutMs) }
            ?: KtorMarketplaceClient(
                httpClient = platform.httpEngine?.let { createMarketplaceHttpClient(it, timeoutMs, networkObserver) }
                    ?: createMarketplaceHttpClient(requestTimeoutMs = timeoutMs, observer = networkObserver),
                baseUrl = baseUrl,
                authenticator = platform.marketplaceAuthenticator,
                retry = config.marketplaceRetry,
            )
        val loopFor: suspend (SteamId, EventObserver) -> TradeTrackerLoop = { account, observer ->
            val stores = platform.stores(account, config)
            TradeTrackerCore(GameRegistry.v1(config.game)).createLoop(
                config = LoopConfig(
                    clientVersion = installed.clientVersion,
                    surface = RuntimeSurface.AndroidNative,
                    mode = TrackerMode.Background,
                    tunables = config,
                ),
                marketplace = marketplace,
                steamReader = KtorSteamReadClient(steamHttp, config.steamEndpoints),
                scraper = KtorSteamSessionScraper(steamHttp, config.steamScrape, config.steamEndpoints.communityBaseUrl),
                // Never armed: P2pTracker drives the loop itself.
                scheduler = CoroutineScheduler(scope),
                deviceId = platform.deviceIds,
                vault = platform.vault,
                clock = platform.clock,
                marketplaceCredentials = platform.marketplaceCredentials,
                progress = stores.progress,
                claims = stores.claims,
                unprovenClaims = stores.unprovenClaims,
                offerGuard = stores.offerGuard,
                throttle = stores.throttle,
                notaryThrottle = stores.notaryThrottle,
                loopState = stores.loopState,
                eventObserver = observer,
            )
        }
        return P2pTracker(
            scope = scope,
            clock = platform.clock,
            deviceIds = platform.deviceIds,
            linkedAccount = installed.linkedAccount,
            loopFor = loopFor,
            eventObserver = eventObserver,
        )
    }
}
