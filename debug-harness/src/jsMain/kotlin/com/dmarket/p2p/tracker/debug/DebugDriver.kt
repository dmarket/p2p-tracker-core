package com.dmarket.p2p.tracker.debug

import com.dmarket.p2p.tracker.adapter.host.SystemClock
import com.dmarket.p2p.tracker.adapter.platformScheduler
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
import com.dmarket.p2p.tracker.config.TrackerConfig
import com.dmarket.p2p.tracker.credential.steam.DefaultSteamSessionRefresher
import com.dmarket.p2p.tracker.game.Cs2GameAdapter
import com.dmarket.p2p.tracker.game.GameRegistry
import com.dmarket.p2p.tracker.loop.LoopConfig
import com.dmarket.p2p.tracker.loop.TradeTrackerLoop
import com.dmarket.p2p.tracker.model.RuntimeSurface
import com.dmarket.p2p.tracker.model.TrackerMode
import com.dmarket.p2p.tracker.net.NetworkRedaction
import com.dmarket.p2p.tracker.port.host.EventObserver
import com.dmarket.p2p.tracker.port.host.NetworkObserver
import com.dmarket.p2p.tracker.runtime.TradeTrackerCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.promise

/**
 * A self-driving debug loop + the teardown to stop it. [loop.createTrade] is the FE-triggered create;
 * [nudge] forces one loop cycle now — a fresh `/heartbeat` (bypassing both the `ttl_seconds` cadence
 * gate and the coalesce guard) → watch — so a just-created offer's `active_tracking` and any leased
 * directives are picked up immediately instead of waiting for the next alarm.
 */
internal class DebugDriver(val loop: TradeTrackerLoop, val nudge: () -> Unit, private val onStop: () -> Unit) {
    fun stop() = onStop()
}

/** Cycles requested within this window collapse to one (dedupes the MV3 boot + onAlarm double-fire). */
private const val CYCLE_COALESCE_MS: Double = 5_000.0

/**
 * Builds and self-drives a [TradeTrackerLoop] for the **integration test**: real DMarket backend, real
 * DMarket frontend + backend, and **live Steam**.
 *
 * **Steam wiring:** all Steam reads/writes/inventory hit real Steam. A `create` therefore POSTs a
 * **real** trade offer (stops at `NeedsConfirmation`; the user confirms in the mobile Steam app — the
 * port has no confirm surface, so nothing auto-transfers). The credential scraper + session refresher
 * likewise run against **live** `steamcommunity.com` — the real backend validates
 * `heartbeat.steam_id == account.linked_steam_id`, so identity must be real.
 *
 * `directivesEnabled = true` keeps the real C1 behaviour (the backend may lease `create_offer` and the
 * loop executes it). Independently, the FE "create trade" postMessage is routed to
 * [TradeTrackerLoop.createTrade] by the debug facade — both paths appear in the session log.
 */
