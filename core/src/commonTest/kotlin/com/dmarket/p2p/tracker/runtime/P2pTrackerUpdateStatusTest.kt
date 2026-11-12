package com.dmarket.p2p.tracker.runtime

import com.dmarket.p2p.tracker.adapter.steam.NoOpSteamSessionRefresher
import com.dmarket.p2p.tracker.client.marketplace.RateLimitedException
import com.dmarket.p2p.tracker.client.steam.SteamRateLimitedException
import com.dmarket.p2p.tracker.credential.steam.SteamCredentialProvider
import com.dmarket.p2p.tracker.loop.LoopConfig
import com.dmarket.p2p.tracker.loop.TradeTrackerLoop
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.LifecycleEvent
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.RuntimeSurface
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.TrackerMode
import com.dmarket.p2p.tracker.model.marketplace.HeartbeatRequest
import com.dmarket.p2p.tracker.model.marketplace.HeartbeatResponse
import com.dmarket.p2p.tracker.model.marketplace.TrackedDeal
import com.dmarket.p2p.tracker.model.marketplace.WatchTarget
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.model.steam.SteamOfferSnapshot
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceClient
import com.dmarket.p2p.tracker.port.steam.SteamReadClient
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class P2pTrackerUpdateStatusTest {
    private val account = SteamId("76561198000000001")
    private val deal = DealId("d1")
    private val offer = OfferId("offer-1")
    private val clock = FakeClock()
    private val events = RecordingEventObserver()

    private fun heartbeat(proofRequired: Boolean = false) = HeartbeatResponse(
        activeTracking = listOf(
            TrackedDeal(dealId = deal, steamOfferId = offer, watch = setOf(WatchTarget.GET_TRADE_OFFER), proofRequired = proofRequired),
        ),
        ttlSeconds = 86_400,
    )

    private class Setup(
        val marketplace: FakeMarketplaceClient,
        val reader: SteamReadClient = FakeSteamReadClient(initialOffers = mapOf(OfferId("offer-1") to 3)),
        val notary: FakeNotaryProver = FakeNotaryProver(),
        val vault: SteamCredential? = fakeSteamCredential(),
        val refresher: SteamSessionRefresher = NoOpSteamSessionRefresher,
        var metered: Boolean = false,
        var cellularOptIn: Boolean = false,
        val heldMarketplace: MarketplaceClient = marketplace,
    )

    private fun TestScope.tracker(setup: Setup) = P2pTracker(
        scope = backgroundScope,
        clock = clock,
        deviceIds = FakeDeviceIdStore(),
        linkedAccount = { account },
        loopFor = { _, observer ->
            TradeTrackerLoop(
                config = LoopConfig("1.0.0", RuntimeSurface.AndroidNative, TrackerMode.Background),
                marketplace = setup.heldMarketplace,
                steamReader = setup.reader,
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
        eventObserver = events,
    )

    // ---- updateStatus --------------------------------------------------------------------------

    @Test
    fun a_successful_update_syncs_watches_and_reports_the_deal() = runTest {
        val setup = Setup(FakeMarketplaceClient(heartbeat()))
        val tracker = tracker(setup)

        val update = tracker.updateStatus(deal)

        assertEquals(1, setup.marketplace.heartbeatsSent.size, "the update syncs first")
        assertEquals(listOf(3), setup.marketplace.tradeStatusReports.map { it.steamStatusCode })
        assertEquals(StatusUpdate(checkedAt = clock.now(), code = 3, proofState = ProofState.NONE), update)
    }

    @Test
    fun the_code_is_what_the_run_read_even_when_its_report_was_refused() = runTest {
        val mp = FakeMarketplaceClient(heartbeat()).apply { tradeStatusAccepted = false }
        val tracker = tracker(Setup(mp, reader = FakeSteamReadClient(initialOffers = mapOf(offer to 2))))

        val update = tracker.updateStatus(deal)

        assertEquals(UpdateFailure.DMARKET, update.failure, "the report was refused")
        assertNull(tracker.state.value.deals[deal]?.lastCode, "nothing was reported")
        assertEquals(2, update.code, "but the run read the deal")
    }

    @Test
    fun a_dmarket_429_gives_its_retry_after_and_a_cooldown_that_sends_nothing() = runTest {
        val mp = FakeMarketplaceClient(heartbeat()).apply { heartbeatThrowable = RateLimitedException(45) }
        val setup = Setup(mp)
        val tracker = tracker(setup)

        val update = tracker.updateStatus(deal)

        assertEquals(UpdateFailure.RATE_LIMITED, update.failure)
        assertEquals(45.seconds, update.retryAfter)
        assertTrue(events.events.contains(LifecycleEvent.RateLimited("marketplace", 45)))
        assertEquals(clock.now() + 45.seconds, tracker.state.value.deals[deal]?.cooldownUntil)

        clock.advance(15.seconds)
        val attempts = mp.heartbeatAttempts
        val again = tracker.updateStatus(deal)
        assertEquals(UpdateFailure.RATE_LIMITED, again.failure)
        assertEquals(30.seconds, again.retryAfter, "the rest of the window")
        assertEquals(attempts, mp.heartbeatAttempts, "nothing goes out inside the window")
    }

    @Test
    fun a_steam_429_gives_its_retry_after() = runTest {
        val limited = object : SteamReadClient by FakeSteamReadClient() {
            override suspend fun offerSnapshots(credential: SteamCredential, offerIds: Set<OfferId>): Map<OfferId, SteamOfferSnapshot> =
                throw SteamRateLimitedException(null)
        }
        val tracker = tracker(Setup(FakeMarketplaceClient(heartbeat()), reader = limited))

        val update = tracker.updateStatus(deal)

        assertEquals(UpdateFailure.RATE_LIMITED, update.failure)
        assertEquals(P2pTracker.DEFAULT_RATE_LIMIT_WAIT_SECONDS.seconds, update.retryAfter, "no Retry-After: the default wait")
        assertTrue(events.events.contains(LifecycleEvent.RateLimited("steam", null)))
    }

    @Test
    fun an_expired_steam_session_is_a_session_failure() = runTest {
        val setup = Setup(
            FakeMarketplaceClient(heartbeat()),
            vault = null,
            refresher = FakeSteamSessionRefresher(state = SteamWebSessionState.GONE),
        )
        val tracker = tracker(setup)

        val update = tracker.updateStatus(deal)

        assertEquals(UpdateFailure.STEAM_SESSION, update.failure)
        assertTrue(setup.marketplace.tradeStatusReports.isEmpty())
    }

    @Test
    fun a_cancelled_update_frees_both_locks_for_the_next_one() = runTest {
        val fake = FakeMarketplaceClient(heartbeat())
        val entered = CompletableDeferred<Unit>()
        var hold = true
        val held = object : MarketplaceClient by fake {
            override suspend fun heartbeat(request: HeartbeatRequest): HeartbeatResponse {
                if (hold) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
                return fake.heartbeat(request)
            }
        }
        val tracker = tracker(Setup(fake, heldMarketplace = held))

        val first = async { tracker.updateStatus(deal) }
        entered.await()
        first.cancel()
        runCurrent()
        hold = false

        val update = tracker.updateStatus(deal)

        assertNull(update.failure, "the facade's lock and the loop's cycleMutex were both released")
        assertEquals(listOf(3), fake.tradeStatusReports.map { it.steamStatusCode })
    }

    @Test
    fun a_run_past_the_cycle_deadline_is_a_timeout() = runTest {
        val fake = FakeMarketplaceClient(heartbeat())
        val held = object : MarketplaceClient by fake {
            override suspend fun heartbeat(request: HeartbeatRequest): HeartbeatResponse = awaitCancellation()
        }
        val tracker = tracker(Setup(fake, heldMarketplace = held))

        val update = tracker.updateStatus(deal)

        assertEquals(UpdateFailure.TIMEOUT, update.failure)
    }

    @Test
    fun a_failed_proof_is_a_notary_failure() = runTest {
        val notary = FakeNotaryProver().apply { proveThrows = IllegalStateException("notary connection dropped") }
        val tracker = tracker(Setup(FakeMarketplaceClient(heartbeat(proofRequired = true)), notary = notary))

        assertEquals(UpdateFailure.NOTARY, tracker.updateStatus(deal).failure)
    }

    // ---- proof gate ----------------------------------------------------------------------------

    @Test
    fun on_a_metered_network_without_the_option_the_proof_is_deferred_and_nothing_is_reported() = runTest {
        val setup = Setup(FakeMarketplaceClient(heartbeat(proofRequired = true)), metered = true)
        val tracker = tracker(setup)

        val update = tracker.updateStatus(deal)

        assertEquals(ProofState.DEFERRED, update.proofState)
        assertNull(update.failure)
        assertTrue(setup.notary.proven.isEmpty(), "no proof on mobile data")
        assertTrue(setup.marketplace.proofsSubmitted.isEmpty())
        assertTrue(setup.marketplace.tradeStatusReports.isEmpty(), "the decisive report waits, unsent")
        assertTrue(events.events.contains(LifecycleEvent.ProofDeferred(deal.value, "offer")))
        assertTrue(events.events.none { it is LifecycleEvent.TradeStatusClaimedUnproven }, "deferred is not unprovable")

        // A later cycle on the same network still waits: the transition is never claimed unproven.
        tracker.onPush("""{"type":"wake_deal","dealId":"d1","reason":"buyer_accepted"}""")
        assertTrue(setup.marketplace.tradeStatusReports.isEmpty())
        assertEquals(ProofState.DEFERRED, tracker.state.value.deals[deal]?.proofState)
    }

    @Test
    fun the_user_option_lets_a_proof_run_on_a_metered_network() = runTest {
        val setup = Setup(FakeMarketplaceClient(heartbeat(proofRequired = true)), metered = true, cellularOptIn = true)

        val update = tracker(setup).updateStatus(deal)

        assertProvedAndReported(setup, update)
    }

    @Test
    fun the_callers_permission_lets_a_proof_run_on_a_metered_network_for_that_update_only() = runTest {
        val setup = Setup(FakeMarketplaceClient(heartbeat(proofRequired = true)), metered = true)

        val update = tracker(setup).updateStatus(deal, allowCellularProof = true)

        assertProvedAndReported(setup, update)
    }

    @Test
    fun an_unmetered_network_proves() = runTest {
        val setup = Setup(FakeMarketplaceClient(heartbeat(proofRequired = true)))

        val update = tracker(setup).updateStatus(deal)

        assertProvedAndReported(setup, update)
    }

    @Test
    fun a_deferred_proof_runs_once_the_network_is_unmetered() = runTest {
        val setup = Setup(FakeMarketplaceClient(heartbeat(proofRequired = true)), metered = true)
        val tracker = tracker(setup)
        tracker.updateStatus(deal)
        clock.advance(1.minutes)

        setup.metered = false
        events.events.clear()
        val update = tracker.updateStatus(deal)

        assertProvedAndReported(setup, update)
    }

    private fun assertProvedAndReported(setup: Setup, update: StatusUpdate) {
        assertEquals(1, setup.marketplace.proofsSubmitted.size, "the proof ran")
        assertEquals(listOf(3), setup.marketplace.tradeStatusReports.map { it.steamStatusCode })
        assertEquals(ProofState.NONE, update.proofState)
        assertNull(update.failure)
        assertTrue(events.events.none { it is LifecycleEvent.ProofDeferred })
    }
}
