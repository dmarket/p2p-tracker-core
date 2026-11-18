package com.dmarket.p2p.tracker.runtime

import com.dmarket.p2p.tracker.adapter.steam.NoOpSteamSessionRefresher
import com.dmarket.p2p.tracker.credential.steam.SteamCredentialProvider
import com.dmarket.p2p.tracker.engine.ProofSkipReason
import com.dmarket.p2p.tracker.loop.LoopConfig
import com.dmarket.p2p.tracker.loop.TradeTrackerLoop
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.DeviceId
import com.dmarket.p2p.tracker.model.LifecycleEvent
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.RuntimeSurface
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.TrackerMode
import com.dmarket.p2p.tracker.model.WakeCause
import com.dmarket.p2p.tracker.model.WakeOutcome
import com.dmarket.p2p.tracker.model.WakeOutcomeKind
import com.dmarket.p2p.tracker.model.marketplace.ApnsTarget
import com.dmarket.p2p.tracker.model.marketplace.DeviceRegistration
import com.dmarket.p2p.tracker.model.marketplace.HeartbeatRequest
import com.dmarket.p2p.tracker.model.marketplace.HeartbeatResponse
import com.dmarket.p2p.tracker.model.marketplace.NetworkType
import com.dmarket.p2p.tracker.model.marketplace.PushProvider
import com.dmarket.p2p.tracker.model.marketplace.TrackedDeal
import com.dmarket.p2p.tracker.model.marketplace.WatchTarget
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.port.marketplace.DeviceRegistryClient
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceClient
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceServerErrorException
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceUnauthorizedException
import com.dmarket.p2p.tracker.port.marketplace.WakeAcknowledger
import com.dmarket.p2p.tracker.port.steam.SteamSessionRefresher
import com.dmarket.p2p.tracker.port.steam.SteamWebSessionState
import com.dmarket.p2p.tracker.support.FakeClock
import com.dmarket.p2p.tracker.support.FakeCredentialVault
import com.dmarket.p2p.tracker.support.FakeDeviceIdStore
import com.dmarket.p2p.tracker.support.FakeMarketplaceClient
import com.dmarket.p2p.tracker.support.FakeNotaryProver
import com.dmarket.p2p.tracker.support.FakeScheduler
import com.dmarket.p2p.tracker.support.FakeSteamReadClient
import com.dmarket.p2p.tracker.support.FakeSteamSessionRefresher
import com.dmarket.p2p.tracker.support.FakeSteamSessionScraper
import com.dmarket.p2p.tracker.support.RecordingEventObserver
import com.dmarket.p2p.tracker.support.fakeSteamCredential
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class P2pTrackerWakeTest {
    private val account = SteamId("76561198000000001")
    private val deal = DealId("d1")
    private val clock = FakeClock()
    private val events = RecordingEventObserver()
    private val acks = mutableListOf<Pair<String, WakeOutcome>>()
    private val acknowledger = WakeAcknowledger { _, wakeId, outcome -> acks += wakeId to outcome }

    private fun wake(reason: String = "prove_offer", deadline: String? = null) =
        """{"v":"1","type":"wake_deal","wakeId":"w-1","transactionId":"tx-1","reason":"$reason"""" +
            (deadline?.let { ""","deadline":"$it"""" } ?: "") + "}"

    private fun heartbeat(proofRequired: Boolean = false) = HeartbeatResponse(
        activeTracking = listOf(
            TrackedDeal(
                dealId = deal,
                steamOfferId = OfferId("offer-1"),
                watch = setOf(WatchTarget.GET_TRADE_OFFER),
                proofRequired = proofRequired,
            ),
        ),
        ttlSeconds = 86_400,
    )

    private class Setup(
        val marketplace: FakeMarketplaceClient,
        val notary: FakeNotaryProver = FakeNotaryProver(),
        val vault: SteamCredential? = fakeSteamCredential(),
        val refresher: SteamSessionRefresher = NoOpSteamSessionRefresher,
        val metered: Boolean = false,
        val cellularOptIn: Boolean = false,
        val heldMarketplace: MarketplaceClient = marketplace,
        val registry: DeviceRegistry? = null,
    )

    private fun TestScope.tracker(setup: Setup, cycleDeadline: Duration = P2pTracker.DEFAULT_CYCLE_DEADLINE) = P2pTracker(
        scope = backgroundScope,
        clock = clock,
        deviceIds = FakeDeviceIdStore(),
        linkedAccount = { account },
        loopFor = { _, observer ->
            TradeTrackerLoop(
                config = LoopConfig("1.0.0", RuntimeSurface.AndroidNative, TrackerMode.Background),
                marketplace = setup.heldMarketplace,
                steamReader = FakeSteamReadClient(initialOffers = mapOf(OfferId("offer-1") to 3)),
                credentials = SteamCredentialProvider(
                    vault = FakeCredentialVault(setup.vault),
                    scraper = FakeSteamSessionScraper(result = setup.vault),
                    clock = clock,
                    sessionRefresher = setup.refresher,
                ),
                scheduler = FakeScheduler(),
                clock = clock,
                deviceId = FakeDeviceIdStore(),
                notary = setup.notary,
                eventObserver = observer,
            )
        },
        network = { setup.metered },
        cellularProofs = { setup.cellularOptIn },
        acknowledger = acknowledger,
        eventObserver = events,
        cycleDeadline = cycleDeadline,
        deviceRegistry = setup.registry,
    )

    /** A heartbeat that never answers. */
    private class Stuck(fake: FakeMarketplaceClient) : MarketplaceClient by fake {
        var attempts = 0

        override suspend fun heartbeat(request: HeartbeatRequest): HeartbeatResponse {
            attempts += 1
            awaitCancellation()
        }
    }

    private fun receiptThen(outcome: WakeOutcome) = listOf("w-1" to WakeOutcome.DONE, "w-1" to outcome)

    @Test
    fun a_wake_past_its_deadline_is_dropped_without_a_sync_and_acked_once() = runTest {
        val setup = Setup(FakeMarketplaceClient(heartbeat()))
        val tracker = tracker(setup)

        val outcome = tracker.onPush(wake(deadline = (clock.now() - 1.seconds).toString()))

        assertEquals(WakeOutcome.failed(WakeCause.OTHER), outcome)
        assertEquals(0, setup.marketplace.heartbeatAttempts, "no sync")
        assertEquals(listOf("w-1" to WakeOutcome.failed(WakeCause.OTHER)), acks, "one ack, the final one")
        assertTrue(events.events.filterIsInstance<LifecycleEvent.WakeReceived>().single().expired)
    }

    @Test
    fun a_wake_inside_its_deadline_syncs_and_carries_its_id_and_the_device_state() = runTest {
        val setup = Setup(FakeMarketplaceClient(heartbeat()), metered = true, cellularOptIn = true)
        val tracker = tracker(setup)

        assertEquals(WakeOutcome.DONE, tracker.onPush(wake(deadline = (clock.now() + 1.minutes).toString())))

        val beat = setup.marketplace.heartbeatsSent.single()
        assertEquals("w-1", beat.wakeId)
        assertEquals(NetworkType.CELLULAR, beat.network)
        assertEquals(true, beat.backgroundProveAllowed)
        assertEquals(receiptThen(WakeOutcome.DONE), acks, "the final ack goes once")
    }

    @Test
    fun a_host_deadline_already_spent_is_a_budget_failure_without_a_sync() = runTest {
        val setup = Setup(FakeMarketplaceClient(heartbeat()))

        assertEquals(WakeOutcome.failed(WakeCause.BUDGET), tracker(setup).onPush(wake(), deadline = Duration.ZERO))

        assertEquals(0, setup.marketplace.heartbeatAttempts)
        assertEquals(listOf("w-1" to WakeOutcome.failed(WakeCause.BUDGET)), acks)
    }

    @Test
    fun the_host_deadline_cuts_the_cycle_short_of_its_own_and_fails_on_budget() = runTest {
        val stuck = Stuck(FakeMarketplaceClient(heartbeat()))
        val tracker = tracker(Setup(FakeMarketplaceClient(), heldMarketplace = stuck))

        val outcome = tracker.onPush(wake(), deadline = 10.seconds)

        assertEquals(WakeOutcome.failed(WakeCause.BUDGET), outcome)
        assertEquals(10_000, currentTime, "the host's 10 s, not the cycle's 3 min")
        assertEquals(1, stuck.attempts)
        assertEquals(receiptThen(WakeOutcome.failed(WakeCause.BUDGET)), acks)
    }

    @Test
    fun a_stopped_wake_acks_failed_on_budget() = runTest {
        val stuck = Stuck(FakeMarketplaceClient(heartbeat()))
        val tracker = tracker(Setup(FakeMarketplaceClient(), heldMarketplace = stuck))

        val job = launch { tracker.onPush(wake()) }
        runCurrent()
        job.cancelAndJoin()

        assertEquals(receiptThen(WakeOutcome.failed(WakeCause.BUDGET)), acks)
    }

    @Test
    fun a_proof_held_by_the_network_gate_defers_a_proof_wake_and_never_a_directive_wake() = runTest {
        val setup = Setup(FakeMarketplaceClient(heartbeat(proofRequired = true)), metered = true)
        val tracker = tracker(setup)

        assertEquals(WakeOutcome(WakeOutcomeKind.DEFERRED, WakeCause.CELLULAR_NOT_ALLOWED), tracker.onPush(wake("prove_offer")))
        assertEquals(WakeOutcome.DONE, tracker.onPush(wake("directive")))
        assertTrue(setup.notary.proven.isEmpty())
    }

    @Test
    fun a_failed_proof_is_a_notary_failure() = runTest {
        val notary = FakeNotaryProver().apply { proveThrows = IllegalStateException("notary connection dropped") }
        val tracker = tracker(Setup(FakeMarketplaceClient(heartbeat(proofRequired = true)), notary = notary))

        assertEquals(WakeOutcome.failed(WakeCause.NOTARY), tracker.onPush(wake()))
        assertEquals(receiptThen(WakeOutcome.failed(WakeCause.NOTARY)), acks)
    }

    @Test
    fun a_wake_without_time_for_a_proof_does_not_call_the_prover() = runTest {
        val setup = Setup(FakeMarketplaceClient(heartbeat(proofRequired = true)))
        val tracker = tracker(setup)

        assertEquals(WakeOutcome.failed(WakeCause.BUDGET), tracker.onPush(wake(), allowProof = false))

        assertTrue(setup.notary.proven.isEmpty(), "no proof minted")
        assertTrue(setup.marketplace.tradeStatusReports.isEmpty(), "the decisive report waits, unsent")
        assertTrue(events.events.contains(LifecycleEvent.ProofSuppressed(deal.value, "offer", ProofSkipReason.WAKE_BUDGET.message)))
        assertTrue(events.events.none { it is LifecycleEvent.TradeStatusClaimedUnproven }, "held, not unprovable")

        // The next wake that may prove does.
        assertEquals(WakeOutcome.DONE, tracker.onPush(wake()))
        assertEquals(1, setup.notary.proven.size)
    }

    @Test
    fun no_live_steam_session_fails_on_steam_session() = runTest {
        val setup = Setup(
            FakeMarketplaceClient(heartbeat()),
            vault = null,
            refresher = FakeSteamSessionRefresher(state = SteamWebSessionState.GONE),
        )

        assertEquals(WakeOutcome.failed(WakeCause.STEAM_SESSION), tracker(setup).onPush(wake("directive")))
    }

    @Test
    fun dmarket_unreachable_or_logged_out_fails_on_network_or_auth() = runTest {
        val down = FakeMarketplaceClient(heartbeat()).apply { heartbeatThrows = true }
        assertEquals(WakeOutcome.failed(WakeCause.NETWORK), tracker(Setup(down)).onPush(wake()))

        val loggedOut = FakeMarketplaceClient(heartbeat()).apply { heartbeatThrowable = MarketplaceUnauthorizedException() }
        assertEquals(WakeOutcome.failed(WakeCause.AUTH), tracker(Setup(loggedOut)).onPush(wake()))
    }

    @Test
    fun register_and_unregister_go_through_the_registry_with_this_installs_device_id() = runTest {
        val calls = mutableListOf<Pair<DeviceId, DeviceRegistration?>>()
        val client = object : DeviceRegistryClient {
            var refuse = false

            override suspend fun registerDevice(deviceId: DeviceId, registration: DeviceRegistration) {
                if (refuse) throw MarketplaceServerErrorException(429)
                calls += deviceId to registration
            }

            override suspend fun unregisterDevice(deviceId: DeviceId) {
                calls += deviceId to null
            }
        }
        val tracker = tracker(Setup(FakeMarketplaceClient(), registry = DeviceRegistry(client, "android", "2.1.0", steamWrites = true)))

        assertNull(tracker.registerDevice("fcm-token", PushProvider.FCM))
        assertNull(tracker.unregisterDevice())
        assertEquals(DealCallErrorKind.OTHER, tracker.registerDevice("fcm-token", PushProvider.FCM, ApnsTarget("sandbox", "t"))?.kind)
        client.refuse = true
        assertEquals(DealCallErrorKind.SERVER, tracker.registerDevice("fcm-token", PushProvider.FCM)?.kind)

        val device = tracker.deviceId()
        assertEquals(listOf(device, device), calls.map { it.first })
        assertEquals(listOf("wake_v1", "steam_writes"), calls[0].second?.capabilities)
        assertNull(calls[1].second, "the second call is the delete")
        assertEquals(
            DealCallErrorKind.UNAVAILABLE,
            tracker(Setup(FakeMarketplaceClient())).unregisterDevice()?.kind,
            "no registry: refused",
        )
    }
}
