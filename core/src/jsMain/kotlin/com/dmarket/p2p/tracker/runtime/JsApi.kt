@file:OptIn(ExperimentalJsExport::class)

package com.dmarket.p2p.tracker.runtime

import com.dmarket.p2p.tracker.adapter.host.NoOpEventObserver
import com.dmarket.p2p.tracker.adapter.host.NoOpNetworkObserver
import com.dmarket.p2p.tracker.adapter.host.SystemClock
import com.dmarket.p2p.tracker.adapter.notary.MarketplaceNotaryTokenProvider
import com.dmarket.p2p.tracker.adapter.notary.NoOpNotaryProver
import com.dmarket.p2p.tracker.adapter.webext.WebExtAlarmsScheduler
import com.dmarket.p2p.tracker.adapter.webext.WebExtStorageCredentialVault
import com.dmarket.p2p.tracker.adapter.webext.WebExtStorageDeviceIdStore
import com.dmarket.p2p.tracker.adapter.webext.WebExtStorageLoopStateStore
import com.dmarket.p2p.tracker.adapter.webext.WebExtStorageTrackerProgressStore
import com.dmarket.p2p.tracker.client.createMarketplaceHttpClient
import com.dmarket.p2p.tracker.client.createSteamHttpClient
import com.dmarket.p2p.tracker.client.marketplace.CredentialMarketplaceAuthenticator
import com.dmarket.p2p.tracker.client.marketplace.KtorMarketplaceClient
import com.dmarket.p2p.tracker.client.marketplace.createBrowserMarketplaceCredentials
import com.dmarket.p2p.tracker.client.steam.FetchSteamOfferCanceller
import com.dmarket.p2p.tracker.client.steam.FetchSteamOfferCreator
import com.dmarket.p2p.tracker.client.steam.FetchSteamWebSessionGateway
import com.dmarket.p2p.tracker.client.steam.KtorSteamInventoryReader
import com.dmarket.p2p.tracker.client.steam.KtorSteamNotificationReader
import com.dmarket.p2p.tracker.client.steam.KtorSteamReadClient
import com.dmarket.p2p.tracker.client.steam.KtorSteamSessionScraper
import com.dmarket.p2p.tracker.config.SteamEndpointsConfig
import com.dmarket.p2p.tracker.config.TrackerConfig
import com.dmarket.p2p.tracker.credential.steam.DefaultSteamSessionRefresher
import com.dmarket.p2p.tracker.game.Cs2GameAdapter
import com.dmarket.p2p.tracker.game.GameRegistry
import com.dmarket.p2p.tracker.loop.LoopConfig
import com.dmarket.p2p.tracker.loop.TradeTrackerLoop
import com.dmarket.p2p.tracker.model.RuntimeSurface
import com.dmarket.p2p.tracker.model.TrackerMode
import com.dmarket.p2p.tracker.net.NetworkRedaction
import com.dmarket.p2p.tracker.notary.DelegatingNotaryProver
import com.dmarket.p2p.tracker.port.host.EventObserver
import com.dmarket.p2p.tracker.port.host.NetworkObserver
import com.dmarket.p2p.tracker.port.steam.SteamReadClient
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlin.js.Promise

/**
 * The JavaScript surface the web extension imports (as an ES module with generated `.d.ts`).
 *
 * Kept deliberately thin: the extension uses factory functions to obtain instances, then calls
 * their `suspend` methods (which become JS Promises under Kotlin/JS IR).
 *
 * **Audit boundary:** [com.dmarket.p2p.tracker.model.steam.SteamCredential] is **never** exported. The
 * scraper, vault, and Steam ports are wired internally in [createBrowserLoop] so JS code only ever
 * sees an opaque [TradeTrackerLoop] handle.
 */

@JsExport
fun trackerCoreVersion(): String = TradeTrackerCore.VERSION

@JsExport
fun enabledGameCount(): Int = TradeTrackerCore().enabledGameCount()

