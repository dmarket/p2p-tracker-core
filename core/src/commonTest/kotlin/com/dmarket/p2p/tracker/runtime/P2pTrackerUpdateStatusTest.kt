package com.dmarket.p2p.tracker.runtime

import com.dmarket.p2p.tracker.adapter.steam.NoOpSteamSessionRefresher
import com.dmarket.p2p.tracker.client.marketplace.RateLimitedException
import com.dmarket.p2p.tracker.client.steam.SteamRateLimitedException
import com.dmarket.p2p.tracker.config.NotaryConfig
import com.dmarket.p2p.tracker.credential.steam.SteamCredentialProvider
import com.dmarket.p2p.tracker.loop.LoopConfig
import com.dmarket.p2p.tracker.loop.TradeTrackerLoop
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.LifecycleEvent
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.RuntimeSurface
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.TrackerMode
import com.dmarket.p2p.tracker.model.WakeOutcome
import com.dmarket.p2p.tracker.model.marketplace.DealRole
import com.dmarket.p2p.tracker.model.marketplace.HeartbeatRequest
import com.dmarket.p2p.tracker.model.marketplace.HeartbeatResponse
import com.dmarket.p2p.tracker.model.marketplace.ProofSubmission
import com.dmarket.p2p.tracker.model.marketplace.TrackedDeal
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusResult
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusSource
import com.dmarket.p2p.tracker.model.marketplace.WatchTarget
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.model.steam.SteamOfferSnapshot
import com.dmarket.p2p.tracker.notary.HostedProofException
import com.dmarket.p2p.tracker.notary.KillCause
import com.dmarket.p2p.tracker.notary.NativeIssuance
import com.dmarket.p2p.tracker.notary.NativeIssuanceListener
import com.dmarket.p2p.tracker.notary.NativeIssuanceOutcome
import com.dmarket.p2p.tracker.notary.NativeNotaryProver
import com.dmarket.p2p.tracker.notary.NativeProverEngine
import com.dmarket.p2p.tracker.notary.ProofFailure
import com.dmarket.p2p.tracker.notary.ProvenReadBinding
import com.dmarket.p2p.tracker.port.marketplace.DealRefresh
import com.dmarket.p2p.tracker.port.marketplace.DealRefreshClient
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceClient
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceServerErrorException
import com.dmarket.p2p.tracker.port.notary.NotaryProver
import com.dmarket.p2p.tracker.port.notary.NotaryTokenProvider
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
import com.dmarket.p2p.tracker.support.fakeDeal
import com.dmarket.p2p.tracker.support.fakeSteamCredential
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class P2pTrackerUpdateStatusTest {
    private val account = SteamId("76561198000000001")
    private val deal = DealId("d1")
    private val offer = OfferId("offer-1")
    private val clock = FakeClock()
    private val events = RecordingEventObserver()
    private var linked = account

    private fun heartbeat(proofRequired: Boolean = false, role: DealRole = DealRole.UNKNOWN) = HeartbeatResponse(
        activeTracking = listOf(
            TrackedDeal(
                dealId = deal,
                steamOfferId = offer,
                watch = setOf(WatchTarget.GET_TRADE_OFFER),
                proofRequired = proofRequired,
                role = role,
            ),
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
        val refresh: DealRefreshClient? = null,
        val prover: NotaryProver = notary,
    )

    /** The refresh route: [answer] per call, every call counted. */
    private class FakeRefresh(var answer: suspend (DealId) -> DealRefresh = { DealRefresh(tracked = true) }) : DealRefreshClient {
        val calls = mutableListOf<DealId>()

        override suspend fun refreshDeal(id: DealId): DealRefresh {
            calls += id
            return answer(id)
        }
    }

    /** Every draw is 0: no jitter. */
    private object Lowest : Random() {
        override fun nextBits(bitCount: Int): Int = 0
    }

    /** Every draw is just under 1: the most jitter. */
    private object Highest : Random() {
        override fun nextBits(bitCount: Int): Int = -1 ushr (32 - bitCount)
    }

    private fun TestScope.tracker(setup: Setup, random: Random = Lowest) = P2pTracker(
        scope = backgroundScope,
        clock = clock,
        deviceIds = FakeDeviceIdStore(),
        linkedAccount = { linked },
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
                notary = setup.prover,
                eventObserver = observer,
            )
        },
        network = { setup.metered },
        cellularProofs = { setup.cellularOptIn },
        eventObserver = events,
        random = random,
        dealRefresh = setup.refresh,
    )

    // ---- updateStatus --------------------------------------------------------------------------

    @Test
    fun a_successful_update_syncs_watches_and_reports_the_deal() = runTest {
        val setup = Setup(FakeMarketplaceClient(heartbeat()))
        val tracker = tracker(setup)

        val update = tracker.updateStatus(deal)

        assertEquals(1, setup.marketplace.heartbeatsSent.size, "the update syncs first")
        assertEquals(listOf(3), setup.marketplace.tradeStatusReports.map { it.steamStatusCode })
        assertEquals(StatusUpdate(checkedAt = clock.now(), code = 3, proofState = ProofState.NONE, retryLeft = 1), update)
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
    fun a_429_in_any_cycle_holds_every_deals_update() = runTest {
        val mp = FakeMarketplaceClient(heartbeat()).apply { heartbeatThrowable = RateLimitedException(45) }
        val tracker = tracker(Setup(mp))

        tracker.onPush("""{"type":"wake_all","reason":"new_deal"}""")
        clock.advance(15.seconds)
        val attempts = mp.heartbeatAttempts

        for (id in listOf(deal, DealId("d2"))) {
            val update = tracker.updateStatus(id)
            assertEquals(UpdateFailure.RATE_LIMITED, update.failure, "$id")
            assertEquals(30.seconds, update.retryAfter, "$id: the rest of the push's window")
        }
        assertEquals(attempts, mp.heartbeatAttempts, "nothing goes out inside the window")
    }

    @Test
    fun a_steam_429_in_a_cycle_holds_the_update_for_its_own_wait() = runTest {
        val limited = object : SteamReadClient by FakeSteamReadClient() {
            override suspend fun offerSnapshots(credential: SteamCredential, offerIds: Set<OfferId>): Map<OfferId, SteamOfferSnapshot> =
                throw SteamRateLimitedException(120)
        }
        val mp = FakeMarketplaceClient(heartbeat())
        val tracker = tracker(Setup(mp, reader = limited))

        tracker.onPush("""{"type":"wake_all","reason":"new_deal"}""")
        val attempts = mp.heartbeatAttempts

        assertEquals(120.seconds, tracker.updateStatus(deal).retryAfter, "Steam's own wait, not DMarket's")
        assertEquals(attempts, mp.heartbeatAttempts)
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
        assertEquals(RateLimitBackoff.BASE, update.retryAfter, "no Retry-After: the ladder's first step")
        assertTrue(events.events.contains(LifecycleEvent.RateLimited("steam", null)))
    }

    @Test
    fun dmarket_429s_in_a_row_without_retry_after_climb_the_ladder() = runTest {
        val mp = FakeMarketplaceClient(heartbeat()).apply { heartbeatThrowable = RateLimitedException(null) }
        val tracker = tracker(Setup(mp))

        assertEquals(30.seconds, tracker.updateStatus(deal).retryAfter, "the first 429")
        clock.advance(30.seconds)
        assertEquals(60.seconds, tracker.updateStatus(deal).retryAfter, "the second in a row doubles")
        assertEquals(clock.now() + 60.seconds, tracker.state.value.deals[deal]?.cooldownUntil)
    }

    @Test
    fun a_retry_after_longer_than_the_ladder_wins_and_a_shorter_one_does_not() = runTest {
        val mp = FakeMarketplaceClient(heartbeat()).apply { heartbeatThrowable = RateLimitedException(300) }
        val tracker = tracker(Setup(mp))
        assertEquals(300.seconds, tracker.updateStatus(deal).retryAfter)

        clock.advance(300.seconds)
        mp.heartbeatThrowable = RateLimitedException(1)
        assertEquals(60.seconds, tracker.updateStatus(deal).retryAfter, "a 1 s Retry-After does not cut the second step")
    }

    @Test
    fun an_answered_heartbeat_ends_the_run_of_429s() = runTest {
        val mp = FakeMarketplaceClient(heartbeat()).apply { heartbeatThrowable = RateLimitedException(null) }
        val tracker = tracker(Setup(mp))
        tracker.updateStatus(deal)
        clock.advance(30.seconds)
        tracker.updateStatus(deal)
        clock.advance(60.seconds)

        mp.heartbeatThrowable = null
        assertNull(tracker.updateStatus(deal).failure)
        clock.advance(1.minutes)

        mp.heartbeatThrowable = RateLimitedException(null)
        assertEquals(30.seconds, tracker.updateStatus(deal).retryAfter, "the ladder starts over")
    }

    private val wakeAll = """{"type":"wake_all","reason":"new_deal"}"""

    @Test
    fun an_answer_from_dmarket_ends_the_backoffs_wait_but_not_the_servers_retry_after() = runTest {
        val mp = FakeMarketplaceClient(heartbeat()).apply { heartbeatThrowable = RateLimitedException(300) }
        val tracker = tracker(Setup(mp))
        tracker.updateStatus(deal)
        mp.heartbeatThrowable = null
        tracker.onPush(wakeAll)
        val attempts = mp.heartbeatAttempts

        clock.advance(299.seconds)
        val held = tracker.updateStatus(deal)
        assertEquals(UpdateFailure.RATE_LIMITED, held.failure, "the server asked for 300 s")
        assertEquals(1.seconds, held.retryAfter)
        assertEquals(attempts, mp.heartbeatAttempts)

        clock.advance(1.seconds)
        assertNull(tracker.updateStatus(deal).failure)
        assertEquals(attempts + 1, mp.heartbeatAttempts)
    }

    @Test
    fun an_answer_from_dmarket_ends_a_wait_the_server_did_not_ask_for() = runTest {
        val mp = FakeMarketplaceClient(heartbeat()).apply { heartbeatThrowable = RateLimitedException(null) }
        val tracker = tracker(Setup(mp))
        assertEquals(30.seconds, tracker.updateStatus(deal).retryAfter)
        mp.heartbeatThrowable = null
        tracker.onPush(wakeAll)
        assertNull(tracker.state.value.deals[deal]?.cooldownUntil, "the deal's cooldown ends with its surface's")
        val attempts = mp.heartbeatAttempts

        assertNull(tracker.updateStatus(deal).failure)
        assertEquals(attempts + 1, mp.heartbeatAttempts, "the update goes out at once")
    }

    @Test
    fun steam_and_dmarket_answers_end_only_their_own_waits() = runTest {
        var steamLimited = true
        val reader = object : SteamReadClient by FakeSteamReadClient(initialOffers = mapOf(offer to 3)) {
            override suspend fun offerSnapshots(credential: SteamCredential, offerIds: Set<OfferId>): Map<OfferId, SteamOfferSnapshot> {
                if (steamLimited) throw SteamRateLimitedException(null)
                return FakeSteamReadClient(initialOffers = mapOf(offer to 3)).offerSnapshots(credential, offerIds)
            }
        }
        val mp = FakeMarketplaceClient(heartbeat()).apply { heartbeatThrowable = RateLimitedException(300) }
        val tracker = tracker(Setup(mp, reader = reader))
        tracker.updateStatus(deal) // DMarket asks for 300 s
        mp.heartbeatThrowable = null
        clock.advance(300.seconds)

        tracker.onPush(wakeAll) // DMarket answers, Steam refuses
        assertEquals(30.seconds, tracker.updateStatus(deal).retryAfter, "Steam's first step: DMarket's answer leaves it")

        steamLimited = false
        mp.heartbeatThrowable = RateLimitedException(120)
        tracker.onPush(wakeAll) // DMarket asks for 120 s, so the cycle reads no Steam
        mp.heartbeatThrowable = null
        steamLimited = true
        clock.advance(120.seconds)
        tracker.onPush(wakeAll) // DMarket answers, Steam refuses again: the second step of its own run
        assertEquals(60.seconds, tracker.updateStatus(deal).retryAfter, "DMarket's 429 did not end Steam's run")

        steamLimited = false
        tracker.onPush(wakeAll) // both answer
        val attempts = mp.heartbeatAttempts
        assertNull(tracker.updateStatus(deal).failure, "Steam's answer ends its wait at once")
        assertEquals(attempts + 1, mp.heartbeatAttempts)
    }

    @Test
    fun a_logout_or_another_account_starts_the_dmarket_429s_over_and_keeps_steams() = runTest {
        val mp = FakeMarketplaceClient(heartbeat()).apply { heartbeatThrowable = RateLimitedException(300) }
        val tracker = tracker(Setup(mp))
        tracker.updateStatus(deal)
        tracker.onLogout()

        mp.heartbeatThrowable = RateLimitedException(null)
        assertEquals(30.seconds, tracker.updateStatus(deal).retryAfter, "after a logout: no cooldown, the ladder's first step")

        linked = SteamId("76561198000000002")
        tracker.start()
        val sent = mp.heartbeatAttempts
        assertEquals(30.seconds, tracker.updateStatus(deal).retryAfter, "another account: no cooldown, the ladder's first step")
        assertEquals(sent + 1, mp.heartbeatAttempts)

        mp.heartbeatThrowable = null
        val reader = object : SteamReadClient by FakeSteamReadClient() {
            override suspend fun offerSnapshots(credential: SteamCredential, offerIds: Set<OfferId>): Map<OfferId, SteamOfferSnapshot> =
                throw SteamRateLimitedException(120)
        }
        val steam = tracker(Setup(FakeMarketplaceClient(heartbeat()), reader = reader))
        steam.updateStatus(deal)
        steam.onLogout()
        assertEquals(UpdateFailure.RATE_LIMITED, steam.updateStatus(deal).failure, "Steam's 429 is the address's, not the user's")
    }

    @Test
    fun jitter_adds_up_to_a_quarter_of_the_wait() = runTest {
        val lowest = FakeMarketplaceClient(heartbeat()).apply { heartbeatThrowable = RateLimitedException(null) }
        assertEquals(30.seconds, tracker(Setup(lowest), Lowest).updateStatus(deal).retryAfter)

        val highest = FakeMarketplaceClient(heartbeat()).apply { heartbeatThrowable = RateLimitedException(null) }
        val most = tracker(Setup(highest), Highest).updateStatus(deal).retryAfter!!
        assertTrue(most > 37.seconds && most <= 37.5.seconds, "at most 30 s + 25 %: $most")
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

    // ---- the buyer's report without a proof (DMA-664) ----------------------------------------------

    /** A buyer's tracker on a proof-required deal whose report DMarket refuses for want of a proof. */
    private fun buyerRefused() = Setup(
        FakeMarketplaceClient(heartbeat(proofRequired = true, role = DealRole.BUYER)).apply {
            tradeStatusResults = { reports ->
                reports.map { TradeStatusResult(dealId = it.dealId, accepted = false, reason = "P2P_PROOF_REQUIRED", source = it.source) }
            }
        },
    )

    @Test
    fun a_buyers_report_refused_for_want_of_a_proof_is_no_update_failure() = runTest {
        // The seller's tracker proves the transition; the buyer's report goes once without a proof, and its refusal
        // is nothing the buyer can act on.
        val setup = buyerRefused()

        val update = tracker(setup).updateStatus(deal)

        assertNull(update.failure)
        assertEquals(ProofState.NONE, update.proofState)
        assertTrue(setup.notary.proven.isEmpty(), "no notary session on the buyer's side")
        assertEquals(1, setup.marketplace.tradeStatusReports.size)
    }

    @Test
    fun a_buyers_report_refused_for_want_of_a_proof_does_not_fail_the_wake() = runTest {
        val setup = buyerRefused()

        val outcome = tracker(setup).onPush("""{"v":"1","type":"wake_all","wakeId":"w-1","reason":"frozen"}""")

        assertEquals(WakeOutcome.DONE, outcome)
        assertEquals(1, setup.marketplace.tradeStatusReports.size)
    }

    @Test
    fun a_buyers_later_runs_hold_the_report_without_a_pending_proof() = runTest {
        // After the one report, the spent claim withholds it; the deal shows no proof of its own pending.
        val setup = buyerRefused()
        val tracker = tracker(setup)
        tracker.updateStatus(deal, retries = 0)
        clock.advance(10.minutes)

        val update = tracker.updateStatus(deal)

        assertNull(update.failure)
        assertEquals(ProofState.NONE, update.proofState)
        assertEquals(1, setup.marketplace.tradeStatusReports.size)
    }

    @Test
    fun a_buyers_report_that_did_not_get_through_is_still_a_dmarket_failure() = runTest {
        // Only the refusal for want of a proof is the seller's business: a 5xx or a dropped call is a report DMarket
        // never heard, as on any other deal.
        val marketplace = FakeMarketplaceClient(heartbeat(proofRequired = true, role = DealRole.BUYER))
        marketplace.reportTradeStatusThrows = true
        val setup = Setup(marketplace)

        val update = tracker(setup).updateStatus(deal)

        assertEquals(UpdateFailure.DMARKET, update.failure)
        assertEquals(1, setup.marketplace.tradeStatusReports.size)
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
    fun start_delivery_runs_behind_the_proof_gate() = runTest {
        val setup = Setup(FakeMarketplaceClient(heartbeat(proofRequired = true)), metered = true)

        tracker(setup).startDelivery(deal) // a fresh loop heartbeats and watches its deals before the create

        assertTrue(setup.marketplace.heartbeatsSent.isNotEmpty(), "the create's own sync ran")
        assertTrue(setup.notary.proven.isEmpty(), "no proof on mobile data")
        assertTrue(events.events.contains(LifecycleEvent.ProofDeferred(deal.value, "offer")))
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

    // ---- the refresh permit (DMA-963, DMA-1016) -------------------------------------------------

    private fun window() = clock.now() + 10.minutes

    @Test
    fun a_permit_with_the_watch_entry_runs_the_cycle_and_gives_its_window() = runTest {
        val until = window()
        val refresh = FakeRefresh { DealRefresh(tracked = true, nextAllowedAt = until) }
        val setup = Setup(FakeMarketplaceClient(heartbeat()), refresh = refresh)

        val update = tracker(setup).updateStatus(deal)

        assertEquals(listOf(deal), refresh.calls)
        assertEquals(1, setup.marketplace.heartbeatsSent.size, "the cycle ran")
        assertNull(update.failure)
        assertEquals(until, update.nextAllowedAt)
        assertEquals(1, update.retryLeft)
    }

    @Test
    fun a_permit_without_a_window_is_not_kept_and_the_next_run_asks_again() = runTest {
        val refresh = FakeRefresh { DealRefresh(tracked = true) }
        val tracker = tracker(Setup(FakeMarketplaceClient(heartbeat()), refresh = refresh))

        val first = tracker.updateStatus(deal, retries = 2)
        tracker.updateStatus(deal, retries = 2)

        assertEquals(2, refresh.calls.size)
        assertNull(first.nextAllowedAt)
        assertEquals(2, first.retryLeft, "no permit holds: the retries asked for")
    }

    @Test
    fun nothing_to_refresh_still_runs_the_cycle_uncounted() = runTest {
        for (answer in listOf<suspend (DealId) -> DealRefresh>(
            { DealRefresh(tracked = false) },
            { throw MarketplaceServerErrorException(404, "P2P_DEAL_NOT_FOUND") },
        )) {
            val refresh = FakeRefresh(answer)
            val setup = Setup(FakeMarketplaceClient(heartbeat()), refresh = refresh)
            val tracker = tracker(setup)

            val update = tracker.updateStatus(deal)
            tracker.updateStatus(deal)

            assertNull(update.failure, "not an error")
            assertNull(update.nextAllowedAt)
            assertEquals(2, setup.marketplace.heartbeatsSent.size, "both runs synced")
            assertEquals(2, refresh.calls.size, "nothing was kept to run under")
        }
    }

    @Test
    fun a_503_charges_nothing_runs_nothing_and_may_be_asked_again_at_once() = runTest {
        val refresh = FakeRefresh { throw MarketplaceServerErrorException(503, "P2P_UNAVAILABLE") }
        val setup = Setup(FakeMarketplaceClient(heartbeat()), refresh = refresh)
        val tracker = tracker(setup)

        val update = tracker.updateStatus(deal)

        assertEquals(UpdateFailure.DMARKET, update.failure)
        assertEquals(1, update.retryLeft)
        assertNull(update.retryAfter)
        assertTrue(setup.marketplace.heartbeatsSent.isEmpty(), "no cycle without the permit")

        refresh.answer = { DealRefresh(tracked = true, nextAllowedAt = window()) }
        assertNull(tracker.updateStatus(deal).failure, "asked again at once")
        assertEquals(2, refresh.calls.size)
    }

    @Test
    fun the_refresh_429_waits_exactly_its_retry_after_for_that_deal_only() = runTest {
        val refresh = FakeRefresh { id ->
            if (id == deal) throw RateLimitedException(412, "P2P_REFRESH_RATE_LIMITED") else DealRefresh(tracked = true)
        }
        val setup = Setup(FakeMarketplaceClient(heartbeat()), refresh = refresh)
        val tracker = tracker(setup, random = Highest)

        val update = tracker.updateStatus(deal)

        assertEquals(UpdateFailure.RATE_LIMITED, update.failure)
        assertEquals(412.seconds, update.retryAfter, "no backoff, no jitter")
        assertEquals(clock.now() + 412.seconds, update.nextAllowedAt)
        assertEquals(0, update.retryLeft)
        assertTrue(setup.marketplace.heartbeatsSent.isEmpty())

        clock.advance(12.seconds)
        val again = tracker.updateStatus(deal)
        assertEquals(400.seconds, again.retryAfter, "the rest of the wait, asked of no one")
        assertEquals(1, refresh.calls.size)

        val other = tracker.updateStatus(DealId("d2"))
        assertNull(other.failure, "another deal is not held")
        assertEquals(1, setup.marketplace.heartbeatsSent.size)
    }

    @Test
    fun two_updates_at_once_take_one_permit_and_a_late_refresh_429_spends_none_of_it() = runTest {
        val answered = CompletableDeferred<Unit>()
        var asked = 0
        val refresh = FakeRefresh {
            val nth = ++asked
            answered.await()
            if (nth ==
                1
            ) {
                DealRefresh(tracked = true, nextAllowedAt = window())
            } else {
                throw RateLimitedException(60, "P2P_REFRESH_RATE_LIMITED")
            }
        }
        val tracker = tracker(Setup(FakeMarketplaceClient(heartbeat()), refresh = refresh))

        val first = async { tracker.updateStatus(deal, retries = 1) }
        val second = async { tracker.updateStatus(deal, retries = 1) }
        runCurrent()
        answered.complete(Unit)

        assertNull(first.await().failure)
        assertNull(second.await().failure, "the second runs under the first one's permit")
        assertNull(tracker.updateStatus(deal, retries = 1).failure, "no session was spent: the permit is whole")
        assertEquals(1, refresh.calls.size)
    }

    @Test
    fun kongs_429_takes_the_dmarket_backoff_for_every_deal() = runTest {
        val refresh = FakeRefresh { throw RateLimitedException(5) }
        val setup = Setup(FakeMarketplaceClient(heartbeat()), refresh = refresh)
        val tracker = tracker(setup)

        val update = tracker.updateStatus(deal)
        val other = tracker.updateStatus(DealId("d2"))

        assertEquals(UpdateFailure.RATE_LIMITED, update.failure)
        assertEquals(30.seconds, update.retryAfter, "the backoff's first step")
        assertNull(update.nextAllowedAt)
        assertEquals(UpdateFailure.RATE_LIMITED, other.failure, "the DMarket cooldown holds every deal")
        assertEquals(1, refresh.calls.size)
    }

    @Test
    fun a_permit_allows_one_session_plus_its_retries_then_waits_for_its_window() = runTest {
        for (retries in 0..2) {
            val until = window()
            val refresh = FakeRefresh { DealRefresh(tracked = true, nextAllowedAt = until) }
            val notary = FakeNotaryProver().apply {
                proveThrows = IllegalStateException("notary connection dropped")
                onProve = { clock.advance(20.seconds) } // long enough to count for the prover's breaker
            }
            val tracker = tracker(Setup(FakeMarketplaceClient(heartbeat(proofRequired = true)), notary = notary, refresh = refresh))

            for (session in 1..retries + 1) {
                val update = tracker.updateStatus(deal, retries = retries)
                assertEquals(UpdateFailure.NOTARY, update.failure, "retries=$retries, session $session")
                assertEquals(retries + 1 - session, update.retryLeft, "retries=$retries, session $session")
                assertEquals(until, update.nextAllowedAt)
            }
            val spent = tracker.updateStatus(deal, retries = retries)

            assertEquals(UpdateFailure.RATE_LIMITED, spent.failure, "retries=$retries")
            assertEquals(until - clock.now(), spent.retryAfter)
            assertEquals(0, spent.retryLeft)
            assertEquals(1, refresh.calls.size, "retries=$retries: the Retries go without a refresh")
            assertEquals(retries + 1, notary.proven.size, "retries=$retries")
        }
    }

    @Test
    fun negative_retries_are_none() = runTest {
        val refresh = FakeRefresh { DealRefresh(tracked = true, nextAllowedAt = window()) }
        val notary = FakeNotaryProver().apply { proveThrows = IllegalStateException("dropped") }
        val tracker = tracker(Setup(FakeMarketplaceClient(heartbeat(proofRequired = true)), notary = notary, refresh = refresh))

        assertEquals(0, tracker.updateStatus(deal, retries = -3).retryLeft)
        assertEquals(UpdateFailure.RATE_LIMITED, tracker.updateStatus(deal, retries = -3).failure)
    }

    @Test
    fun a_run_without_a_notary_session_leaves_the_permit_whole() = runTest {
        val refresh = FakeRefresh { DealRefresh(tracked = true, nextAllowedAt = window()) }
        val tracker = tracker(Setup(FakeMarketplaceClient(heartbeat()), refresh = refresh))

        tracker.updateStatus(deal, retries = 0)
        val second = tracker.updateStatus(deal, retries = 0)

        assertNull(second.failure, "the first session is still unused")
        assertEquals(1, refresh.calls.size, "and no second refresh, which would answer 429")
    }

    @Test
    fun only_an_updates_sessions_count_against_the_permit() = runTest {
        val refresh = FakeRefresh { DealRefresh(tracked = true, nextAllowedAt = window()) }
        val notary = FakeNotaryProver().apply { proveThrows = IllegalStateException("dropped") }
        val setup = Setup(FakeMarketplaceClient(heartbeat(proofRequired = true)), notary = notary, refresh = refresh, metered = true)
        val tracker = tracker(setup)
        tracker.updateStatus(deal, retries = 0) // the permit, its session unused: the proof waits for Wi-Fi

        setup.metered = false
        tracker.acceptDeal(deal) // its sync proves, outside any update
        val update = tracker.updateStatus(deal, retries = 0)

        assertEquals(2, notary.proven.size, "the accept's sync and the update each ran one")
        assertEquals(UpdateFailure.NOTARY, update.failure, "the update still had its session")
        assertEquals(1, refresh.calls.size)
    }

    /**
     * The native prover over an engine whose first issuance reaches the notary's `101` when [upgraded] (a stage comes
     * only then) and hangs; every later one fails at the notary, a session all the same.
     */
    private fun nativeProverHangingOnce(upgraded: Boolean = true): NativeNotaryProver {
        var issued = 0
        val engine = object : NativeProverEngine {
            override fun unavailableReason(): String? = null

            override suspend fun issue(issuance: NativeIssuance, listener: NativeIssuanceListener): NativeIssuanceOutcome {
                if (++issued > 1) return NativeIssuanceOutcome(status = 1, diagnostic = "notary connection dropped", closeCode = 1011)
                if (upgraded) listener.onStage(2, "MPC_SETUP", 0.1f)
                awaitCancellation()
            }
        }
        return NativeNotaryProver(NotaryConfig(), NotaryTokenProvider { "notary-token" }, engine, clock)
    }

    @Test
    fun a_cancelled_session_counts_once_and_only_after_the_notarys_101() = runTest {
        for (upgraded in listOf(false, true)) {
            for (retries in 0..1) {
                val refresh = FakeRefresh { DealRefresh(tracked = true, nextAllowedAt = window()) }
                val prover = nativeProverHangingOnce(upgraded)
                val tracker = tracker(Setup(FakeMarketplaceClient(heartbeat(proofRequired = true)), prover = prover, refresh = refresh))
                val run = launch { tracker.updateStatus(deal, retries = retries) }
                runCurrent()
                assertEquals(setOf(deal), prover.progress.value.keys, "upgraded=$upgraded: the proof runs")

                run.cancel() // Cancel on T-14: the app cancels its collector
                runCurrent()
                val next = tracker.updateStatus(deal, retries = retries)

                val spent = upgraded && retries == 0
                assertEquals(
                    if (spent) UpdateFailure.RATE_LIMITED else UpdateFailure.NOTARY,
                    next.failure,
                    "upgraded=$upgraded, retries=$retries: a Cancel after the 101 spends one session, before it none",
                )
                assertEquals(1, refresh.calls.size)
            }
        }
    }

    @Test
    fun a_session_a_stop_a_logout_or_an_account_change_cut_does_not_count() = runTest {
        // Only the stop tells: a logout and an account change also drop the permit.
        for (cut in listOf("stop", "logout", "account change")) {
            val prover = nativeProverHangingOnce()
            val refresh = FakeRefresh { DealRefresh(tracked = true, nextAllowedAt = window()) }
            val tracker = tracker(Setup(FakeMarketplaceClient(heartbeat(proofRequired = true)), prover = prover, refresh = refresh))
            val run = launch { tracker.updateStatus(deal, retries = 0) }
            runCurrent()
            assertEquals(setOf(deal), prover.progress.value.keys, "$cut: the session is past the 101")

            when (cut) {
                "stop" -> tracker.stop()
                "logout" -> tracker.onLogout()
                else -> {
                    linked = SteamId("76561198000000002")
                    tracker.start()
                    linked = account
                }
            }
            run.join()
            val next = tracker.updateStatus(deal, retries = 0)

            assertEquals(UpdateFailure.NOTARY, next.failure, "$cut: the permit was whole, so the session ran")
        }
    }

    @Test
    fun a_granted_window_already_past_by_the_devices_clock_still_runs_the_update_once() = runTest {
        val refresh = FakeRefresh {
            val until = clock.now() + 1.minutes
            clock.advance(2.minutes) // this device's clock is past the window the server granted
            DealRefresh(tracked = true, nextAllowedAt = until)
        }
        val tracker = tracker(Setup(FakeMarketplaceClient(heartbeat()), refresh = refresh))

        val update = tracker.updateStatus(deal, retries = 0)

        assertNull(update.failure, "the cycle ran")
        assertEquals(1, refresh.calls.size)
    }

    @Test
    fun two_updates_at_once_spend_no_more_sessions_than_the_permit_allows() = runTest {
        val refresh = FakeRefresh { DealRefresh(tracked = true, nextAllowedAt = window()) }
        val notary = FakeNotaryProver().apply { proveThrows = IllegalStateException("notary connection dropped") }
        val tracker = tracker(Setup(FakeMarketplaceClient(heartbeat(proofRequired = true)), notary = notary, refresh = refresh))

        val first = async { tracker.updateStatus(deal, retries = 0) }
        val second = async { tracker.updateStatus(deal, retries = 0) }

        assertEquals(UpdateFailure.NOTARY, first.await().failure)
        val late = second.await()
        assertEquals(UpdateFailure.RATE_LIMITED, late.failure, "the first one spent the only session")
        assertEquals(window() - clock.now(), late.retryAfter)
        assertEquals(1, notary.proven.size)
        assertEquals(1, refresh.calls.size)
    }

    @Test
    fun an_update_whose_permit_lapsed_while_it_waited_for_the_lock_runs_under_a_new_one_or_not_at_all() = runTest {
        for (ownRefresh in listOf(false, true)) {
            var proves = 0
            val hangsOnce = object : NotaryProver by FakeNotaryProver() {
                override suspend fun proveTransition(
                    binding: ProvenReadBinding,
                    source: TradeStatusSource,
                    credential: SteamCredential,
                ): ProofSubmission {
                    if (++proves > 1) throw IllegalStateException("notary connection dropped")
                    awaitCancellation() // holds the lock until the cycle's deadline
                }
            }
            // ownRefresh: the first update's refresh leaves no permit, so the second takes its own.
            val refresh = FakeRefresh()
            refresh.answer = {
                if (ownRefresh && refresh.calls.size == 1) {
                    DealRefresh(tracked = false)
                } else {
                    DealRefresh(tracked = true, nextAllowedAt = clock.now() + 1.minutes)
                }
            }
            val tracker = tracker(Setup(FakeMarketplaceClient(heartbeat(proofRequired = true)), prover = hangsOnce, refresh = refresh))
            val holder = async { tracker.updateStatus(deal, retries = 0) }
            runCurrent()
            val waiter = async { tracker.updateStatus(deal, retries = 0) }
            runCurrent()
            assertEquals(if (ownRefresh) 2 else 1, refresh.calls.size, "ownRefresh=$ownRefresh: the waiter is past its check")

            clock.advance(2.minutes) // the permit's window closes while the waiter waits for the lock
            assertEquals(UpdateFailure.TIMEOUT, holder.await().failure)
            val late = waiter.await()

            if (ownRefresh) {
                assertEquals(UpdateFailure.TIMEOUT, late.failure, "one refresh per tap: no cycle without a permit")
                assertEquals(2, refresh.calls.size)
                assertEquals(1, proves, "the waiter ran no session")
            } else {
                assertEquals(UpdateFailure.NOTARY, late.failure, "the session ran under a new permit")
                assertEquals(2, refresh.calls.size, "the waiter took the new permit itself")
                assertEquals(UpdateFailure.RATE_LIMITED, tracker.updateStatus(deal, retries = 0).failure, "and its session counted")
            }
        }
    }

    @Test
    fun updates_whose_permits_lapse_in_the_queue_never_answer_with_another_updates_window() = runTest {
        val hanging = object : NotaryProver by FakeNotaryProver() {
            override suspend fun proveTransition(
                binding: ProvenReadBinding,
                source: TradeStatusSource,
                credential: SteamCredential,
            ): ProofSubmission {
                clock.advance(2.minutes) // every window closes while the queue waits
                awaitCancellation()
            }
        }
        val refresh = FakeRefresh { DealRefresh(tracked = true, nextAllowedAt = clock.now() + 1.minutes) }
        val tracker = tracker(Setup(FakeMarketplaceClient(heartbeat(proofRequired = true)), prover = hanging, refresh = refresh))

        val updates = List(3) { async { tracker.updateStatus(deal, retries = 0) } }
        val answers = updates.map { it.await().failure }

        assertEquals(List(3) { UpdateFailure.TIMEOUT }, answers)
        assertTrue(refresh.calls.size <= 3, "at most one refresh per tap: ${refresh.calls.size}")
    }

    @Test
    fun a_session_that_never_started_does_not_count() = runTest {
        val refresh = FakeRefresh { DealRefresh(tracked = true, nextAllowedAt = window()) }
        val notary = FakeNotaryProver().apply { proveThrows = HostedProofException(ProofFailure.NotStarted("no DMarket session")) }
        val tracker = tracker(Setup(FakeMarketplaceClient(heartbeat(proofRequired = true)), notary = notary, refresh = refresh))

        tracker.updateStatus(deal, retries = 0)
        val second = tracker.updateStatus(deal, retries = 0)

        assertFalse(second.failure == UpdateFailure.RATE_LIMITED, "the permit is whole: ${second.failure}")
        assertEquals(2, notary.proven.size)
        assertEquals(1, refresh.calls.size)
    }

    @Test
    fun an_attempt_the_notary_never_took_does_not_count() = runTest {
        for (failure in listOf(
            ProofFailure.UpgradeRefused(401),
            ProofFailure.UpgradeRefused(429),
            ProofFailure.NotaryUnreachable("dns"),
            ProofFailure.ProverKilled(KillCause.CONNECT_DEADLINE),
        )) {
            val refresh = FakeRefresh { DealRefresh(tracked = true, nextAllowedAt = window()) }
            val notary = FakeNotaryProver().apply { proveThrows = HostedProofException(failure) }
            val tracker = tracker(Setup(FakeMarketplaceClient(heartbeat(proofRequired = true)), notary = notary, refresh = refresh))

            tracker.updateStatus(deal, retries = 0)
            val second = tracker.updateStatus(deal, retries = 0)

            assertFalse(second.failure == UpdateFailure.RATE_LIMITED, "$failure: the permit is whole")
            assertEquals(2, notary.proven.size, "$failure")
        }
    }

    @Test
    fun a_proof_the_cycles_deadline_cut_does_not_count() = runTest {
        val hanging = object : NotaryProver by FakeNotaryProver() {
            override suspend fun proveTransition(
                binding: ProvenReadBinding,
                source: TradeStatusSource,
                credential: SteamCredential,
            ): ProofSubmission = awaitCancellation()
        }
        val refresh = FakeRefresh { DealRefresh(tracked = true, nextAllowedAt = window()) }
        val tracker = tracker(Setup(FakeMarketplaceClient(heartbeat(proofRequired = true)), prover = hanging, refresh = refresh))

        assertEquals(UpdateFailure.TIMEOUT, tracker.updateStatus(deal, retries = 0).failure)
        val next = tracker.updateStatus(deal, retries = 0)

        assertEquals(UpdateFailure.TIMEOUT, next.failure, "the permit was whole, so the run went: ${next.failure}")
        assertEquals(1, refresh.calls.size)
    }

    @Test
    fun a_native_session_the_cycles_deadline_cut_counts_only_after_the_notarys_101() = runTest {
        for (upgraded in listOf(false, true)) {
            val hangs = object : NativeProverEngine {
                override fun unavailableReason(): String? = null

                override suspend fun issue(issuance: NativeIssuance, listener: NativeIssuanceListener): NativeIssuanceOutcome {
                    if (upgraded) listener.onStage(2, "MPC_SETUP", 0.1f) // the prover's stream exists only after the 101
                    awaitCancellation()
                }
            }
            val prover = NativeNotaryProver(NotaryConfig(), NotaryTokenProvider { "notary-token" }, hangs, clock)
            val refresh = FakeRefresh { DealRefresh(tracked = true, nextAllowedAt = clock.now() + 1.hours) }
            val tracker = tracker(Setup(FakeMarketplaceClient(heartbeat(proofRequired = true)), prover = prover, refresh = refresh))

            assertEquals(UpdateFailure.TIMEOUT, tracker.updateStatus(deal, retries = 0).failure, "upgraded=$upgraded")
            val next = tracker.updateStatus(deal, retries = 0)

            assertEquals(
                if (upgraded) UpdateFailure.RATE_LIMITED else UpdateFailure.TIMEOUT,
                next.failure,
                "upgraded=$upgraded: the session counts only once the notary answered",
            )
            assertEquals(1, refresh.calls.size)
        }
    }

    @Test
    fun a_delivered_refusal_is_its_own_failure_also_when_the_same_bytes_meet_the_latch() = runTest {
        val mp = FakeMarketplaceClient(heartbeat(proofRequired = true)).apply {
            proofVerified = false
            proofReason = "presentation does not match the deal"
        }
        val tracker = tracker(Setup(mp))

        val update = tracker.updateStatus(deal)
        val again = tracker.updateStatus(deal)

        assertEquals(UpdateFailure.PROOF_REFUSED, update.failure, "a refusal is no success")
        assertEquals(UpdateFailure.PROOF_REFUSED, again.failure, "nor is the latch that keeps it")
        assertEquals(1, mp.proofsSubmitted.size)
    }

    @Test
    fun a_429_on_the_proofs_delivery_is_a_rate_limit_not_the_notary() = runTest {
        val mp = FakeMarketplaceClient(heartbeat(proofRequired = true)).apply { submitProofThrowable = RateLimitedException(60) }
        val tracker = tracker(Setup(mp))

        val update = tracker.updateStatus(deal)

        assertEquals(UpdateFailure.RATE_LIMITED, update.failure)
        assertEquals(60.seconds, update.retryAfter)
        assertTrue(events.events.contains(LifecycleEvent.RateLimited("marketplace", 60)))
        assertTrue(events.events.none { it is LifecycleEvent.ProofFailed }, "no dropped connection to tell")
    }

    @Test
    fun an_undelivered_proof_goes_again_without_a_session_or_a_retry() = runTest {
        val refresh = FakeRefresh { DealRefresh(tracked = true, nextAllowedAt = window()) }
        val mp = FakeMarketplaceClient(heartbeat(proofRequired = true)).apply {
            submitProofThrowable = MarketplaceServerErrorException(503)
        }
        val setup = Setup(mp, refresh = refresh)
        val tracker = tracker(setup)

        val undelivered = tracker.updateStatus(deal, retries = 0)
        mp.submitProofThrowable = null
        val resent = tracker.updateStatus(deal, retries = 0)

        assertEquals(UpdateFailure.DMARKET, undelivered.failure, "DMarket did not answer; the notary did its part")
        assertNull(resent.failure, "the spent permit lets the same bytes go again")
        assertEquals(1, setup.notary.proven.size, "one notary session")
        assertEquals(2, mp.proofsSubmitted.size)
        assertEquals(mp.proofsSubmitted[0], mp.proofsSubmitted[1], "the same bytes")
        assertEquals(1, refresh.calls.size)
        assertTrue(events.events.filterIsInstance<LifecycleEvent.ProofFailed>().single().undelivered)
    }

    @Test
    fun a_resend_reads_the_text_first_then_the_deal() = runTest {
        val replayed = "this presentation was already accepted for this deal"
        val wrongPhase = "a deal in AWAITING_TERMINAL is not waiting on a provable transition"
        val cases = listOf(
            Triple(true, replayed, null), // p2p's word about these bytes outranks the deal's one flag
            Triple(false, wrongPhase, null),
            Triple(true, wrongPhase, UpdateFailure.PROOF_REFUSED),
        )
        for ((needsStatusUpdate, reason, failure) in cases) {
            val mp = FakeMarketplaceClient(heartbeat(proofRequired = true)).apply {
                submitProofThrowable = MarketplaceServerErrorException(503)
                dealForGet = fakeDeal("d1").copy(needsStatusUpdate = needsStatusUpdate)
            }
            val tracker = tracker(Setup(mp))
            tracker.updateStatus(deal)
            mp.submitProofThrowable = null
            mp.proofVerified = false
            mp.proofReason = reason

            assertEquals(failure, tracker.updateStatus(deal).failure, "needsStatusUpdate=$needsStatusUpdate, reason=$reason")
        }
    }

    @Test
    fun undelivered_bytes_keep_a_retry_open_whatever_the_permit_has_left() = runTest {
        for (retries in 0..1) {
            val refresh = FakeRefresh { DealRefresh(tracked = true, nextAllowedAt = window()) }
            val notary = FakeNotaryProver().apply { if (retries > 0) proveThrows = IllegalStateException("notary connection dropped") }
            val mp = FakeMarketplaceClient(heartbeat(proofRequired = true)).apply {
                submitProofThrowable =
                    MarketplaceServerErrorException(503)
            }
            val tracker = tracker(Setup(mp, notary = notary, refresh = refresh))
            repeat(retries) { tracker.updateStatus(deal, retries = retries) } // the first session fails at the notary
            notary.proveThrows = null

            val undelivered = tracker.updateStatus(deal, retries = retries) // the last session's proof is not delivered
            mp.submitProofThrowable = null
            val resent = tracker.updateStatus(deal, retries = retries)

            assertEquals(UpdateFailure.DMARKET, undelivered.failure, "retries=$retries")
            assertEquals(1, undelivered.retryLeft, "retries=$retries: the permit's sessions are spent, the bytes still go")
            assertNull(resent.failure, "retries=$retries")
            assertEquals(0, resent.retryLeft, "retries=$retries: delivered, the permit is spent")
            assertEquals(retries + 1, notary.proven.size, "retries=$retries: the resend ran no session")
        }
    }

    @Test
    fun undelivered_bytes_go_once_their_deal_is_untracked_or_steam_shows_another_code() = runTest {
        for (moveOn in listOf("untracked", "another code", "steam unread")) {
            val refresh = FakeRefresh { DealRefresh(tracked = true, nextAllowedAt = window()) }
            val reader = FakeSteamReadClient(initialOffers = mapOf(offer to 3))
            val mp = FakeMarketplaceClient(heartbeat(proofRequired = true)).apply {
                submitProofThrowable = MarketplaceServerErrorException(503)
            }
            val tracker = tracker(Setup(mp, reader = reader, refresh = refresh))
            assertEquals(1, tracker.updateStatus(deal, retries = 0).retryLeft, moveOn)
            mp.submitProofThrowable = MarketplaceServerErrorException(503)
            when (moveOn) {
                "untracked" -> mp.heartbeatResponse = HeartbeatResponse(ttlSeconds = 86_400)
                "another code" -> reader.offers = mapOf(offer to 11) // not decisive: no new proof is due
                else -> reader.offerStatusesThrows = true
            }

            val moved = tracker.updateStatus(deal, retries = 0)

            if (moveOn == "steam unread") {
                assertEquals(1, moved.retryLeft, "an axis not read is no sign the transition is past")
            } else {
                assertEquals(0, moved.retryLeft, moveOn)
                assertEquals(UpdateFailure.RATE_LIMITED, tracker.updateStatus(deal, retries = 0).failure, moveOn)
                assertEquals(1, mp.proofsSubmitted.size, "$moveOn: the bytes were not sent again")
            }
        }
    }

    private fun assertProvedAndReported(setup: Setup, update: StatusUpdate) {
        assertEquals(1, setup.marketplace.proofsSubmitted.size, "the proof ran")
        assertEquals(listOf(3), setup.marketplace.tradeStatusReports.map { it.steamStatusCode })
        assertEquals(ProofState.NONE, update.proofState)
        assertNull(update.failure)
        assertTrue(events.events.none { it is LifecycleEvent.ProofDeferred })
    }
}
