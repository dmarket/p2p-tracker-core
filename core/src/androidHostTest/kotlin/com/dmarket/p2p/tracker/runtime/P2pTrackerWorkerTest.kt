package com.dmarket.p2p.tracker.runtime

import com.dmarket.p2p.tracker.adapter.host.InMemoryDeviceKeyValueStore
import com.dmarket.p2p.tracker.adapter.host.PersistedDeviceIdStore
import com.dmarket.p2p.tracker.adapter.host.SystemClock
import com.dmarket.p2p.tracker.model.WakeCause
import com.dmarket.p2p.tracker.model.WakeOutcome
import com.dmarket.p2p.tracker.port.marketplace.WakeAcknowledger
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class P2pTrackerWorkerTest {
    private val wake = """{"v":"1","type":"wake_all","wakeId":"w-1","reason":"watch"}"""
    private val acks = mutableListOf<Pair<String, WakeOutcome>>()
    private val trackers = MutableStateFlow<P2pTracker?>(null)

    /** A tracker with no linked Steam account: a wake acks `done` on receipt, then ends `failed` / `steam_session`. */
    private fun TestScope.tracker() = P2pTracker(
        scope = backgroundScope,
        clock = SystemClock(),
        deviceIds = PersistedDeviceIdStore(InMemoryDeviceKeyValueStore()),
        linkedAccount = { null },
        loopFor = { _, _ -> error("no Steam account, no loop") },
        network = { false },
        cellularProofs = { false },
        acknowledger = WakeAcknowledger { _, wakeId, outcome -> acks += wakeId to outcome },
    )

    private val ranOnTracker = listOf("w-1" to WakeOutcome.DONE, "w-1" to WakeOutcome.failed(WakeCause.STEAM_SESSION))

    @Test
    fun a_wake_waits_for_the_tracker_the_host_builds_and_then_runs_on_it() = runTest {
        val run = async { runWake(wake, trackers, time = testTimeSource) }
        advanceTimeBy(90.seconds)
        runCurrent()
        assertFalse(run.isCompleted, "still waiting for the host")

        trackers.value = tracker()

        assertEquals(WakeOutcome.failed(WakeCause.STEAM_SESSION), run.await())
        assertEquals(ranOnTracker, acks)
    }

    @Test
    fun a_wake_runs_at_once_on_a_tracker_already_built() = runTest {
        trackers.value = tracker()

        runWake(wake, trackers, time = testTimeSource)

        assertEquals(0L, testScheduler.currentTime)
        assertEquals(ranOnTracker, acks)
    }

    @Test
    fun no_tracker_within_the_wait_ends_failed_with_no_ack() = runTest {
        val outcome = runWake(wake, trackers, hostWait = 2.minutes, time = testTimeSource)

        assertEquals(WakeOutcome.failed(WakeCause.OTHER), outcome)
        assertEquals(2.minutes.inWholeMilliseconds, testScheduler.currentTime)
        assertEquals(emptyList(), acks)
    }

    @Test
    fun the_wait_comes_out_of_the_wake_deadline() = runTest {
        val run = async { runWake(wake, trackers, hostWait = 2.minutes, deadline = 1.minutes, time = testTimeSource) }
        advanceTimeBy(1.minutes + 1.seconds)
        trackers.value = tracker()

        assertEquals(WakeOutcome.failed(WakeCause.BUDGET), run.await(), "no time left: the tracker says so without a sync")
        assertEquals(emptyList(), acks.filter { it.second == WakeOutcome.DONE })
    }

    @Test
    fun a_payload_that_is_no_wake_ends_at_once_without_an_outcome() = runTest {
        assertNull(runWake("""{"type":"chat"}""", trackers, time = testTimeSource))
        assertEquals(0L, testScheduler.currentTime)
    }
}