/**
 * Creates a fully-wired [TradeTrackerLoop] for a Chrome/Firefox extension service worker that drives
 * the golden C1 trade-tracker contract: heartbeat presence, device-leased
 * directive execution (`create_offer` / `cancel_offer` / `report_inventory`), and raw-code
 * watch-and-report (+ decisive proofs on `proof_required` deals).
 *
 * **Most consumers want [startTracker] instead** — it builds this loop and self-drives it via
 * `chrome.alarms`. Use `createBrowserLoop` only to drive `runOnce()` yourself.
 *
 * Internally wires the marketplace client (the C1 `/exchange/v1/p2p/ext/` endpoints on the gateway),
 * the raw Steam reader + inventory reader, the credential scraper, the Steam create-trade actual
 * ([FetchSteamOfferCreator]), the `chrome.alarms` scheduler, and durable `chrome.storage`
 * tracker-progress / loop-state stores; it never exposes the Steam credential.
 *
 * **Directives are enabled on this path** and not host-configurable: the loop executes the `create_offer`
 * / `cancel_offer` / `report_inventory` commands the backend leases to this `device_id` and reports each
 * outcome on `/trade-actions`. Which device is allowed to execute one is the *backend's* call — it holds
 * the lease — so the gate stays library scope, to be driven by the backend once cross-device arbitration
 * lands. A web loop built with it off answers nothing, so the backend re-leases every directive on every
 * heartbeat and the deal parks (visibly: each ignored directive emits a `DirectiveDropped`).
 *
 * **Re-login prompt (signal-only):** the core never opens UI. Poll `needsReLogin` /
 * `needsMarketplaceReLogin`; when `true`, open the corresponding login tab. The flag clears once the
 * next background scrape recovers.
 *
 * **TLSN proofs:** the prover ships **inside this package** and is located at runtime, so you import
 * nothing and configure no bundler alias. Enabling it needs [notaryProofDelegate] and nothing else —
 * `config.notary.notaryUrl` already defaults to the deployed production notary — so pass a delegate and
 * the real prover runs; omit it and the no-op, client-reported prover does. Whether an individual deal
 * needs a proof is the backend's per-deal `proof_required`, never a client flag.
 *
 * The delegate is required because this loop cannot run the prover where it lives. An MV3 service
 * worker exposes no `Worker` constructor for the prover's thread pool, so the proof must happen in an
 * **offscreen document**: relay the JSON your delegate receives to that document, call
 * `proveNotaryTransition` there, and resolve with the base64 string it returns. The delegate is called
 * with `(requestJson, notaryToken, steamAccessToken)`: the JSON carries no credential — only ids and the
 * public `subjectSteamId` — while the two tokens are passed separately because they are not
 * interchangeable. `notaryToken` is the live DMarket one this loop already refreshes and authenticates the
 * client to the notary; `steamAccessToken` authenticates the **proven read** to `api.steampowered.com`,
 * which is the call whose status integer the client reports.
 *
 * That second token is a deliberate widening of the credential seam, forced by the proven read being a
 * token-authed API call rather than a cookie-authed page. It stays device-only: MPC means the notary sees
 * only ciphertext, and the spec withholds the request target so the query string never enters the
 * presentation. Nothing sends it to DMarket. Both tokens are resolved here so no downstream context needs
 * its own refresh logic.
 *
 * Two hosting duties come with it: ship `pkg/` and `transport/` from this package at the extension root
 * as `web_accessible_resources`, and make that document cross-origin isolated (COEP `require-corp` +
 * COOP `same-origin`) — the module needs `SharedArrayBuffer` at **any** `threadCount`.
 *
 * **Manifest requirements:** `"storage"`, `"cookies"`, `"alarms"`, `"tabs"`, and `host_permissions`
 * for `https://api.steampowered.com`, `https://steamcommunity.com`, `https://login.steampowered.com`,
 * `https://store.steampowered.com` and `https://dmarket.com/`.
 */
