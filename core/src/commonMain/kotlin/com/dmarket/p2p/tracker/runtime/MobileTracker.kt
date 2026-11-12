package com.dmarket.p2p.tracker.runtime

import com.dmarket.p2p.tracker.adapter.host.CoroutineScheduler
import com.dmarket.p2p.tracker.client.createMarketplaceHttpClient
import com.dmarket.p2p.tracker.client.createSteamHttpClient
import com.dmarket.p2p.tracker.client.marketplace.KtorMarketplaceClient
import com.dmarket.p2p.tracker.client.marketplace.MarketplaceAuthenticator
import com.dmarket.p2p.tracker.client.steam.KtorSteamReadClient
import com.dmarket.p2p.tracker.client.steam.KtorSteamSessionScraper
import com.dmarket.p2p.tracker.config.MarketplaceRetryConfig
import com.dmarket.p2p.tracker.config.TrackerConfig
import com.dmarket.p2p.tracker.game.GameRegistry
import com.dmarket.p2p.tracker.loop.LoopConfig
import com.dmarket.p2p.tracker.loop.TradeTrackerLoop
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.TrackerMode
import com.dmarket.p2p.tracker.port.host.EventObserver
import com.dmarket.p2p.tracker.port.host.NetworkObserver
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceClient
import kotlinx.coroutines.CoroutineScope

/**
 * Builds the DMarket client over the host's own HTTP stack (on Android, the app's `OkHttpClient`). The client must
 * authenticate through [authenticator]; the host's stack adds no auth of its own.
 */
fun interface MarketplaceClientFactory {
    fun create(
        baseUrl: String,
        authenticator: MarketplaceAuthenticator,
        requestTimeoutMs: Long,
        retry: MarketplaceRetryConfig,
    ): MarketplaceClient
}

/**
 * The mobile tracker's assembly, the same on Android and iOS: the HTTP clients, the loop per Steam account and the
 * [P2pTracker] over them. A platform gives only its adapters ([platform]); the app gives the rest of the host
 * contract.
 *
 * Not production-ready on its own: the Steam session (vault, cookies, offer writes) is DMA-662. Until then the loop
 * reads the session with the shared scraper and leaves `create_offer` / `cancel_offer` leases to lapse.
 *
 * @param linkedAccount the Steam account the logged-in user links, or `null` when none; read on every start.
 * @param cellularProofs the user's "proofs on mobile data" option, read before every cycle; the app owns its default.
 * @param clientVersion the app's version, for the heartbeat.
 * @param hostMarketplaceClient the DMarket client over the host's HTTP stack; `null` builds the core's own over
 *   [MobileTrackerPlatform.dmarketEngine].
 */
class MobileTracker(
    val platform: MobileTrackerPlatform,
    private val linkedAccount: suspend () -> SteamId?,
    private val cellularProofs: () -> Boolean,
    private val clientVersion: String = TradeTrackerCore.VERSION,
    private val hostMarketplaceClient: MarketplaceClientFactory? = null,
) {
    /**
     * A new [P2pTracker] in [scope]. The loop is never scheduled: the tracker drives it, on screen by polling and in
     * the background only on a push. [P2pTracker.onLogout] erases the account state and the Steam credential.
     */
    fun tracker(
        scope: CoroutineScope,
        baseUrl: String,
        config: TrackerConfig,
        networkObserver: NetworkObserver,
        eventObserver: EventObserver,
    ): P2pTracker {
        val timeoutMs = config.http.requestTimeoutMs.toLong()
        val steamHttp = createSteamHttpClient(platform.steamEngine(), timeoutMs, networkObserver)
        val marketplace = hostMarketplaceClient?.create(baseUrl, platform.marketplaceAuthenticator, timeoutMs, config.marketplaceRetry)
            ?: KtorMarketplaceClient(
                httpClient = createMarketplaceHttpClient(platform.dmarketEngine(), timeoutMs, networkObserver),
                baseUrl = baseUrl,
                authenticator = platform.marketplaceAuthenticator,
                retry = config.marketplaceRetry,
            )
        val loopFor: suspend (SteamId, EventObserver) -> TradeTrackerLoop = { account, observer ->
            val stores = platform.stores(account, config)
            TradeTrackerCore(GameRegistry.v1(config.game)).createLoop(
                config = LoopConfig(
                    clientVersion = clientVersion,
                    surface = platform.surface,
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
            linkedAccount = linkedAccount,
            loopFor = loopFor,
            network = platform.network,
            cellularProofs = cellularProofs,
            eventObserver = eventObserver,
            clearAccountState = platform::clearAccountState,
        )
    }
}
