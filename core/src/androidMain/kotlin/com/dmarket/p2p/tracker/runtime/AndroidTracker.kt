package com.dmarket.p2p.tracker.runtime

import android.content.Context
import com.dmarket.p2p.tracker.client.marketplace.androidMarketplaceClient
import com.dmarket.p2p.tracker.config.TrackerConfig
import com.dmarket.p2p.tracker.credential.marketplace.MarketplaceTokenSupplier
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.port.host.EventObserver
import com.dmarket.p2p.tracker.port.host.NetworkObserver
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

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Hands the tracker what only the app has. Call once, in `Application.onCreate`, before [startTracker].
     *
     * @param tokens the app's DMarket token layer (`TokenManager.getTokenData(forceRefresh)`).
     * @param linkedAccount the Steam account the logged-in user links, or `null` when none; read on every start.
     * @param cellularProofs the user's "proofs on mobile data" option (T-04), read before every cycle; the app owns its
     *   default (off). Reading the network needs `ACCESS_NETWORK_STATE`; without it every network reads as metered.
     * @param hostOkHttp the app's `OkHttpClient` for DMarket calls; `null` takes the platform's.
     * @param clientVersion the app's version, for the heartbeat.
     */
    fun install(
        context: Context,
        tokens: MarketplaceTokenSupplier,
        linkedAccount: suspend () -> SteamId?,
        cellularProofs: () -> Boolean,
        hostOkHttp: OkHttpClient? = null,
        clientVersion: String = TradeTrackerCore.VERSION,
    ) {
        installation = MobileTracker(
            platform = AndroidTrackerPlatform(context, tokens),
            linkedAccount = linkedAccount,
            cellularProofs = cellularProofs,
            clientVersion = clientVersion,
            hostMarketplaceClient = hostOkHttp?.let { okHttp ->
                MarketplaceClientFactory { baseUrl, authenticator, timeoutMs, retry ->
                    androidMarketplaceClient(okHttp, baseUrl, authenticator, timeoutMs, retry)
                }
            },
        )
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