@Suppress("NON_EXPORTABLE_TYPE")
@JsExport
fun createBrowserLoop(
    baseUrl: String = TrackerConfig.DEFAULT_DMARKET_BASE_URL,
    config: TrackerConfig = TrackerConfig.defaults(),
    networkObserver: NetworkObserver = NoOpNetworkObserver,
    eventObserver: EventObserver = NoOpEventObserver,
    notaryProofDelegate: ((String, String, String) -> Promise<String>)? = null,
): TradeTrackerLoop {
    val clock = SystemClock()
    val vault = WebExtStorageCredentialVault()
    // One credentialed Steam transport shared by every Steam-facing Ktor client (reader, inventory,
    // session scraper). createSteamHttpClient → platformHttpEngine(credentialsInclude=true) sets fetch
    // credentials:"include", so the logged-in Steam cookie session rides along and reads no longer 403.
    val steamHttpClient = createSteamHttpClient(
        requestTimeoutMs = config.http.requestTimeoutMs.toLong(),
        observer = networkObserver,
        secretParamNames = steamSecretParamNames(config.steamEndpoints),
    )
    val scraper = KtorSteamSessionScraper(
        httpClient = steamHttpClient,
        scrapeConfig = config.steamScrape,
        communityBaseUrl = config.steamEndpoints.communityBaseUrl,
    )
    val sessionRefresher = DefaultSteamSessionRefresher(
        gateway = FetchSteamWebSessionGateway(httpClient = steamHttpClient),
        clock = clock,
        gateHeadroom = config.credentials.sessionGateHeadroom,
        loginBaseUrl = config.steamEndpoints.loginBaseUrl,
        communityBaseUrl = config.steamEndpoints.communityBaseUrl,
        storeBaseUrl = config.steamEndpoints.storeBaseUrl,
        sessionCookieName = config.steamScrape.steamSessionCookieName,
        sessionIdCookieName = config.steamScrape.steamSessionIdCookieName,
    )
    val marketplaceHttpClient =
        createMarketplaceHttpClient(requestTimeoutMs = config.http.requestTimeoutMs.toLong(), observer = networkObserver)
    val marketplaceCredentials = createBrowserMarketplaceCredentials(
        baseUrl = baseUrl,
        config = config,
        clock = clock,
        networkObserver = networkObserver,
    )
    val marketplace = KtorMarketplaceClient(
        httpClient = marketplaceHttpClient,
        baseUrl = baseUrl,
        authenticator = CredentialMarketplaceAuthenticator(marketplaceCredentials),
        retry = config.marketplaceRetry,
    )
    // RefreshingSteamReadClient wraps this in TradeTrackerCore.createLoop for the 401/403 self-heal.
    val steamReader = KtorSteamReadClient(httpClient = steamHttpClient, endpoints = config.steamEndpoints)
    // Real TLSN proofs need somewhere to run: this loop is a service worker, which has no `Worker`
    // constructor for the prover's thread pool, so without a delegate there is no such place and the safe
    // no-op (client-reported) prover runs instead of one guaranteed to fail on first use.
    //
    // ONE condition, not three. The old `NotaryConfig.enabled` flag was redundant with the backend's
    // per-deal `proof_required`; `notaryUrl` then stopped being part of the gate too, because it now
    // defaults to the deployed production notary (see NotaryConfig's KDoc for both). Whether a deal NEEDS a
    // proof is the backend's call; whether this client CAN produce one is what this one condition answers.
    val notary = if (notaryProofDelegate != null) {
        DelegatingNotaryProver(
            maxConcurrency = config.notary.maxConcurrency,
            tokenProvider = MarketplaceNotaryTokenProvider(marketplaceCredentials),
            delegate = notaryProofDelegate,
        )
    } else {
        NoOpNotaryProver
    }
    val loopConfig = LoopConfig(
        clientVersion = TradeTrackerCore.VERSION,
        surface = RuntimeSurface.WebChrome,
        mode = TrackerMode.Background,
        tunables = config,
    )
    return TradeTrackerCore(GameRegistry.v1(config.game)).createLoop(
        config = loopConfig,
        marketplace = marketplace,
        steamReader = steamReader,
        scraper = scraper,
        scheduler = WebExtAlarmsScheduler(),
        deviceId = WebExtStorageDeviceIdStore(),
        vault = vault,
        clock = clock,
        notary = notary,
        offerCreator = FetchSteamOfferCreator(
            httpClient = steamHttpClient,
            communityBaseUrl = config.steamEndpoints.communityBaseUrl,
            adapter = Cs2GameAdapter(config.game),
        ),
        offerCanceller = FetchSteamOfferCanceller(
            httpClient = steamHttpClient,
            communityBaseUrl = config.steamEndpoints.communityBaseUrl,
        ),
        inventoryReader = KtorSteamInventoryReader(
            httpClient = steamHttpClient,
            communityBaseUrl = config.steamEndpoints.communityBaseUrl,
            adapter = Cs2GameAdapter(config.game),
            pageCount = config.steamEndpoints.inventoryPageCount,
            maxPages = config.steamEndpoints.inventoryMaxPages,
        ),
        notifications = KtorSteamNotificationReader(
            httpClient = steamHttpClient,
            endpoints = config.steamEndpoints,
        ),
        sessionRefresher = sessionRefresher,
        marketplaceCredentials = marketplaceCredentials,
        // Durable across worker respawns so the loop doesn't re-execute directives / re-report codes each wake.
        progress = WebExtStorageTrackerProgressStore(),
        loopState = WebExtStorageLoopStateStore(),
        eventObserver = eventObserver,
        // On, and deliberately NOT a host knob: which device may execute a directive is the backend's
        // decision (it leases each one to a single `device_id`), so this stays library scope and becomes
        // backend-driven when cross-device arbitration lands — a host-settable flag would just be a second
        // authority racing that one. The original gate ("keep this false until the lease is live") is
        // spent, and leaving it off is not a safe default but a silent stall: the backend re-serves each
        // leased create on every heartbeat, forever, while the client reports nothing and the deal parks.
        // Kept a `createLoop` parameter (not a constant) so it can take that backend-driven value without
        // reopening this exported signature, and so tests can still drive the watch axis alone.
        directivesEnabled = true,
        // Push delivery is the host's job (it calls `deliverPush`); the lib owns no push transport.
    )
}

