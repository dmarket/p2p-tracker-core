package com.dmarket.p2p.tracker.runtime

import app.cash.turbine.test
import com.dmarket.p2p.tracker.model.LifecycleEvent
import com.dmarket.p2p.tracker.support.RecordingEventObserver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ActiveTrackingCountChannelTest {

    private fun cycle(watching: Int) =
        LifecycleEvent.CycleCompleted(directivesExecuted = 0, reportsSent = 0, proofsSubmitted = 0, watching = watching)

    private fun heartbeat(tracking: Int) = LifecycleEvent.HeartbeatSent(ttlSeconds = 60, trackingCount = tracking, directiveCount = 0)

    @Test
    fun initial_count_is_zero() {
        assertEquals(0, ActiveTrackingCountChannel().count.value)
    }

    @Test
    fun cycle_completed_sets_the_count_to_watching() = runTest {
        val channel = ActiveTrackingCountChannel()
        channel.onEvent(cycle(watching = 3))
        assertEquals(3, channel.count.value)
    }

    @Test
    fun heartbeat_sets_the_count_to_tracking() = runTest {
        val channel = ActiveTrackingCountChannel()
        channel.onEvent(heartbeat(tracking = 5))
        assertEquals(5, channel.count.value)
    }

    @Test
    fun unrelated_events_leave_the_count_unchanged_but_reach_the_delegate() = runTest {
        val delegate = RecordingEventObserver()
        val channel = ActiveTrackingCountChannel(delegate = delegate)
        val status = LifecycleEvent.TradeStatusReported("deal-1", "offer", 9)

        channel.onEvent(cycle(watching = 2))
        channel.onEvent(status) // not a count-bearing event
        channel.onEvent(LifecycleEvent.CycleStarted)

        assertEquals(2, channel.count.value, "count only tracks CycleCompleted/HeartbeatSent")
        assertEquals(listOf(cycle(watching = 2), status, LifecycleEvent.CycleStarted), delegate.events)
    }

    @Test
    fun count_flow_replays_current_value_then_emits_changes_conflated() = runTest {
        val channel = ActiveTrackingCountChannel()
        channel.count.test {
            assertEquals(0, awaitItem()) // StateFlow replays the current value to a new collector
            channel.onEvent(cycle(watching = 2))
            assertEquals(2, awaitItem())
            channel.onEvent(heartbeat(tracking = 2)) // same value → conflated, no emission
            channel.onEvent(cycle(watching = 4))
            assertEquals(4, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