internal fun startDebugTracker(
    baseUrl: String,
    networkObserver: NetworkObserver,
    eventObserver: EventObserver,
    marketplaceFeUrl: String = "",
): DebugDriver {
    val config = TrackerConfig.defaults()
    // The DMarket bearer token lives in the `dm-trade-token` cookie on the FE origin (NOT the API
    // base URL). When targeting a non-prod env, read + refresh it against that FE host instead of the
    // default https://dmarket.com/, so the session token comes from the environment under test.
    val marketplaceScrape =
        if (marketplaceFeUrl.isBlank()) {
            config.marketplaceScrape
        } else {
            config.marketplaceScrape.copy(refreshUrl = marketplaceFeUrl)
        }
    val clock = SystemClock()
    val vault = WebExtStorageCredentialVault()
    val live = config.steamEndpoints // live Steam base URLs
    val steamHttp = createSteamHttpClient(
        requestTimeoutMs = config.http.requestTimeoutMs.toLong(),
        observer = networkObserver,
        // Without this a renamed `paramAccessToken` puts the raw Steam JWT into the session log, which
        // has a one-click export.
        secretParamNames = NetworkRedaction.plusSecretParam(config.steamEndpoints.paramAccessToken),
    )

    // Identity: LIVE steamcommunity.com — real steamId + token so the real backend leases directives.
    val scraper =
        KtorSteamSessionScraper(httpClient = steamHttp, scrapeConfig = config.steamScrape, communityBaseUrl = live.communityBaseUrl)
    val sessionRefresher = DefaultSteamSessionRefresher(
        gateway = FetchSteamWebSessionGateway(httpClient = steamHttp),
        clock = clock,
        gateHeadroom = config.credentials.sessionGateHeadroom,
        loginBaseUrl = live.loginBaseUrl,
        communityBaseUrl = live.communityBaseUrl,
        storeBaseUrl = live.storeBaseUrl,
        sessionCookieName = config.steamScrape.steamSessionCookieName,
        sessionIdCookieName = config.steamScrape.steamSessionIdCookieName,
    )

    val mpHttp = createMarketplaceHttpClient(requestTimeoutMs = config.http.requestTimeoutMs.toLong(), observer = networkObserver)

    val marketplaceCredentials = createBrowserMarketplaceCredentials(
        baseUrl = baseUrl,
        config = config.copy(marketplaceScrape = marketplaceScrape),
        clock = clock,
        networkObserver = networkObserver,
    )
    val marketplace = KtorMarketplaceClient(
        httpClient = mpHttp,
        baseUrl = baseUrl,
        authenticator = CredentialMarketplaceAuthenticator(marketplaceCredentials),
    )
    val steamReader = KtorSteamReadClient(httpClient = steamHttp, endpoints = live)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val scheduler = platformScheduler(scope, TrackerMode.Background)

    val loop = TradeTrackerCore(GameRegistry.v1(config.game)).createLoop(
        config = LoopConfig(TradeTrackerCore.VERSION, RuntimeSurface.WebChrome, TrackerMode.Background, tunables = config),
        marketplace = marketplace,
        steamReader = steamReader,
        scraper = scraper,
        scheduler = scheduler,
        deviceId = WebExtStorageDeviceIdStore(),
        vault = vault,
        clock = clock,
        // Steam WRITES + inventory: live community (audit-locked path suffixes stay hardcoded). All observed.
        offerCreator = FetchSteamOfferCreator(
            httpClient = steamHttp,
            communityBaseUrl = live.communityBaseUrl,
            adapter = Cs2GameAdapter(config.game),
        ),
        offerCanceller = FetchSteamOfferCanceller(httpClient = steamHttp, communityBaseUrl = live.communityBaseUrl),
        inventoryReader = KtorSteamInventoryReader(
            httpClient = steamHttp,
            communityBaseUrl = live.communityBaseUrl,
            adapter = Cs2GameAdapter(config.game),
            pageCount = config.steamEndpoints.inventoryPageCount,
            maxPages = config.steamEndpoints.inventoryMaxPages,
        ),
        notifications = KtorSteamNotificationReader(
            httpClient = steamHttp,
            endpoints = config.steamEndpoints,
        ),
        sessionRefresher = sessionRefresher,
        marketplaceCredentials = marketplaceCredentials,
        progress = WebExtStorageTrackerProgressStore(),
        loopState = WebExtStorageLoopStateStore(),
        eventObserver = eventObserver,
        // Real C1 behaviour: the real backend may lease create_offer/cancel_offer directives.
        directivesEnabled = true,
    )

    // Self-drive: one chrome.alarms entry, re-armed each cycle (mirrors Tracker.js.kt:73-112).
    val alarmName = WebExtAlarmsScheduler.DEFAULT_ALARM_NAME
    val chrome: dynamic = js("typeof chrome !== 'undefined' ? chrome : browser")

    // MV3 double-cycle guard: when a repeating alarm respawns a dead worker, BOTH the synchronous boot
    // cycle() below AND the freshly-registered onAlarm listener fire for the same wake (~ms apart). This
    // synchronous time-gate collapses them into one; legitimate cycles (≥ the 60s alarm floor) pass.
    var lastCycleMs = 0.0
    fun cycle() {
        val now = kotlin.js.Date.now()
        if (now - lastCycleMs < CYCLE_COALESCE_MS) return
        lastCycleMs = now
        scope.promise {
            loop.runOnce()
            scheduler.schedule(loop.nextWakeDelay())
            Unit
        }
    }

    // Explicit "force tick" (dashboard button / FE fallback / post-create): unlike the alarm-driven
    // cycle() this bypasses the coalesce guard AND forces a heartbeat, so it always POSTs /heartbeat
    // (re-fetching directives + active_tracking) instead of just watching between-heartbeat wakes.
    // lastCycleMs is still stamped so the imminent onAlarm double-fire on a respawn stays coalesced.
    fun forceCycle() {
        lastCycleMs = kotlin.js.Date.now()
        scope.promise {
            loop.forceHeartbeatNow()
            loop.runOnce()
            scheduler.schedule(loop.nextWakeDelay())
            Unit
        }
    }

    val onAlarm: dynamic = { alarm: dynamic ->
        if (alarm.name == alarmName) cycle()
        Unit
    }
    chrome.alarms.onAlarm.addListener(onAlarm)
    // Arm-if-absent (mirrors Tracker.js.kt): don't clobber a pending expedited alarm on respawn —
    // re-creating it resets its fire time to now + a full fresh-state period.
    chrome.alarms.get(alarmName) { existing: dynamic ->
        if (existing == null) scheduler.schedule(loop.nextWakeDelay())
    }
    cycle()

    return DebugDriver(loop, nudge = { forceCycle() }) {
        chrome.alarms.onAlarm.removeListener(onAlarm)
        scheduler.cancel()
        scope.cancel()
    }
}
