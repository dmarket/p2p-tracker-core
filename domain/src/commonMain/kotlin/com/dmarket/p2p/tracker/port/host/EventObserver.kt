package com.dmarket.p2p.tracker.port.host

import com.dmarket.p2p.tracker.model.LifecycleEvent

/**
 * A passive sink for [LifecycleEvent]s emitted by the loop at its nodal points (cycle start/end,
 * heartbeat, directive execution, status report, credential/login transitions). The default
 * [com.dmarket.p2p.tracker.adapter.host.NoOpEventObserver] makes it zero-overhead in production.
 *
 * Like [NetworkObserver], implementations must be passive and tolerant of being called from the loop's
 * coroutine; events carry only public ids and enum names, never a credential.
 */
fun interface EventObserver {
    suspend fun onEvent(event: LifecycleEvent)
}
