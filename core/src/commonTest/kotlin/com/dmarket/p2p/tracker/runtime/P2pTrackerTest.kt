package com.dmarket.p2p.tracker.runtime

import com.dmarket.p2p.tracker.adapter.host.NoOpWakeAcknowledger
import com.dmarket.p2p.tracker.adapter.steam.NoOpSteamSessionRefresher
import com.dmarket.p2p.tracker.credential.steam.SteamCredentialProvider
import com.dmarket.p2p.tracker.engine.TrackerBlock
import com.dmarket.p2p.tracker.loop.LoopConfig
import com.dmarket.p2p.tracker.loop.TradeTrackerLoop
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.DeviceId
import com.dmarket.p2p.tracker.model.LifecycleEvent
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.RuntimeSurface
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.TrackerMode
import com.dmarket.p2p.tracker.model.marketplace.HeartbeatRequest
import com.dmarket.p2p.tracker.model.marketplace.HeartbeatResponse
import com.dmarket.p2p.tracker.model.marketplace.TrackedDeal
import com.dmarket.p2p.tracker.model.marketplace.WatchTarget
import com.dmarket.p2p.tracker.port.host.EventObserver
import com.dmarket.p2p.tracker.port.host.NetworkConditions
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceClient
import com.dmarket.p2p.tracker.port.marketplace.WakeAcknowledger
import com.dmarket.p2p.tracker.support.FakeClock
import com.dmarket.p2p.tracker.support.FakeCredentialVault
import com.dmarket.p2p.tracker.support.FakeDeviceIdStore
import com.dmarket.p2p.tracker.support.FakeMarketplaceClient
import com.dmarket.p2p.tracker.support.FakeScheduler
import com.dmarket.p2p.tracker.support.FakeSteamReadClient
import com.dmarket.p2p.tracker.support.FakeSteamSessionScraper
import com.dmarket.p2p.tracker.support.RecordingEventObserver
import com.dmarket.p2p.tracker.support.fakeSteamCredential
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class P2pTrackerTest {
    private val accountA = SteamId("76561198000000001")
    private val accountB = SteamId("76561198000000002")
    private val wakeDeal = """{"type":"wake_deal","dealId":"d1","reason":"buyer_accepted","issuedAt":"2026-11-11T10:00:00Z"}"""
    private val wakeAll = """{"type":"wake_all","reason":"new_deal"}"""

    private val clock = FakeClock()
    private val events = RecordingEventObserver()

    private fun tracked(dealId: String = "d1", offerId: String = "offer-1") = TrackedDeal(
        dealId = DealId(dealId),
        steamOfferId = OfferId(offerId),
        watch = setOf(WatchTarget.GET_TRADE_OFFER),
    )

    private fun loop(marketplace: MarketplaceClient, observer: EventObserver, reader: FakeSteamReadClient = FakeSteamReadClient()) =
        TradeTrackerLoop(
            config = LoopConfig("1.0.0", RuntimeSurface.AndroidNative, TrackerMode.Background),
            marketplace = marketplace,
            steamReader = reader,
            credentials = SteamCredentialProvider(
                vault = FakeCredentialVault(fakeSteamCredential()),
                scraper = FakeSteamSessionScraper(),
                clock = clock,
                sessionRefresher = NoOpSteamSessionRefresher,
            ),
            scheduler = FakeScheduler(),
            clock = clock,
            deviceId = FakeDeviceIdStore(),
            eventObserver = observer,
        )

    private fun TestScope.tracker(
        marketplace: MarketplaceClient,
        reader: FakeSteamReadClient = FakeSteamReadClient(),
        acknowledger: WakeAcknowledger = NoOpWakeAcknowledger,
    ) = P2pTracker(
        scope = backgroundScope,
        clock = clock,
        deviceIds = FakeDeviceIdStore(),
        linkedAccount = { accountA },
        loopFor = { _, observer -> loop(marketplace, observer, reader) },
        network = NetworkConditions.Unmetered,
        cellularProofs = { false },
        acknowledger = acknowledger,
        eventObserver = events,
    )

    /** Records each ack with how many heartbeats had gone out by then. */
    private class RecordingAcknowledger(private val marketplace: FakeMarketplaceClient) : WakeAcknowledger {
        val heartbeatsAtAck = mutableListOf<Int>()
        override suspend fun acknowledge(deviceId: DeviceId, wake: com.dmarket.p2p.tracker.model.Wake) {
            heartbeatsAtAck += marketplace.heartbeatsSent.size
        }
    }

    /** Holds each heartbeat for [hold] (virtual time) and counts how many run at once. */
    private class SlowMarketplace(val fake: FakeMarketplaceClient, var hold: (suspend () -> Unit)? = null) : MarketplaceClient by fake {
        var running = 0
        var maxRunning = 0
        var attempts = 0

        override suspend fun heartbeat(request: HeartbeatRequest): HeartbeatResponse {
            attempts += 1
            running += 1
            maxRunning = maxOf(maxRunning, running)
            try {
                hold?.invoke()
                return fake.heartbeat(request)
            } finally {
                running -= 1
            }
        }
    }

    @Test
    fun a_wake_deal_is_acknowledged_once_before_the_sync_which_watches_and_reports() = runTest {
        val mp = FakeMarketplaceClient(HeartbeatResponse(activeTracking = listOf(tracked()), ttlSeconds = 86_400))
        val acks = RecordingAcknowledger(mp)
        val reader = FakeSteamReadClient(initialOffers = mapOf(OfferId("offer-1") to 3))
        val tracker = tracker(mp, reader, acks)

        assertTrue(tracker.onPush(wakeDeal))

        assertEquals(listOf(0), acks.heartbeatsAtAck, "one ack per push, sent before the sync's heartbeat")
        assertEquals(1, mp.heartbeatsSent.size)
        assertFalse(mp.heartbeatsSent.single().foreground, "a background sync heartbeats with foreground=false")
        assertEquals(listOf(3), mp.tradeStatusReports.map { it.steamStatusCode })
        val wakeAt = events.events.indexOfFirst { it is LifecycleEvent.WakeReceived }
        val beatAt = events.events.indexOfFirst { it is LifecycleEvent.HeartbeatSent }
        assertTrue(wakeAt in 0 until beatAt)
        assertEquals(LifecycleEvent.WakeReceived("wake_deal", "d1", null, acknowledged = true), events.events[wakeAt])

        val state = tracker.state.value
        assertTrue(state.running)
        assertEquals(accountA, state.account)
        assertEquals(clock.now(), state.lastSyncAt)
        assertEquals(DealTrackingState(checkedAt = clock.now(), lastCode = 3), state.deals[DealId("d1")])
        assertEquals(DeviceId("test-device-1"), tracker.deviceId())
    }

    @Test
    fun every_wake_all_syncs_even_with_the_heartbeat_not_due() = runTest {
        val mp = FakeMarketplaceClient(HeartbeatResponse(activeTracking = listOf(tracked()), ttlSeconds = 86_400))
        val tracker = tracker(mp)

        assertTrue(tracker.onPush(wakeAll))
        assertTrue(tracker.onPush(wakeAll))

        assertEquals(2, mp.heartbeatsSent.size, "a wake always heartbeats, so the guard and the claim release run")
    }

    @Test
    fun an_unknown_or_unreadable_envelope_is_ignored_without_ack_or_sync() = runTest {
        val mp = FakeMarketplaceClient()
        val acks = RecordingAcknowledger(mp)
        val tracker = tracker(mp, acknowledger = acks)

        assertFalse(tracker.onPush("""{"type":"presence_ping","dealId":"d1"}"""))
        assertFalse(tracker.onPush("""{"type":"wake_deal"}"""))
        assertFalse(tracker.onPush("not json"))

        assertTrue(acks.heartbeatsAtAck.isEmpty())
        assertEquals(0, mp.heartbeatAttempts)
        assertFalse(tracker.state.value.running, "an ignored push starts nothing")
    }

    @Test
    fun a_failed_ack_still_syncs() = runTest {
        val mp = FakeMarketplaceClient()
        val tracker = tracker(mp, acknowledger = { _, _ -> error("ack refused") })

        assertTrue(tracker.onPush(wakeAll))

        assertEquals(1, mp.heartbeatsSent.size)
        assertEquals(false, events.events.filterIsInstance<LifecycleEvent.WakeReceived>().single().acknowledged)
    }

    @Test
    fun only_the_foreground_polls() = runTest {
        val mp = FakeMarketplaceClient(HeartbeatResponse(activeTracking = listOf(tracked()), ttlSeconds = 86_400))
        val tracker = tracker(mp)
        fun cycles() = events.events.count { it == LifecycleEvent.CycleStarted }

        tracker.start()
        advanceTimeBy(1.hours)
        assertEquals(0, cycles(), "the background schedules no wake")

        tracker.setForeground(true)
        runCurrent()
        assertTrue(mp.heartbeatsSent.single().foreground, "on screen the heartbeat says foreground")
        advanceTimeBy(15.minutes)
        assertTrue(cycles() > 2, "on screen the tracker polls")

        tracker.setForeground(false)
        val before = cycles()
        advanceTimeBy(2.hours)
        assertEquals(before, cycles(), "back in the background, polling stops")
        assertFalse(tracker.state.value.foreground)
    }

    @Test
    fun a_day_long_ttl_wakes_nothing_in_the_background() = runTest {
        val mp = FakeMarketplaceClient(HeartbeatResponse(activeTracking = listOf(tracked()), ttlSeconds = 86_400))
        val tracker = tracker(mp)

        tracker.onPush(wakeAll)
        advanceTimeBy(25.hours)

        assertEquals(1, mp.heartbeatsSent.size, "no heartbeat is woken for ttl_seconds; pushes and the foreground run cycles")
        assertEquals(1, events.events.count { it == LifecycleEvent.CycleStarted })
    }

    @Test
    fun a_wake_sync_raises_the_wrong_account_block() = runTest {
        val mp = FakeMarketplaceClient(HeartbeatResponse(activeTracking = listOf(tracked()), ttlSeconds = 86_400, linkedSteamId = accountB))
        val reader = FakeSteamReadClient(initialOffers = mapOf(OfferId("offer-1") to 3))
        val tracker = tracker(mp, reader)

        tracker.onPush(wakeDeal)

        val state = tracker.state.value
        assertEquals(TrackerBlock.STEAM_ACCOUNT_MISMATCH, state.block)
        assertTrue(state.accountMismatch)
        assertEquals(accountB, state.linkedSteamId)
        assertEquals(0, reader.offerStatusesCalls, "a wrong-account session reads nothing on Steam")
        assertTrue(mp.tradeStatusReports.isEmpty())
    }

    @Test
    fun parallel_pushes_and_the_worker_run_one_cycle_at_a_time() = runTest {
        val mp = SlowMarketplace(FakeMarketplaceClient(), hold = { delay(1.seconds) })
        val tracker = tracker(mp)

        val jobs = List(3) { launch { tracker.onPush(wakeAll) } }
        jobs.forEach { it.join() }

        assertEquals(3, mp.fake.heartbeatsSent.size)
        assertEquals(1, mp.maxRunning)
    }

    @Test
    fun a_cancelled_cycle_leaves_the_lock_free() = runTest {
        val gate = CompletableDeferred<Unit>()
        val mp = SlowMarketplace(FakeMarketplaceClient(), hold = { gate.await() })
        val tracker = tracker(mp)

        val stuck = launch { tracker.onPush(wakeAll) }
        runCurrent()
        assertEquals(1, mp.running)
        stuck.cancelAndJoin()

        mp.hold = null
        assertTrue(tracker.onPush(wakeAll))
        assertEquals(1, mp.fake.heartbeatsSent.size, "the next wake gets the lock and syncs")
        assertEquals(2, mp.attempts)
    }

    @Test
    fun a_cycle_past_its_deadline_is_cancelled_and_the_next_one_runs() = runTest {
        val mp = SlowMarketplace(FakeMarketplaceClient(), hold = { awaitCancellation() })
        val tracker = P2pTracker(
            scope = backgroundScope,
            clock = clock,
            deviceIds = FakeDeviceIdStore(),
            linkedAccount = { accountA },
            loopFor = { _, observer -> loop(mp, observer) },
            network = NetworkConditions.Unmetered,
            cellularProofs = { false },
            cycleDeadline = 30.seconds,
        )

        assertTrue(tracker.onPush(wakeAll))
        mp.hold = null
        tracker.onPush(wakeAll)

        assertEquals(1, mp.fake.heartbeatsSent.size)
        assertTrue(currentTime >= 30_000)
    }

    @Test
    fun another_account_waits_for_the_previous_loop_to_end_before_its_stores_open() = runTest {
        var linked = accountA
        val log = mutableListOf<String>()
        val heldA = SlowMarketplace(FakeMarketplaceClient(), hold = {
            try {
                awaitCancellation()
            } finally {
                log += "A ended"
            }
        })
        val tracker = P2pTracker(
            scope = backgroundScope,
            clock = clock,
            deviceIds = FakeDeviceIdStore(),
            linkedAccount = { linked },
            loopFor = { account, observer ->
                log += "stores of ${account.value}"
                loop(if (account == accountA) heldA else FakeMarketplaceClient(), observer)
            },
            network = NetworkConditions.Unmetered,
            cellularProofs = { false },
        )

        launch { tracker.onPush(wakeAll) }
        runCurrent()
        assertEquals(1, heldA.running, "account A's cycle is in flight")

        linked = accountB
        assertTrue(tracker.start())

        assertEquals(listOf("stores of ${accountA.value}", "A ended", "stores of ${accountB.value}"), log)
        assertEquals(accountB, tracker.state.value.account)
    }

    @Test
    fun a_start_after_close_waits_for_the_closed_loop_to_end_before_its_stores_open() = runTest {
        val log = mutableListOf<String>()
        val held = SlowMarketplace(FakeMarketplaceClient(), hold = {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { delay(5.seconds) }
                log += "closed loop ended"
            }
        })
        val tracker = P2pTracker(
            scope = backgroundScope,
            clock = clock,
            deviceIds = FakeDeviceIdStore(),
            linkedAccount = { accountA },
            loopFor = { _, observer ->
                log += "stores opened"
                loop(held, observer)
            },
            network = NetworkConditions.Unmetered,
            cellularProofs = { false },
        )
        launch { tracker.onPush(wakeAll) }
        runCurrent()
        assertEquals(1, held.running, "the cycle is in flight")

        tracker.close()
        assertFalse(tracker.state.value.running)
        assertTrue(tracker.start())

        assertEquals(listOf("stores opened", "closed loop ended", "stores opened"), log)
        assertTrue(tracker.state.value.running)
    }

    @Test
    fun no_linked_account_stops_the_tracker() = runTest {
        var linked: SteamId? = accountA
        val tracker = P2pTracker(
            scope = backgroundScope,
            clock = clock,
            deviceIds = FakeDeviceIdStore(),
            linkedAccount = { linked },
            loopFor = { _, observer -> loop(FakeMarketplaceClient(), observer) },
            network = NetworkConditions.Unmetered,
            cellularProofs = { false },
        )
        assertTrue(tracker.start())

        linked = null
        assertFalse(tracker.start())

        assertEquals(TrackerState(), tracker.state.value)
    }

    @Test
    fun a_loop_that_does_not_start_answers_not_running_says_why_and_the_next_call_starts_again() = runTest {
        var fault: Throwable? = IllegalStateException("Keystore busy")
        val mp = FakeMarketplaceClient()
        val tracker = P2pTracker(
            scope = backgroundScope,
            clock = clock,
            deviceIds = FakeDeviceIdStore(),
            linkedAccount = { accountA },
            loopFor = { _, observer ->
                fault?.let { throw it }
                loop(mp, observer)
            },
            network = NetworkConditions.Unmetered,
            cellularProofs = { false },
            eventObserver = events,
        )
        val deal = DealId("d1")
        val notRunning = DealCallError(DealCallErrorKind.NOT_RUNNING)

        assertEquals(StatusUpdate(failure = UpdateFailure.NOT_RUNNING), tracker.updateStatus(deal))
        assertEquals(notRunning, tracker.startDelivery(deal).error)
        assertEquals(AcceptOutcome.Failed(notRunning), tracker.acceptDeal(deal))
        assertEquals(DealRead.Failed(notRunning), tracker.getDeal(deal))
        assertTrue(tracker.onPush(wakeAll), "the wake is still taken")
        assertFalse(tracker.start())
        assertEquals(6, events.events.count { it == LifecycleEvent.CycleFailed("IllegalStateException: Keystore busy") })
        assertFalse(tracker.state.value.running)
        assertTrue(mp.heartbeatsSent.isEmpty())

        fault = null
        assertTrue(tracker.onPush(wakeAll))
        assertEquals(1, mp.heartbeatsSent.size, "the next call starts the loop")
        assertTrue(tracker.state.value.running)
    }

    @Test
    fun an_account_read_that_throws_answers_not_running_says_why_and_the_next_start_reads_again() = runTest {
        var fault: Throwable? = IllegalStateException("token store locked")
        val tracker = P2pTracker(
            scope = backgroundScope,
            clock = clock,
            deviceIds = FakeDeviceIdStore(),
            linkedAccount = { fault?.let { throw it } ?: accountA },
            loopFor = { _, observer -> loop(FakeMarketplaceClient(), observer) },
            network = NetworkConditions.Unmetered,
            cellularProofs = { false },
            eventObserver = events,
        )

        assertFalse(tracker.start())
        assertEquals(StatusUpdate(failure = UpdateFailure.NOT_RUNNING), tracker.updateStatus(DealId("d1")))
        assertEquals(2, events.events.count { it == LifecycleEvent.CycleFailed("IllegalStateException: token store locked") })

        fault = null
        assertTrue(tracker.start())
        assertEquals(accountA, tracker.state.value.account)
    }

    @Test
    fun coming_on_screen_after_a_failed_start_starts_again_but_not_after_a_logout_a_stop_or_without_an_account() = runTest {
        var fault: Throwable? = IllegalStateException("Keystore busy")
        var linked: SteamId? = accountA
        val tracker = P2pTracker(
            scope = backgroundScope,
            clock = clock,
            deviceIds = FakeDeviceIdStore(),
            linkedAccount = { linked },
            loopFor = { _, observer ->
                fault?.let { throw it }
                loop(FakeMarketplaceClient(), observer)
            },
            network = NetworkConditions.Unmetered,
            cellularProofs = { false },
        )

        suspend fun failThenComeOnScreen(between: suspend () -> Unit): Boolean {
            fault = IllegalStateException("Keystore busy")
            tracker.setForeground(false)
            assertFalse(tracker.start())
            fault = null
            between()
            tracker.setForeground(true)
            return tracker.state.value.running
        }

        assertTrue(failThenComeOnScreen {}, "a failed start is tried again on screen")
        tracker.stop()
        assertFalse(failThenComeOnScreen { tracker.onLogout() }, "after a logout")
        assertFalse(failThenComeOnScreen { tracker.stop() }, "after a stop")
        val unlinked = failThenComeOnScreen {
            linked = null
            assertFalse(tracker.start())
        }
        assertFalse(unlinked, "without an account")
        linked = accountA
        tracker.setForeground(false)
        tracker.setForeground(true)
        assertFalse(tracker.state.value.running, "coming on screen with nothing failed starts nothing")
    }
}
