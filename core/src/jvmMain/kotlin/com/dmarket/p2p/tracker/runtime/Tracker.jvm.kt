package com.dmarket.p2p.tracker.runtime

import com.dmarket.p2p.tracker.config.TrackerConfig
import com.dmarket.p2p.tracker.port.host.EventObserver
import com.dmarket.p2p.tracker.port.host.NetworkObserver

/**
 * The JVM target exists for fast unit tests and has no Steam-facing actuals (no session scraper /
 * read client), so there is no standard background driver to wire. A JVM/desktop host composes a
 * loop with [TradeTrackerCore.createLoop] and drives it with its own [com.dmarket.p2p.tracker.port.host.Scheduler].
 */
actual fun startTracker(
    baseUrl: String,
    config: TrackerConfig,
    networkObserver: NetworkObserver,
    eventObserver: EventObserver,
): TrackerHandle = throw UnsupportedOperationException(
    "startTracker has no JVM runtime. Compose a loop with TradeTrackerCore.createLoop(...) " +
        "and drive it with your own Scheduler.",
)
