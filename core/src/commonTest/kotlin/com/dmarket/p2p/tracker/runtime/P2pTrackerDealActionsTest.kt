package com.dmarket.p2p.tracker.runtime

import com.dmarket.p2p.tracker.adapter.steam.NoOpSteamSessionRefresher
import com.dmarket.p2p.tracker.client.HttpTransportException
import com.dmarket.p2p.tracker.client.marketplace.RateLimitedException
import com.dmarket.p2p.tracker.credential.steam.SteamCredentialProvider
import com.dmarket.p2p.tracker.engine.TrackerBlock
import com.dmarket.p2p.tracker.loop.LoopConfig
import com.dmarket.p2p.tracker.loop.TradeTrackerLoop
import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.DirectiveId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.RuntimeSurface
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.TrackerMode
import com.dmarket.p2p.tracker.model.marketplace.Deal
import com.dmarket.p2p.tracker.model.marketplace.DealActionResult
import com.dmarket.p2p.tracker.model.marketplace.DealRole
import com.dmarket.p2p.tracker.model.marketplace.Directive
import com.dmarket.p2p.tracker.model.marketplace.DirectiveAction
import com.dmarket.p2p.tracker.model.marketplace.HeartbeatRequest
import com.dmarket.p2p.tracker.model.marketplace.HeartbeatResponse
import com.dmarket.p2p.tracker.model.marketplace.P2PDealState
import com.dmarket.p2p.tracker.model.marketplace.TrackedDeal
import com.dmarket.p2p.tracker.model.marketplace.WatchTarget
import com.dmarket.p2p.tracker.port.host.NetworkConditions
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceClient
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceServerErrorException
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceUnauthorizedException
import com.dmarket.p2p.tracker.port.steam.CreateOfferResult
import com.dmarket.p2p.tracker.support.FakeClock
import com.dmarket.p2p.tracker.support.FakeCredentialVault
import com.dmarket.p2p.tracker.support.FakeDeviceIdStore
import com.dmarket.p2p.tracker.support.FakeMarketplaceClient
import com.dmarket.p2p.tracker.support.FakeScheduler
import com.dmarket.p2p.tracker.support.FakeSteamOfferCreator
import com.dmarket.p2p.tracker.support.FakeSteamReadClient
import com.dmarket.p2p.tracker.support.FakeSteamSessionScraper
import com.dmarket.p2p.tracker.support.delivered
import com.dmarket.p2p.tracker.support.fakeDeal
import com.dmarket.p2p.tracker.support.fakeSteamCredential
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class P2pTrackerDealActionsTest {
    private val account = SteamId("76561198000000001")
    private val deal = DealId("deal-1")
    private val clock = FakeClock()

    private val sellerHeartbeat = HeartbeatResponse(
        activeTracking = listOf(
            TrackedDeal(
                dealId = deal,
                steamOfferId = OfferId("offer-1"),
                watch = setOf(WatchTarget.GET_TRADE_OFFER),
                role = DealRole.SELLER,
            ),
        ),
        ttlSeconds = 86_400,
    )

    private fun createDirective(id: String = "dir-1") = Directive(
        directiveId = DirectiveId(id),
        action = DirectiveAction.CREATE_OFFER,
        dealId = deal,
        partnerSteamId = SteamId("76561198000000002"),
        assetIds = listOf(AssetId("asset-1")),
        tradeToken = "trade-token",
        contextId = 2,
    )

    /** Logs the heartbeat and accept calls in order, and fails a deal call with what a test sets. */
    private class RecordingMarketplace(val fake: FakeMarketplaceClient) : MarketplaceClient by fake {
        val calls = mutableListOf<String>()
        var acceptFailure: Throwable? = null
        var getDealFailure: Throwable? = null

        override suspend fun heartbeat(request: HeartbeatRequest): HeartbeatResponse = fake.heartbeat(request).also { calls += "heartbeat" }

        override suspend fun acceptDeal(id: DealId): DealActionResult {
            calls += "accept"
            acceptFailure?.let { throw it }
            return fake.acceptDeal(id)
        }

        override suspend fun getDeal(id: DealId): Deal {
            getDealFailure?.let { throw it }
            return fake.getDeal(id)
        }
    }

    private class Built(val tracker: P2pTracker, val loops: MutableList<TradeTrackerLoop>)

    private fun TestScope.tracker(
        marketplace: MarketplaceClient,
        creator: FakeSteamOfferCreator = FakeSteamOfferCreator(),
        linked: SteamId? = account,
    ): Built {
        val loops = mutableListOf<TradeTrackerLoop>()
        val tracker = P2pTracker(
            scope = backgroundScope,
            clock = clock,
            deviceIds = FakeDeviceIdStore(),
            linkedAccount = { linked },
            loopFor = { _, observer ->
                TradeTrackerLoop(
                    config = LoopConfig("1.0.0", RuntimeSurface.AndroidNative, TrackerMode.Background),
                    marketplace = marketplace,
                    steamReader = FakeSteamReadClient(),
                    credentials = SteamCredentialProvider(
                        vault = FakeCredentialVault(fakeSteamCredential()),
                        scraper = FakeSteamSessionScraper(),
                        clock = clock,
                        sessionRefresher = NoOpSteamSessionRefresher,
                    ),
                    scheduler = FakeScheduler(),
                    clock = clock,
                    deviceId = FakeDeviceIdStore(),
                    offerCreator = creator,
                    directivesEnabled = true,
                    eventObserver = observer,
                ).also { loops += it }
            },
            network = NetworkConditions.Unmetered,
            cellularProofs = { false },
        )
        return Built(tracker, loops)
    }

    // ---- startDelivery ------------------------------------------------------------------------

    @Test
    fun start_delivery_runs_the_directive_its_create_trade_call_answers_with() = runTest {
        val mp = FakeMarketplaceClient(sellerHeartbeat).apply { deliveries = listOf(delivered(createDirective())) }
        val creator = FakeSteamOfferCreator()

        val outcome = tracker(mp, creator).tracker.startDelivery(deal)
        runCurrent() // the report and the heartbeat after the create run in the session's (background) scope

        assertEquals(DeliveryOutcome(CreateOfferResult.NeedsConfirmation(OfferId("offer-created"))), outcome)
        assertEquals(1, creator.created.size)
        assertEquals(DirectiveId("dir-1"), mp.directiveOutcomes.single().directiveId)
        assertEquals(1, mp.startDeliveryCalls)
    }

    @Test
    fun a_refused_delivery_carries_its_reason_code_and_writes_nothing_to_steam() = runTest {
        val mp = FakeMarketplaceClient(sellerHeartbeat).apply {
            deliveries = listOf(DealActionResult(P2PDealState.COMMITTED, applied = false, reasonCode = "DeliveryWindowClosed"))
        }
        val creator = FakeSteamOfferCreator()

        val outcome = tracker(mp, creator).tracker.startDelivery(deal)

        assertIs<CreateOfferResult.Failed>(outcome.result)
        assertEquals("DeliveryWindowClosed", outcome.reasonCode)
        assertNull(outcome.error)
        assertTrue(creator.created.isEmpty())
        assertEquals(1, mp.startDeliveryCalls)
    }

    @Test
    fun a_failed_create_trade_call_says_why_in_the_outcome() = runTest {
        val cases = listOf(
            RateLimitedException(30) to DealCallError(DealCallErrorKind.RATE_LIMITED, 429, retryAfter = 30.seconds),
            MarketplaceUnauthorizedException() to DealCallError(DealCallErrorKind.UNAUTHORIZED, 401),
            MarketplaceServerErrorException(503) to DealCallError(DealCallErrorKind.SERVER, 503),
            MarketplaceServerErrorException(403, "P2P_NOT_A_PARTICIPANT") to
                DealCallError(DealCallErrorKind.SERVER, 403, "P2P_NOT_A_PARTICIPANT"),
            MarketplaceServerErrorException(404) to DealCallError(DealCallErrorKind.UNAVAILABLE, 404),
            HttpTransportException("timeout", "POST", "https://x") to DealCallError(DealCallErrorKind.TIMEOUT),
            HttpTransportException("Fail to fetch", "POST", "https://x") to DealCallError(DealCallErrorKind.NETWORK),
            IOException("connection reset") to DealCallError(DealCallErrorKind.NETWORK),
            IllegalArgumentException("bad body") to DealCallError(DealCallErrorKind.OTHER),
        )
        for ((thrown, expected) in cases) {
            val mp = FakeMarketplaceClient(sellerHeartbeat).apply { startDeliveryThrowable = thrown }
            val creator = FakeSteamOfferCreator()

            val outcome = tracker(mp, creator).tracker.startDelivery(deal)

            assertIs<CreateOfferResult.Failed>(outcome.result, "$thrown")
            assertEquals(expected, outcome.error, "$thrown")
            assertNull(outcome.reasonCode)
            assertEquals(2, mp.startDeliveryCalls, "a failed call is repeated once, as an empty answer: $thrown")
            assertTrue(creator.created.isEmpty())
        }
    }

    @Test
    fun an_answer_without_a_directive_fails_with_neither_a_reason_nor_an_error() = runTest {
        val outcome = tracker(FakeMarketplaceClient(sellerHeartbeat)).tracker.startDelivery(deal)

        assertIs<CreateOfferResult.Failed>(outcome.result)
        assertNull(outcome.reasonCode)
        assertNull(outcome.error)
    }

    @Test
    fun concurrent_deliveries_and_a_heartbeat_lease_of_the_deal_end_in_one_steam_offer() = runTest {
        // Two presses while the call is slow, and a wake whose heartbeat leases the same create under a fresh id.
        val mp = FakeMarketplaceClient(sellerHeartbeat).apply {
            deliveries = listOf(delivered(createDirective("dir-1")))
            startDeliveryDelay = 1.seconds
        }
        val creator = FakeSteamOfferCreator()
        val tracker = tracker(mp, creator).tracker

        val first = async { tracker.startDelivery(deal) }
        val second = async { tracker.startDelivery(deal) }
        runCurrent()
        mp.heartbeatResponse = sellerHeartbeat.copy(directives = listOf(createDirective("dir-2")))
        tracker.onPush("""{"type":"wake_deal","dealId":"deal-1","reason":"buyer_accepted","issuedAt":"2026-11-11T10:00:00Z"}""")
        advanceTimeBy(10.seconds)
        runCurrent()

        assertEquals(1, creator.created.size, "one Steam offer for the deal")
        assertEquals(first.await(), second.await(), "concurrent presses share one run")
        assertTrue(first.await().result !is CreateOfferResult.Failed, "${first.await()}")
        assertEquals(1, mp.startDeliveryCalls)
    }

    @Test
    fun start_delivery_waits_for_the_running_cycle_before_it_calls_anything() = runTest {
        val fake = FakeMarketplaceClient(sellerHeartbeat).apply { deliveries = listOf(delivered(createDirective())) }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var hold = false
        val held = object : MarketplaceClient by fake {
            override suspend fun heartbeat(request: HeartbeatRequest): HeartbeatResponse {
                if (hold) {
                    hold = false
                    entered.complete(Unit)
                    release.await()
                }
                return fake.heartbeat(request)
            }
        }
        val tracker = tracker(held).tracker
        tracker.updateStatus(deal) // the loop has its tracking list: the create goes straight to its call
        hold = true
        val update = async { tracker.updateStatus(deal) }
        entered.await()

        val delivery = async { tracker.startDelivery(deal) }
        runCurrent()
        assertEquals(0, fake.startDeliveryCalls, "the create waits for the cycle's lock")

        release.complete(Unit)
        assertIs<CreateOfferResult.NeedsConfirmation>(delivery.await().result)
        update.await()
        assertEquals(1, fake.startDeliveryCalls)
    }

    @Test
    fun start_delivery_without_a_linked_account_calls_nothing() = runTest {
        val mp = FakeMarketplaceClient(sellerHeartbeat).apply { deliveries = listOf(delivered(createDirective())) }

        val outcome = tracker(mp, linked = null).tracker.startDelivery(deal)

        assertIs<CreateOfferResult.Failed>(outcome.result)
        assertEquals(DealCallError(DealCallErrorKind.NOT_RUNNING), outcome.error)
        assertEquals(0, mp.startDeliveryCalls)
    }

    // ---- acceptDeal ---------------------------------------------------------------------------

    @Test
    fun accept_syncs_on_screen_before_it_accepts() = runTest {
        val mp = RecordingMarketplace(FakeMarketplaceClient(sellerHeartbeat))
        val built = tracker(mp)

        val outcome = built.tracker.acceptDeal(deal)

        assertEquals(AcceptOutcome.Done(DealActionResult(P2PDealState.COMMITTED, applied = true)), outcome)
        assertEquals(listOf("heartbeat", "accept"), mp.calls)
        assertTrue(mp.fake.heartbeatsSent.single().foreground, "the sync heartbeats as on screen")
        assertEquals(TrackerMode.Background, built.loops.single().mode, "the app's own mode is back after the sync")
        assertEquals(listOf(deal), mp.fake.acceptedDeals)
    }

    @Test
    fun a_cancelled_accept_puts_the_apps_mode_back() = runTest {
        val fake = FakeMarketplaceClient(sellerHeartbeat)
        val entered = CompletableDeferred<Unit>()
        val held = object : MarketplaceClient by fake {
            override suspend fun heartbeat(request: HeartbeatRequest): HeartbeatResponse {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val built = tracker(held)

        val accept = async { built.tracker.acceptDeal(deal) }
        entered.await()
        assertEquals(TrackerMode.Foreground, built.loops.single().mode, "the sync runs as on screen")
        accept.cancel()
        runCurrent()

        assertEquals(TrackerMode.Background, built.loops.single().mode, "the app's own mode is back")
        assertTrue(fake.acceptedDeals.isEmpty())
    }

    @Test
    fun accept_on_a_blocked_tracker_sends_no_accept() = runTest {
        val mp = RecordingMarketplace(FakeMarketplaceClient(sellerHeartbeat.copy(linkedSteamId = SteamId("76561198000000099"))))

        val outcome = tracker(mp).tracker.acceptDeal(deal)

        assertEquals(AcceptOutcome.Blocked(TrackerBlock.STEAM_ACCOUNT_MISMATCH), outcome)
        assertEquals(listOf("heartbeat"), mp.calls)
        assertTrue(mp.fake.acceptedDeals.isEmpty())
    }

    @Test
    fun a_refused_accept_carries_the_status_and_the_p2p_code() = runTest {
        val mp = RecordingMarketplace(FakeMarketplaceClient(sellerHeartbeat)).apply {
            acceptFailure = MarketplaceServerErrorException(409, "P2P_PRECOMMIT_WINDOW_CLOSED")
        }

        val outcome = tracker(mp).tracker.acceptDeal(deal)

        assertEquals(AcceptOutcome.Failed(DealCallError(DealCallErrorKind.SERVER, 409, "P2P_PRECOMMIT_WINDOW_CLOSED")), outcome)
    }

    // ---- getDeal ------------------------------------------------------------------------------

    @Test
    fun get_deal_answers_the_deal_or_why_it_could_not() = runTest {
        val detail = fakeDeal(state = P2PDealState.COMMITTED).copy(cancellable = false, nextAction = "seller_create_trade")
        val mp = RecordingMarketplace(FakeMarketplaceClient(sellerHeartbeat, dealForGet = detail))
        val tracker = tracker(mp).tracker

        assertEquals(DealRead.Found(detail), tracker.getDeal(deal))
        mp.getDealFailure = MarketplaceServerErrorException(404, "P2P_DEAL_NOT_FOUND")
        assertEquals(DealRead.Failed(DealCallError(DealCallErrorKind.SERVER, 404, "P2P_DEAL_NOT_FOUND")), tracker.getDeal(deal))
    }
}
