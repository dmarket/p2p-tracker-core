package com.dmarket.p2p.tracker.runtime

import com.dmarket.p2p.tracker.client.createMarketplaceHttpClient
import com.dmarket.p2p.tracker.client.createSteamHttpClient
import com.dmarket.p2p.tracker.client.marketplace.KtorMarketplaceClient
import com.dmarket.p2p.tracker.client.marketplace.MarketplaceAuthenticator
import com.dmarket.p2p.tracker.client.steam.HeaderSessionSteamOfferCanceller
import com.dmarket.p2p.tracker.client.steam.HeaderSessionSteamOfferCreator
import com.dmarket.p2p.tracker.client.steam.KtorSteamInventoryReader
import com.dmarket.p2p.tracker.client.steam.KtorSteamReadClient
import com.dmarket.p2p.tracker.client.steam.KtorSteamSessionScraper
import com.dmarket.p2p.tracker.client.steam.KtorSteamWebSessionGateway
import com.dmarket.p2p.tracker.client.steam.VaultCookieJar
import com.dmarket.p2p.tracker.config.MarketplaceRetryConfig
import com.dmarket.p2p.tracker.config.TrackerConfig
import com.dmarket.p2p.tracker.credential.steam.DefaultSteamSessionRefresher
import com.dmarket.p2p.tracker.game.Cs2GameAdapter
import com.dmarket.p2p.tracker.game.GameRegistry
import com.dmarket.p2p.tracker.loop.LoopConfig
import com.dmarket.p2p.tracker.loop.TradeTrackerLoop
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.TrackerMode
import com.dmarket.p2p.tracker.model.steam.SteamCookie
import com.dmarket.p2p.tracker.port.host.EventObserver
import com.dmarket.p2p.tracker.port.host.NetworkObserver
import com.dmarket.p2p.tracker.port.host.Scheduler
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceClient
import com.dmarket.p2p.tracker.port.steam.SteamWebSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.concurrent.Volatile
import kotlin.time.Duration

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
 * The mobile loop's scheduler: arms nothing. [P2pTracker] drives every cycle (on screen by polling, else on a push),
 * and a timer in the process scope would outlive [P2pTracker.stop] and [P2pTracker.onLogout].
 */
private object NoWakeScheduler : Scheduler {
    override val ticks: Flow<Unit> = emptyFlow()

    override fun schedule(delay: Duration) = Unit

    override fun cancel() = Unit
}

/**
 * The mobile tracker's assembly, the same on Android and iOS: the HTTP clients, the loop per Steam account and the
 * [P2pTracker] over them. A platform gives only its adapters ([platform]); the app gives the rest of the host
 * contract.
 *
 * The Steam session lives in the core's vault ([MobileTrackerPlatform.steamCookies]): imported once after a Steam
 * sign-in ([importSteamSession]), sent from there in an explicit `Cookie` header, refreshed from
 * `steamRefresh_steam` and written back. The loop runs the directives the backend leases; the offer writes
 * (`create_offer` / `cancel_offer`) go out on that session ([HeaderSessionSteamOfferCreator] /
 * [HeaderSessionSteamOfferCanceller] over the vault) only with [steamWrites] on.
 *
 * @param linkedAccount the Steam account the logged-in user links, or `null` when none; read on every start.
 * @param cellularProofs the user's "proofs on mobile data" option, read before every cycle; the app owns its default.
 * @param clientVersion the app's version, for the heartbeat.
 * @param hostMarketplaceClient the DMarket client over the host's HTTP stack; `null` builds the core's own over
 *   [MobileTrackerPlatform.dmarketEngine].
 * @param steamWrites whether this device writes Steam offers ([LoopConfig.steamWrites]). Off by default: offer
 *   creation in production waits for DMarket's security sign-off, so the app turns it on per environment. Off, a
 *   leased `create_offer` / `cancel_offer` is dropped unreported and its lease lapses.
 * @param reportUnsentCreates [LoopConfig.reportUnsentCreates]: whether a `create_offer` that never reached Steam is
 *   reported `failed`.
 */
