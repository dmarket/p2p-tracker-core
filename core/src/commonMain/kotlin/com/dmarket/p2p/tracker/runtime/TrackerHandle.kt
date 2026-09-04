package com.dmarket.p2p.tracker.runtime

import com.dmarket.p2p.tracker.adapter.host.NoOpEventObserver
import com.dmarket.p2p.tracker.adapter.host.NoOpNetworkObserver
import com.dmarket.p2p.tracker.config.TrackerConfig
import com.dmarket.p2p.tracker.port.host.EventObserver
import com.dmarket.p2p.tracker.port.host.NetworkObserver

/**
 * The handle returned by [startTracker]. The lib drives itself after [startTracker]; the consumer
 * only calls [stop] to tear the tracker down.
 */
interface TrackerHandle {
    /** Stop the tracker: clear the platform wake-up and detach listeners. */
    fun stop()
}

/**
 * Start the self-driving tracker — **the single cross-platform lifecycle entry**. After this call
 * the library reschedules its own heartbeat/watch cycle (via the platform wake-up) and reacts to
 * backend pushes with no further consumer involvement; call [TrackerHandle.stop] to stop.
 *
 * One `expect` declaration, one `actual` per platform — the platform glue (which wake-up mechanism,
 * how listeners are registered) lives entirely in the `actual`. Each actual obtains its [Scheduler]
 * from `platformScheduler(scope, mode)` (the cross-platform selection mechanism):
 * - **web** (`actual`): `chrome.alarms` + the Service Worker `push` event; survives MV3
 *   service-worker teardown. Must be called synchronously at the service-worker top level.
 * - **iOS / Android** (`actual`, when those targets are enabled): `BGTaskScheduler` / `WorkManager`
 *   + APNs / FCM.
 * - **JVM**: not supported — compose a loop with [TradeTrackerCore.createLoop] and drive it yourself.
 *
 * @param baseUrl the DMarket API base URL. Defaults to [TrackerConfig.DEFAULT_DMARKET_BASE_URL] so a
 *   host can boot with zero configuration.
 * @param config the host-suppliable [TrackerConfig] bundle (cadence, backoff, endpoints, regexes, …).
 *   Defaults to [TrackerConfig.defaults] — the in-code baseline — so omitting it changes nothing.
 * @param networkObserver an optional passive observer of every HTTP exchange (redacted); defaults to
 *   no-op. Environment-agnostic, so mobile hosts can forward exchanges to their own telemetry.
 * @param eventObserver an optional passive observer of loop lifecycle events; defaults to no-op.
 */
expect fun startTracker(
    baseUrl: String = TrackerConfig.DEFAULT_DMARKET_BASE_URL,
    config: TrackerConfig = TrackerConfig.defaults(),
    networkObserver: NetworkObserver = NoOpNetworkObserver,
    eventObserver: EventObserver = NoOpEventObserver,
): TrackerHandle
