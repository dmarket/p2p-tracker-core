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
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.model.steam.TradeDraft
import com.dmarket.p2p.tracker.net.STEAM_PROXY_UNREACHABLE_REASON
import com.dmarket.p2p.tracker.port.steam.CancelOfferResult
import com.dmarket.p2p.tracker.port.steam.CreateOfferResult
import com.dmarket.p2p.tracker.port.steam.SteamOfferCanceller
import com.dmarket.p2p.tracker.port.steam.SteamOfferCreator
import com.dmarket.p2p.tracker.support.FakeClock
import com.dmarket.p2p.tracker.support.FakeCredentialVault
import com.dmarket.p2p.tracker.support.FakeDeviceIdStore
import com.dmarket.p2p.tracker.support.FakeMarketplaceClient
import com.dmarket.p2p.tracker.support.FakeScheduler
import com.dmarket.p2p.tracker.support.FakeSteamOfferCanceller
import com.dmarket.p2p.tracker.support.FakeSteamOfferCreator
import com.dmarket.p2p.tracker.support.FakeSteamReadClient
import com.dmarket.p2p.tracker.support.FakeSteamSessionScraper
import com.dmarket.p2p.tracker.support.RecordingEventObserver
import com.dmarket.p2p.tracker.support.fakeSteamCredential
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * A write the transport could not send ([CreateOfferResult.NotSent], [CancelOfferResult.NotSent]: the account's Steam
 * proxy was unreachable) never reached Steam, so it is deferred like a create the token is too short for: never
 * reported, its claim released, and run again when the backend re-leases it.
 */
class NotSentWriteTest {
    private val partner = SteamId("76561199497281579")

    private fun create(id: String, deal: String) = Directive(
        directiveId = DirectiveId(id),
        action = DirectiveAction.CREATE_OFFER,
        dealId = DealId(deal),
        partnerSteamId = partner,
        assetIds = listOf(AssetId("asset-$id")),
        tradeToken = "trade-token",
        contextId = 2,
    )

    /** Not sent while [down], then Steam makes the offer. */
    private class ProxyCreator(var down: Boolean = true) : SteamOfferCreator {
        var attempts = 0

        override suspend fun createOffer(credential: SteamCredential, draft: TradeDraft): CreateOfferResult {
            attempts++
            return if (down) {
                CreateOfferResult.NotSent(
                    STEAM_PROXY_UNREACHABLE_REASON,
                )
            } else {
                CreateOfferResult.NeedsConfirmation(OfferId("offer-1"))
            }
        }
    }

    private class Setup(
        val loop: TradeTrackerLoop,
        val mp: FakeMarketplaceClient,
        val events: RecordingEventObserver,
        val claims: DealWriteClaimStore,
    )

    private fun cancel(id: String, deal: String) = Directive(
        directiveId = DirectiveId(id),
        action = DirectiveAction.CANCEL_OFFER,
        dealId = DealId(deal),
        steamOfferId = OfferId("offer-$id"),
    )

    private fun setup(
        creator: SteamOfferCreator,
        reportUnsentCreates: Boolean,
        vararg directives: Directive,
        canceller: SteamOfferCanceller = FakeSteamOfferCanceller(),
        checkFailedCancels: Boolean = false,
    ): Setup {
        val clock = FakeClock()
        val mp = FakeMarketplaceClient(heartbeatResponse = HeartbeatResponse(ttlSeconds = 60, directives = directives.toList()))
        val events = RecordingEventObserver()
        val claims = PersistedDealWriteClaimStore()
        val loop = TradeTrackerLoop(
            config = LoopConfig(
                "1.0.0",
                RuntimeSurface.Headless,
                TrackerMode.Background,
                reportUnsentCreates = reportUnsentCreates,
                unansweredCreateGrace = 2.minutes,
                checkFailedCancels = checkFailedCancels,
            ),
            marketplace = mp,
            steamReader = FakeSteamReadClient(),
            credentials = SteamCredentialProvider(
                vault = FakeCredentialVault(steamCredential = fakeSteamCredential()),
                scraper = FakeSteamSessionScraper(result = fakeSteamCredential()),
                clock = clock,
            ),
            scheduler = FakeScheduler(),
            clock = clock,
            deviceId = FakeDeviceIdStore(),
            offerCreator = creator,
            offerCanceller = canceller,
            progress = InMemoryTrackerProgressStore(),
            claims = claims,
            directivesEnabled = true,
            eventObserver = events,
        )
        return Setup(loop, mp, events, claims)
    }

