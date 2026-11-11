package com.dmarket.p2p.tracker.runtime

import com.dmarket.p2p.tracker.config.TrackerConfig
import com.dmarket.p2p.tracker.port.host.EventObserver
import com.dmarket.p2p.tracker.port.host.NetworkObserver
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * Starts the process's [P2pTracker] ([AndroidTracker.tracker]) for the linked account. Needs
 * [AndroidTracker.install] first, which is where the `Context` comes from. The first call builds the tracker with
 * these arguments; later calls start the same one. [TrackerHandle.stop] stops it; a later start waits for that stop.
 */
actual fun startTracker(
    baseUrl: String,
    config: TrackerConfig,
    networkObserver: NetworkObserver,
    eventObserver: EventObserver,
): TrackerHandle {
    val tracker = AndroidTracker.trackerFor(baseUrl, config, networkObserver, eventObserver)
    // Undispatched: each call queues on the tracker's lock in call order, so a stop then a start end started.
    AndroidTracker.scope.launch(start = CoroutineStart.UNDISPATCHED) { tracker.start() }
    return object : TrackerHandle {
        override fun stop() {
            AndroidTracker.scope.launch(start = CoroutineStart.UNDISPATCHED) { tracker.stop() }
        }
    }
}
