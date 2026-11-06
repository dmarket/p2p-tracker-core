package com.dmarket.p2p.tracker.loop

import com.dmarket.p2p.tracker.credential.steam.SteamCredentialProvider
import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.DirectiveId
import com.dmarket.p2p.tracker.model.LifecycleEvent
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.RuntimeSurface
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.TrackerMode
import com.dmarket.p2p.tracker.model.marketplace.Directive
import com.dmarket.p2p.tracker.model.marketplace.DirectiveAction
import com.dmarket.p2p.tracker.model.marketplace.HeartbeatResponse
import com.dmarket.p2p.tracker.port.steam.CreateOfferResult
import com.dmarket.p2p.tracker.support.FakeClock
import com.dmarket.p2p.tracker.support.FakeCredentialVault
import com.dmarket.p2p.tracker.support.FakeDeviceIdStore
import com.dmarket.p2p.tracker.support.FakeMarketplaceClient
import com.dmarket.p2p.tracker.support.FakeScheduler
import com.dmarket.p2p.tracker.support.FakeSteamOfferCreator
import com.dmarket.p2p.tracker.support.FakeSteamReadClient
import com.dmarket.p2p.tracker.support.FakeSteamSessionScraper
import com.dmarket.p2p.tracker.support.RecordingEventObserver
import com.dmarket.p2p.tracker.support.delivered
import com.dmarket.p2p.tracker.support.fakeSteamCredential
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Offer creation paused by the host ([TradeTrackerLoop.setCreatesPaused]): no `create_offer` reaches Steam or the
 * backend, a create this device already made is still restated, and the rest of the cycle runs as before.
 */
class CreatesPausedTest {

    private val partner = SteamId("76561199497281579")

    private fun create(id: String, deal: String = "deal-1") = Directive(
        directiveId = DirectiveId(id),
        action = DirectiveAction.CREATE_OFFER,
        dealId = DealId(deal),
        partnerSteamId = partner,
        assetIds = listOf(AssetId("asset-1")),
        tradeToken = "trade-token",
        contextId = 2,
    )

    private val cancel = Directive(
        directiveId = DirectiveId("c-1"),
        action = DirectiveAction.CANCEL_OFFER,
        dealId = DealId("deal-c"),
        steamOfferId = OfferId("offer-c"),
    )

    private class Setup(
        val loop: TradeTrackerLoop,
        val mp: FakeMarketplaceClient,
        val creator: FakeSteamOfferCreator,
        val reader: FakeSteamReadClient,
        val events: RecordingEventObserver,
        val progress: TrackerProgressStore,
        val claims: DealWriteClaimStore,
    )

    private fun setup(vararg directives: Directive): Setup {
        val clock = FakeClock()
        val mp = FakeMarketplaceClient(heartbeatResponse = HeartbeatResponse(ttlSeconds = 60, directives = directives.toList()))
        val creator = FakeSteamOfferCreator()
        val reader = FakeSteamReadClient()
        val events = RecordingEventObserver()
        val progress = InMemoryTrackerProgressStore()
        val claims = PersistedDealWriteClaimStore()
        val loop = TradeTrackerLoop(
            config = LoopConfig("1.0.0", RuntimeSurface.WebChrome, TrackerMode.Background),
            marketplace = mp,
            steamReader = reader,
            credentials = SteamCredentialProvider(
                vault = FakeCredentialVault(steamCredential = fakeSteamCredential()),
                scraper = FakeSteamSessionScraper(result = fakeSteamCredential()),
                clock = clock,
            ),
            scheduler = FakeScheduler(),
            clock = clock,
            deviceId = FakeDeviceIdStore(),
            offerCreator = creator,
            progress = progress,
            claims = claims,
            directivesEnabled = true,
            eventObserver = events,
        )
        return Setup(loop, mp, creator, reader, events, progress, claims)
    }

    private fun RecordingEventObserver.pausedDrops() =
        events.filterIsInstance<LifecycleEvent.DirectiveDropped>().filter { it.reason == CREATES_PAUSED_DROP_REASON }