    @Test
    fun a_create_not_sent_is_deferred_with_its_deal_and_never_reported() = runTest {
        for (reportUnsentCreates in listOf(false, true)) {
            val s = setup(ProxyCreator(), reportUnsentCreates, create("a-1", "deal-1"))

            s.loop.runOnce()

            assertEquals(
                emptyList(),
                s.mp.directiveOutcomes,
                "nothing reached Steam, so nothing is reported (reportUnsentCreates=$reportUnsentCreates)",
            )
            val deferred = s.events.events.filterIsInstance<LifecycleEvent.SteamWriteDeferred>().single()
            assertEquals(
                listOf("create_offer", "a-1", "deal-1", STEAM_PROXY_UNREACHABLE_REASON),
                listOf(deferred.kind, deferred.directiveId, deferred.dealId, deferred.reason),
            )
            assertTrue(s.events.events.none { it is LifecycleEvent.CreateUnanswered }, "not held as a create Steam may have made")
            assertTrue(s.events.events.none { it is LifecycleEvent.DirectiveExecuted }, "no outcome at all")
            assertTrue(s.claims.all().none { it.dealId == DealId("deal-1") }, "the claim goes with nothing written")
        }
    }

    @Test
    fun the_rest_of_the_partners_chain_waits_and_the_re_leased_create_runs_once_the_proxy_is_back() = runTest {
        val creator = ProxyCreator()
        val s = setup(creator, reportUnsentCreates = false, create("a-1", "deal-1"), create("a-2", "deal-2"))

        s.loop.runOnce()
        assertEquals(1, creator.attempts, "the chain stops at the first create not sent")
        assertEquals(emptyList(), s.mp.directiveOutcomes)

        creator.down = false
        s.loop.forceHeartbeatNow()
        s.loop.runOnce()

        assertEquals(OfferId("offer-1"), s.mp.directiveOutcomes.first { it.directiveId == DirectiveId("a-1") }.steamOfferId)
        assertTrue(s.mp.directiveOutcomes.none { it.status.name == "FAILED" }, "${s.mp.directiveOutcomes}")
    }

    @Test
    fun a_fake_creator_answering_not_sent_leaves_the_throttle_open() = runTest {
        val s =
            setup(FakeSteamOfferCreator(result = CreateOfferResult.NotSent(STEAM_PROXY_UNREACHABLE_REASON)), false, create("a-1", "deal-1"))
        s.loop.runOnce()
        s.loop.forceHeartbeatNow()
        s.loop.runOnce()
        assertTrue(
            s.events.events.filterIsInstance<LifecycleEvent.SteamWriteDeferred>().all { it.reason == STEAM_PROXY_UNREACHABLE_REASON },
            "a proxy failure opens no cooldown: ${s.events.events}",
        )
    }

    @Test
    fun a_cancel_not_sent_is_deferred_unread_and_never_reported_whether_or_not_failed_cancels_are_checked() = runTest {
        for (checkFailedCancels in listOf(false, true)) {
            val canceller = FakeSteamOfferCanceller(result = CancelOfferResult.NotSent(STEAM_PROXY_UNREACHABLE_REASON))
            val s = setup(
                ProxyCreator(),
                reportUnsentCreates = false,
                cancel("c-1", "deal-1"),
                canceller = canceller,
                checkFailedCancels = checkFailedCancels,
            )

            s.loop.runOnce()

            assertEquals(1, canceller.attempts)
            assertEquals(emptyList(), s.mp.directiveOutcomes, "checkFailedCancels=$checkFailedCancels")
            val deferred = s.events.events.filterIsInstance<LifecycleEvent.SteamWriteDeferred>().single()
            assertEquals(
                listOf("cancel_offer", "c-1", "deal-1", STEAM_PROXY_UNREACHABLE_REASON),
                listOf(deferred.kind, deferred.directiveId, deferred.dealId, deferred.reason),
            )
            assertTrue(s.events.events.none { it is LifecycleEvent.DirectiveExecuted || it is LifecycleEvent.DirectiveDropped })
            assertTrue(s.events.events.none { it is LifecycleEvent.FailedCancelChecked }, "nothing to read: the offer is as it was")
            assertTrue(s.claims.all().none { it.dealId == DealId("deal-1") }, "the claim goes with nothing written")

            canceller.result = CancelOfferResult.Cancelled
            s.loop.forceHeartbeatNow()
            s.loop.runOnce()
            assertEquals("SUCCESS", s.mp.directiveOutcomes.single { it.directiveId == DirectiveId("c-1") }.status.name)
        }
    }
}
