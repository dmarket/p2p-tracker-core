package com.dmarket.p2p.tracker.runtime

import android.content.Context
import com.dmarket.p2p.tracker.adapter.WebViewSteamCookies
import com.dmarket.p2p.tracker.client.marketplace.androidMarketplaceClient
import com.dmarket.p2p.tracker.config.TrackerConfig
import com.dmarket.p2p.tracker.credential.marketplace.MarketplaceTokenSupplier
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.steam.SteamCookie
import com.dmarket.p2p.tracker.port.host.EventObserver
import com.dmarket.p2p.tracker.port.host.NetworkObserver
import com.dmarket.p2p.tracker.port.steam.SteamWebSessionState
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/**
 * The Android tracker's process-wide entry. `startTracker` takes no `Context`, so the app hands it over once, in
 * `Application.onCreate`, with [install]; [startTracker] then builds the one [P2pTracker] of the process with
 * [MobileTracker] over an [AndroidTrackerPlatform] and starts it, and [tracker] is what the app and
 * [P2pTrackerWorker] call.
 *
 * One tracker per process is what keeps the account rule: every start and stop goes through its one lock, so the
 * previous account's loop has ended before the next account's stores open.
 */
object AndroidTracker {
    @Volatile private var installation: MobileTracker? = null

    /** The process's tracker, once [startTracker] built it; `null` before. */
    @Volatile var tracker: P2pTracker? = null
        private set

    /**
     * Where [startTracker] runs the tracker's start and stop. A failure there must not crash the app's process: the
     * tracker answers its own faults with [com.dmarket.p2p.tracker.model.LifecycleEvent.CycleFailed], so the handler
     * only keeps an escaped one from reaching the thread's uncaught-exception handler.
     */
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> })

    /**
     * Hands the tracker what only the app has. Call once, in `Application.onCreate`, before [startTracker].
     *
     * @param tokens the app's DMarket token layer (`TokenManager.getTokenData(forceRefresh)`).
     * @param linkedAccount the Steam account the logged-in user links, or `null` when none; read on every start.
     * @param cellularProofs the user's "proofs on mobile data" option (T-04), read before every cycle; the app owns its
     *   default (off). Reading the network needs `ACCESS_NETWORK_STATE`; without it every network reads as metered.
     * @param hostOkHttp the app's `OkHttpClient` for DMarket calls; `null` takes the platform's.
     * @param clientVersion the app's version, for the heartbeat.
     * @param steamWrites whether this device writes Steam offers (`create_offer` / `cancel_offer`); off by default, the
     *   app sets it per environment. See [MobileTracker].
     * @param reportUnsentCreates whether a `create_offer` that never reached Steam is reported `failed`.
     */
    fun install(
        context: Context,
        tokens: MarketplaceTokenSupplier,
        linkedAccount: suspend () -> SteamId?,
        cellularProofs: () -> Boolean,
        hostOkHttp: OkHttpClient? = null,
        clientVersion: String = TradeTrackerCore.VERSION,
        steamWrites: Boolean = false,
        reportUnsentCreates: Boolean = true,
    ) = install(
        AndroidTrackerPlatform(context, tokens),
        linkedAccount,
        cellularProofs,
        hostOkHttp,
        clientVersion,
        steamWrites,
        reportUnsentCreates,
    )

    /** [install] over a ready [platform]; host tests build it over fake prefs and a fake Keystore. */
    internal fun install(
        platform: MobileTrackerPlatform,
        linkedAccount: suspend () -> SteamId?,
        cellularProofs: () -> Boolean,
        hostOkHttp: OkHttpClient? = null,
        clientVersion: String = TradeTrackerCore.VERSION,
        steamWrites: Boolean = false,
        reportUnsentCreates: Boolean = true,
    ) {
        installation = MobileTracker(
            platform = platform,
            linkedAccount = linkedAccount,
            cellularProofs = cellularProofs,
            clientVersion = clientVersion,
            hostMarketplaceClient = hostOkHttp?.let { okHttp ->
                MarketplaceClientFactory { baseUrl, authenticator, timeoutMs, retry ->
                    androidMarketplaceClient(okHttp, baseUrl, authenticator, timeoutMs, retry)
                }
            },
            steamWrites = steamWrites,
            reportUnsentCreates = reportUnsentCreates,
        )
    }

    /**
     * The app's user just signed in to Steam in the app's WebView (`steamSignedIn`, a Steam sign-in to DMarket
     * included): the WebView's Steam cookies become the core's Steam session ([MobileTracker.importSteamSession], whose
     * contract this follows). Call it right after that sign-in, whether or not the tracker is built or runs; never on
     * start: the WebView keeps the `steamLoginSecure` the core has since refreshed in its vault.
     *
     * After [install] the vault is always written. Returns the session's state when a loop runs, else `null` (the first
     * cycle after the start reads it); before [install], `null` and nothing is read or written.
     */
    suspend fun importSteamSession(): SteamWebSessionState? = importSteamSession { WebViewSteamCookies().read() }

    /** [importSteamSession] over [read]; the WebView is read only once [install] ran. */
    internal suspend fun importSteamSession(read: () -> List<SteamCookie>): SteamWebSessionState? = installation?.importSteamSession(read())

    /**
     * The user logged out of DMarket: erases the account's state, the Steam credential and the Steam session's cookies,
     * whether or not the tracker is built or runs ([MobileTracker.onLogout], whose contract this follows); the device id
     * stays. Before [install] it does nothing.
     */
    suspend fun onLogout() {
        installation?.onLogout()
    }

    /** The tracker, built on the first call; later calls return the same one, whatever they pass. */
    internal fun trackerFor(
        baseUrl: String,
        config: TrackerConfig,
        networkObserver: NetworkObserver,
        eventObserver: EventObserver,
    ): P2pTracker = tracker ?: synchronized(this) {
        tracker
            ?: checkNotNull(installation) { "call AndroidTracker.install(...) first" }
                .tracker(scope, baseUrl, config, networkObserver, eventObserver)
                .also { tracker = it }
    }
}