/**
 * Creates a [KtorMarketplaceClient] pre-configured with the browser fetch engine and the browser DMarket
 * credential chain — the bearer comes from the logged-in `dmarket.com` cookie session and is refreshed
 * through the DMarket refresh API when it nears expiry. No manual seeding required.
 */
@Suppress("NON_EXPORTABLE_TYPE")
@JsExport
fun createBrowserMarketplaceClient(baseUrl: String, config: TrackerConfig = TrackerConfig.defaults()): KtorMarketplaceClient {
    val clock = SystemClock()
    val credentials = createBrowserMarketplaceCredentials(
        baseUrl = baseUrl,
        config = config,
        clock = clock,
        networkObserver = NoOpNetworkObserver,
    )
    return KtorMarketplaceClient(
        httpClient = createMarketplaceHttpClient(),
        baseUrl = baseUrl,
        authenticator = CredentialMarketplaceAuthenticator(credentials),
        retry = config.marketplaceRetry,
    )
}

/**
 * Creates a standalone Steam read client over the credentialed browser engine
 * ([createSteamHttpClient], fetch `credentials:"include"`) — Steam binds the web `access_token` to
 * the cookie session, so a cookie-less client 403s on `IEconService`.
 */
@Suppress("NON_EXPORTABLE_TYPE")
@JsExport
fun createBrowserSteamClient(): SteamReadClient = SteamEndpointsConfig().let { endpoints ->
    KtorSteamReadClient(
        httpClient = createSteamHttpClient(secretParamNames = steamSecretParamNames(endpoints)),
        endpoints = endpoints,
    )
}

/**
 * Augments the default redaction set with the config's Steam `access_token` query-param name when it
 * has been renamed from the default (which is already redacted), so a host cannot desync the
 * observer's redactor and leak the raw JWT from an observed Steam-read URL.
 */
private fun steamSecretParamNames(endpoints: SteamEndpointsConfig): Set<String> =
    NetworkRedaction.plusSecretParam(endpoints.paramAccessToken)