    @Test
    fun a_paused_create_is_neither_written_nor_reported_while_a_cancel_runs() = runTest {
        val s = setup(create("a-1"), cancel)
        s.loop.setCreatesPaused(true)

        s.loop.runOnce()

        assertTrue(s.creator.attemptOrder.isEmpty(), "nothing is POSTed to Steam")
        assertEquals(listOf("c-1"), s.mp.directiveOutcomes.map { it.directiveId.value }, "only the cancel is reported")
        assertEquals(listOf("a-1" to "deal-1"), s.events.pausedDrops().map { it.directiveId to it.dealId })
        assertTrue(DirectiveId("a-1") !in s.progress.loadHandledDirectives(), "not marked handled")
        assertTrue(s.claims.all().none { it.dealId == DealId("deal-1") }, "no claim is left behind")
    }

    @Test
    fun lifting_the_pause_runs_the_re_leased_create() = runTest {
        val s = setup(create("a-1"))
        s.loop.setCreatesPaused(true)
        s.loop.runOnce()

        s.loop.setCreatesPaused(false)
        s.loop.forceHeartbeatNow()
        s.loop.runOnce()

        assertEquals(1, s.creator.created.size)
        assertEquals(OfferId("offer-created"), s.mp.directiveOutcomes.single { it.directiveId == DirectiveId("a-1") }.steamOfferId)
    }

    /** Steam's offer state for the offer a create already made, and whether the paused re-lease restates it. */
    @Test
    fun a_made_create_is_restated_under_the_pause_only_while_its_offer_is_alive() = runTest {
        for ((state, restated) in listOf(9 to true, 2 to true, 6 to false, 7 to false)) {
            val s = setup(create("a-1"))
            s.loop.runOnce()

            s.loop.setCreatesPaused(true)
            s.reader.offers = mapOf(OfferId("offer-created") to state)
            s.mp.heartbeatResponse = HeartbeatResponse(ttlSeconds = 60, directives = listOf(create("a-2")))
            s.loop.forceHeartbeatNow()
            s.loop.runOnce()

            assertEquals(1, s.creator.created.size, "state $state: no second POST under the pause")
            val replay = s.mp.directiveOutcomes.singleOrNull { it.directiveId == DirectiveId("a-2") }
            assertEquals(restated, replay?.steamOfferId == OfferId("offer-created"), "state $state: restated")
            assertEquals(!restated, s.events.pausedDrops().any { it.directiveId == "a-2" }, "state $state: dropped")
        }
    }

    @Test
    fun a_page_create_for_a_new_deal_asks_the_backend_and_answers_paused() = runTest {
        val s = setup()
        s.loop.setCreatesPaused(true)
        s.mp.deliveries = listOf(delivered(create("fe-1", deal = "deal-fe")))

        val result = s.loop.awaitDirectiveOutcome(DealId("deal-fe"), 30.seconds, this)
        testScheduler.advanceUntilIdle()

        assertSame(CreateOfferResult.CreatesPaused, result)
        assertEquals(1, s.mp.startDeliveryCalls)
        assertTrue(s.creator.created.isEmpty(), "nothing is POSTed to Steam")
        assertTrue(s.mp.directiveOutcomes.none { it.directiveId == DirectiveId("fe-1") }, "nothing is reported")
    }

    @Test
    fun a_page_create_for_a_made_offer_answers_with_that_offer_under_the_pause() = runTest {
        val s = setup(create("a-1"))
        s.loop.runOnce()
        s.loop.setCreatesPaused(true)
        s.reader.offers = mapOf(OfferId("offer-created") to 9)
        s.mp.heartbeatResponse = HeartbeatResponse(ttlSeconds = 60)
        s.mp.deliveries = listOf(delivered(create("a-2")))

        val result = s.loop.awaitDirectiveOutcome(DealId("deal-1"), 30.seconds, this)
        testScheduler.advanceUntilIdle()

        assertEquals(CreateOfferResult.AlreadyCreated(OfferId("offer-created")), result)
        assertEquals(1, s.mp.startDeliveryCalls)
        assertEquals(1, s.creator.created.size, "no second POST")
        assertEquals(OfferId("offer-created"), s.mp.directiveOutcomes.single { it.directiveId == DirectiveId("a-2") }.steamOfferId)
    }
}
