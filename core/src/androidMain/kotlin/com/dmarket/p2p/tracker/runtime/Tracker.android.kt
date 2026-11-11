package com.dmarket.p2p.tracker.runtime

import com.dmarket.p2p.tracker.config.TrackerConfig
import com.dmarket.p2p.tracker.port.host.EventObserver
import com.dmarket.p2p.tracker.port.host.NetworkObserver

/**
 * No Android runtime yet: the WorkManager-driven tracker comes with the Android facade. Until then an
 * Android host composes a loop with [TradeTrackerCore.createLoop] and drives it with its own
 * [com.dmarket.p2p.tracker.port.host.Scheduler].
 */
actual fun startTracker(
    baseUrl: String,
    config: TrackerConfig,
    networkObserver: NetworkObserver,
    eventObserver: EventObserver,
): TrackerHandle = throw UnsupportedOperationException(
    "startTracker has no Android runtime yet. Compose a loop with TradeTrackerCore.createLoop(...) " +
        "and drive it with your own Scheduler.",
)
