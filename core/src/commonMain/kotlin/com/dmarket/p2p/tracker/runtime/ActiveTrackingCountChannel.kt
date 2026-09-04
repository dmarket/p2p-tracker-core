package com.dmarket.p2p.tracker.runtime

import com.dmarket.p2p.tracker.adapter.host.NoOpEventObserver
import com.dmarket.p2p.tracker.model.LifecycleEvent
import com.dmarket.p2p.tracker.port.host.EventObserver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A live gauge of **how many trades the tracker is actively watching** right now — the size of the
 * backend's `active_tracking[]`, surfaced as a reactive [StateFlow].
 *
 * It is an [EventObserver] **decorator**: wire it in as the loop's `eventObserver` (via `startTracker`
 * / `TradeTrackerCore.createLoop`) and every event still flows through to [delegate] unchanged, while
 * the active count is kept in sync from the loop's own cycle events — [LifecycleEvent.CycleCompleted]
 * (`watching`, emitted at the end of **every** cycle, incl. between-heartbeat wakes) and
 * [LifecycleEvent.HeartbeatSent] (`trackingCount`, on each heartbeat). No loop changes are needed.
 *
 * ```
 * val counter = ActiveTrackingCountChannel()
 * val handle = startTracker(eventObserver = counter)
 * scope.launch { counter.count.collect { n -> /* render "Activity on DMarket: n" */ } }
 * // or read the latest value synchronously: counter.count.value
 * ```
 *
 * [count] is a [StateFlow]: it always holds the latest count (initially `0`, before the first cycle),
 * a new collector immediately receives the current value, and repeated identical counts are conflated
 * (no duplicate emissions). Updates are non-suspending, so a slow or absent collector never stalls
 * the loop. Mirrors the [com.dmarket.p2p.tracker.adapter.host.CoroutineScheduler] `MutableSharedFlow`
 * pattern, with a `StateFlow` because a count is state, not a stream of discrete events.
 *
 * @param delegate the observer to forward **all** lifecycle events to (defaults to a no-op).
 */
class ActiveTrackingCountChannel(private val delegate: EventObserver = NoOpEventObserver) : EventObserver {

    private val _count = MutableStateFlow(0)

    /** The current number of actively-tracked trades. */
    val count: StateFlow<Int> = _count.asStateFlow()

    override suspend fun onEvent(event: LifecycleEvent) {
        // Update the count BEFORE forwarding: delivery to the delegate is synchronous, and a host that
        // reads the count from inside its event handler must see the value this event implies, not the
        // previous one (same set-state-before-emit discipline as the loop's sticky states).
        when (event) {
            is LifecycleEvent.CycleCompleted -> _count.value = event.watching
            is LifecycleEvent.HeartbeatSent -> _count.value = event.trackingCount
            else -> Unit
        }
        delegate.onEvent(event) // forward the full lifecycle firehose unchanged
    }
}
