package com.dmarket.p2p.tracker.adapter.host

import com.dmarket.p2p.tracker.model.LifecycleEvent
import com.dmarket.p2p.tracker.port.host.EventObserver

/** The default [EventObserver]: discards every event (zero overhead in production). */
object NoOpEventObserver : EventObserver {
    override suspend fun onEvent(event: LifecycleEvent) = Unit
}