class MobileTracker(
    val platform: MobileTrackerPlatform,
    private val linkedAccount: suspend () -> SteamId?,
    private val cellularProofs: () -> Boolean,
    private val clientVersion: String = TradeTrackerCore.VERSION,
    private val hostMarketplaceClient: MarketplaceClientFactory? = null,
    private val steamWrites: Boolean = false,
    private val reportUnsentCreates: Boolean = true,
) {
    /** The tracker [tracker] built last; the host's [importSteamSession] goes through it once there is one. */
    @Volatile private var built: P2pTracker? = null

    /**
     * The host's Steam import, the same on Android and iOS. **Contract:** the host calls it right after its user signs
     * in to Steam in the app (a Steam sign-in to DMarket included), whatever the tracker's state: not built yet, built
     * and stopped, or running. Never on start or on coming on screen: the web view keeps the `steamLoginSecure` the core
     * has since refreshed in the vault, and a second import would put the stale one back. Android reads [cookies] from
     * the WebView's `CookieManager` (`AndroidTracker.importSteamSession()`); iOS reads them from `WKHTTPCookieStore`
     * (DMA-715) and calls this same point.
     *
     * The vault is written in every case ([MobileTrackerPlatform.importSteamSession]). With a tracker built, it goes
     * through [P2pTracker.importSteamSession]: a running loop also heartbeats and publishes the session's state, which
     * is returned. Otherwise `null`; the first cycle after the start reads the vault and publishes
     * [TrackerState.steamSession].
     */
    suspend fun importSteamSession(cookies: List<SteamCookie>): SteamWebSessionState? {
        built?.let { return it.importSteamSession(cookies) }
        platform.importSteamSession(cookies)
        return null
    }

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
        // Cookies from the core's vault only, in an explicit header; Set-Cookie goes back to it (DMA-662).
        val cookies = VaultCookieJar(platform.steamCookies, platform.clock)
        val steamHttp = createSteamHttpClient(platform.steamEngine(), timeoutMs, networkObserver, cookies = cookies)
        // One refresher for the process: every loop's provider and the ON_START check run it under their locks.
        val refresher = DefaultSteamSessionRefresher(
            gateway = KtorSteamWebSessionGateway(steamHttp, cookies),
            clock = platform.clock,
            loginBaseUrl = config.steamEndpoints.loginBaseUrl,
            communityBaseUrl = config.steamEndpoints.communityBaseUrl,
            storeBaseUrl = config.steamEndpoints.storeBaseUrl,
        )
        val marketplace = hostMarketplaceClient?.create(baseUrl, platform.marketplaceAuthenticator, timeoutMs, config.marketplaceRetry)
            ?: KtorMarketplaceClient(
                httpClient = createMarketplaceHttpClient(platform.dmarketEngine(), timeoutMs, networkObserver),
                baseUrl = baseUrl,
                authenticator = platform.marketplaceAuthenticator,
                retry = config.marketplaceRetry,
            )
        val community = config.steamEndpoints.communityBaseUrl
        // The session is the vault's, through the client's jar: one `steamLoginSecure` and one `sessionid` per request.
        val offerCreator = HeaderSessionSteamOfferCreator(steamHttp, cookies, community, Cs2GameAdapter(config.game))
        val offerCanceller = HeaderSessionSteamOfferCanceller(steamHttp, cookies, community)
        val loopFor: suspend (SteamId, EventObserver) -> TradeTrackerLoop = { account, observer ->
            val stores = platform.stores(account, config)
            TradeTrackerCore(GameRegistry.v1(config.game)).createLoop(
                config = LoopConfig(
                    clientVersion = clientVersion,
                    surface = platform.surface,
                    mode = TrackerMode.Background,
                    tunables = config,
                    steamWrites = steamWrites,
                    reportUnsentCreates = reportUnsentCreates,
                    steamSessionInVault = true,
                    hostLinkedSteamId = account,
                    reportEachWrite = true,
                ),
                marketplace = marketplace,
                steamReader = KtorSteamReadClient(steamHttp, config.steamEndpoints),
                scraper = KtorSteamSessionScraper(steamHttp, config.steamScrape, community),
                sessionRefresher = refresher,
                offerCreator = offerCreator,
                offerCanceller = offerCanceller,
                inventoryReader = KtorSteamInventoryReader(steamHttp, community),
                scheduler = NoWakeScheduler,
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
                directivesEnabled = true,
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
            importSteamCookies = platform::importSteamSession,
        ).also { built = it }
    }
}
