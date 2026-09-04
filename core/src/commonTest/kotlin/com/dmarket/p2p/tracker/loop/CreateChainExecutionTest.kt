package com.dmarket.p2p.tracker.loop

import com.dmarket.p2p.tracker.adapter.host.InMemoryDeviceKeyValueStore
import com.dmarket.p2p.tracker.config.SteamWriteConfig
import com.dmarket.p2p.tracker.config.TrackerConfig
import com.dmarket.p2p.tracker.credential.steam.SteamCredentialProvider
import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.DirectiveId
import com.dmarket.p2p.tracker.model.LifecycleEvent
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.RuntimeSurface
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.TrackerMode
import com.dmarket.p2p.tracker.model.marketplace.DealRole
import com.dmarket.p2p.tracker.model.marketplace.Directive
import com.dmarket.p2p.tracker.model.marketplace.DirectiveAction
import com.dmarket.p2p.tracker.model.marketplace.DirectiveOutcome
import com.dmarket.p2p.tracker.model.marketplace.DirectiveStatus
import com.dmarket.p2p.tracker.model.marketplace.HeartbeatResponse
import com.dmarket.p2p.tracker.model.marketplace.TrackedDeal
import com.dmarket.p2p.tracker.model.steam.TradeDraft
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
import com.dmarket.p2p.tracker.support.fakeSteamCredential
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The per-partner `create_offer` chain flow: creates grouped by counterparty, one at a time within a
 * counterparty, chains independent of each other, and a chain abandoned at its first failure.
 *
 * The scenario throughout is the one from the session log that motivated it — a backend leasing far more
 * creates for one partner than Steam's outstanding-offer cap allows.
 */
class CreateChainExecutionTest {

    private val alice = SteamId("76561199497281579")
    private val bob = SteamId("76561198000000002")
    private val carol = SteamId("76561190000000009")

    /** Steam's verbatim over-quota refusal, as `FetchSteamOfferCreator` reports it. */
    private val overQuota = CreateOfferResult.Failed(
        "Steam create returned HTTP 500: {\"strError\":\"You have sent too many trade offers, or have too " +
            "many outstanding trade offers with a-partner. Please cancel some before sending more.\"}",
    )

    private fun creates(partner: SteamId, n: Int, tag: String) = (1..n).map { i ->
        Directive(
            directiveId = DirectiveId("$tag-$i"),
            action = DirectiveAction.CREATE_OFFER,
            dealId = DealId("deal-$tag-$i"),
            partnerSteamId = partner,
            assetIds = listOf(AssetId("asset-$tag-$i")),
            tradeToken = "trade-token",
            contextId = 2,
        )
    }

    private fun loop(
        marketplace: FakeMarketplaceClient,
        creator: FakeSteamOfferCreator,
        events: RecordingEventObserver = RecordingEventObserver(),
        limits: SteamWriteConfig = SteamWriteConfig(),
        clock: FakeClock = FakeClock(),
        throttle: SteamWriteThrottleStore = PersistedSteamWriteThrottleStore(limits = limits, random = Random(7)),
        progress: TrackerProgressStore = InMemoryTrackerProgressStore(),
    ): TradeTrackerLoop {
        val tunables = TrackerConfig.defaults().copy(steamWrites = limits)
        return TradeTrackerLoop(
            config = LoopConfig("1.0.0", RuntimeSurface.WebChrome, TrackerMode.Background, tunables),
            marketplace = marketplace,
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
            throttle = throttle,
            progress = progress,
            directivesEnabled = true,
            eventObserver = events,
        )
    }

    private fun RecordingEventObserver.deferred() = events.filterIsInstance<LifecycleEvent.SteamWriteDeferred>()

    private fun RecordingEventObserver.stopped() = events.filterIsInstance<LifecycleEvent.CreateChainStopped>()

    // ---- failure isolation --------------------------------------------------------------------------

    @Test
    fun a_refusal_stops_only_that_counterpartys_chain() = runTest {
        // Alice accepts one create then refuses; Bob accepts everything. Alice's remaining creates must be
        // abandoned while Bob's chain finishes — the isolation the whole grouping exists for.
        val creator = FakeSteamOfferCreator(
            resultsByPartner = mapOf(
                alice to listOf(CreateOfferResult.NeedsConfirmation(OfferId("offer-a1")), overQuota),
            ),
        )
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(ttlSeconds = 60, directives = creates(alice, 4, "a") + creates(bob, 3, "b")),
        )
        val events = RecordingEventObserver()

        loop(mp, creator, events).runOnce()

        assertEquals(2, creator.callsFor(alice), "alice: one accepted create, one refusal, then the chain stops")
        assertEquals(3, creator.callsFor(bob), "bob's chain is untouched by alice's refusal")
        val stopped = events.stopped().single()
        assertEquals(alice.value, stopped.partnerSteamId)
        assertEquals(2, stopped.skipped, "a-3 and a-4 were abandoned")
        assertTrue(stopped.reason.contains("too many"), "the stop reason carries Steam's own words")
    }

    @Test
    fun the_abandoned_tail_is_surfaced_and_never_reported_to_the_backend() = runTest {
        val creator = FakeSteamOfferCreator(resultsByPartner = mapOf(alice to listOf(overQuota)))
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(ttlSeconds = 60, directives = creates(alice, 3, "a")),
        )
        val events = RecordingEventObserver()

        loop(mp, creator, events).runOnce()

        assertEquals(1, creator.callsFor(alice))
        // Only the create that actually reached Steam is reported; the two abandoned ones are events only,
        // pending agreement with the backend on how a never-attempted directive should be answered.
        assertEquals(listOf(DirectiveId("a-1")), mp.directiveOutcomes.map { it.directiveId })
        assertEquals(listOf("a-2", "a-3"), events.deferred().map { it.directiveId })
    }

    @Test
    fun a_buyer_role_refusal_does_not_stop_the_chain() = runTest {
        // Per-deal, not per-counterparty: the next deal for the same partner may well be ours to write. The
        // tracking list marks deal-a-1 as ours-to-buy, so only that one create is refused.
        val creator = FakeSteamOfferCreator()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(
                ttlSeconds = 60,
                directives = creates(alice, 3, "a"),
                activeTracking = listOf(
                    TrackedDeal(
                        dealId = DealId("deal-a-1"),
                        role = DealRole.BUYER,
                    ),
                ),
            ),
        )
        val events = RecordingEventObserver()

        loop(mp, creator, events).runOnce()

        assertEquals(2, creator.callsFor(alice), "a-2 and a-3 still ran")
        assertTrue(events.stopped().isEmpty(), "a per-deal refusal is not a chain failure")
    }

    // ---- one in flight per counterparty --------------------------------------------------------------

    @Test
    fun creates_for_one_counterparty_never_overlap_while_other_chains_progress() = runTest {
        // Alice's first create is held open. While it is parked, Bob's and Carol's chains must make progress
        // (proving the chains are independent) and Alice's second create must NOT start (proving hers are
        // sequential). Releasing the gate then lets Alice finish.
        val aliceFirstStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val creator = FakeSteamOfferCreator(
            gate = { partner, index ->
                if (partner == alice && index == 0) {
                    aliceFirstStarted.complete(Unit)
                    release.await()
                }
            },
        )
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(
                ttlSeconds = 60,
                directives = creates(alice, 2, "a") + creates(bob, 2, "b") + creates(carol, 1, "c"),
            ),
        )
        val loop = loop(mp, creator)

        val cycle = async { loop.runOnce() }
        aliceFirstStarted.await()
        testScheduler.advanceUntilIdle()

        assertEquals(1, creator.callsFor(alice), "alice's second create must wait for her first to resolve")
        assertEquals(2, creator.callsFor(bob), "bob's chain runs while alice is parked")
        assertEquals(1, creator.callsFor(carol))

        release.complete(Unit)
        cycle.await()

        assertEquals(2, creator.callsFor(alice))
        assertEquals(
            mapOf(alice to 1, bob to 1, carol to 1),
            creator.maxInFlightPerPartner,
            "never two concurrent creates for one counterparty",
        )
    }

    // ---- caps ---------------------------------------------------------------------------------------

    @Test
    fun the_per_partner_cap_bounds_what_one_counterparty_can_consume() = runTest {
        // The logged session leased 26 creates for one partner against Steam's limit of 5.
        val creator = FakeSteamOfferCreator()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(ttlSeconds = 60, directives = creates(alice, 26, "a")),
        )
        val events = RecordingEventObserver()

        loop(mp, creator, events, limits = SteamWriteConfig(maxCreatesPerCycle = 100)).runOnce()

        assertEquals(5, creator.callsFor(alice))
        assertEquals(21, events.deferred().size)
        assertEquals(5, mp.directiveOutcomes.size, "only real writes are reported")
    }

    @Test
    fun the_cycle_ceiling_is_shared_fairly_across_counterparties() = runTest {
        val creator = FakeSteamOfferCreator()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(
                ttlSeconds = 60,
                directives = creates(alice, 26, "a") + creates(bob, 1, "b") + creates(carol, 1, "c"),
            ),
        )

        loop(mp, creator, limits = SteamWriteConfig(maxCreatesPerCycle = 3)).runOnce()

        assertEquals(1, creator.callsFor(alice), "the long chain does not eat the whole budget")
        assertEquals(1, creator.callsFor(bob))
        assertEquals(1, creator.callsFor(carol))
    }

    // ---- cross-cycle cooldown -----------------------------------------------------------------------

    @Test
    fun a_refused_counterparty_is_not_retried_on_the_next_cycle() = runTest {
        // The backend re-leases a failed directive on every heartbeat (75 s in the observed session). Without
        // the cooldown that meant a doomed Steam POST per heartbeat, forever.
        val creator = FakeSteamOfferCreator(result = overQuota)
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(ttlSeconds = 60, directives = creates(alice, 2, "a")),
        )
        val clock = FakeClock()
        val loop = loop(mp, creator, clock = clock)

        loop.runOnce()
        assertEquals(1, creator.callsFor(alice))

        // Same directives re-leased: the cooldown must swallow them without touching Steam.
        loop.forceHeartbeatNow()
        clock.advance(1.minutes)
        loop.runOnce()
        assertEquals(1, creator.callsFor(alice), "still parked — no second Steam write")

        // Past the cooldown the partner is retried.
        clock.advance(31.minutes)
        loop.forceHeartbeatNow()
        loop.runOnce()
        assertEquals(2, creator.callsFor(alice))
    }

    @Test
    fun a_cooldown_survives_a_worker_respawn() = runTest {
        // Web respawns the worker between most cycles, so the ledger must be read back from storage — an
        // in-memory-only cooldown would be forgotten on nearly every wake.
        val storage = InMemoryDeviceKeyValueStore()
        val limits = SteamWriteConfig()
        val clock = FakeClock()
        val first = FakeSteamOfferCreator(result = overQuota)
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(ttlSeconds = 60, directives = creates(alice, 1, "a")),
        )
        loop(
            mp,
            first,
            limits = limits,
            clock = clock,
            throttle = PersistedSteamWriteThrottleStore(limits, storage, random = Random(7)),
        ).runOnce()
        assertEquals(1, first.callsFor(alice))

        // A brand-new loop + store over the same storage: the cooldown is still standing.
        val respawned = FakeSteamOfferCreator()
        loop(
            FakeMarketplaceClient(
                heartbeatResponse = HeartbeatResponse(ttlSeconds = 60, directives = creates(alice, 1, "a")),
            ),
            respawned,
            limits = limits,
            clock = clock,
            throttle = PersistedSteamWriteThrottleStore(limits, storage, random = Random(7)),
        ).runOnce()
        assertEquals(0, respawned.callsFor(alice), "the restored cooldown still blocks the create")
    }

    @Test
    fun enough_refusals_across_counterparties_park_the_whole_create_surface() = runTest {
        // Three chains, each refused once: that is the breaker threshold, so the surface — not just those
        // three partners — is parked. This is the backstop for Steam refusing POSTs wholesale, which is what
        // the observed session ended in (78 instant `Failed to fetch` rejections).
        val creator = FakeSteamOfferCreator(result = overQuota)
        val directives = creates(alice, 1, "a") + creates(bob, 1, "b") + creates(carol, 1, "c")
        val mp = FakeMarketplaceClient(heartbeatResponse = HeartbeatResponse(ttlSeconds = 60, directives = directives))
        val clock = FakeClock()
        val limits = SteamWriteConfig(globalBreakerThreshold = 3)
        val throttle = PersistedSteamWriteThrottleStore(limits = limits, random = Random(7))
        val events = RecordingEventObserver()

        loop(mp, creator, events, limits = limits, clock = clock, throttle = throttle).runOnce()
        assertEquals(3, creator.attemptOrder.size)
        assertTrue(throttle.snapshot().globalUntil != null, "the breaker armed")

        // A fourth, previously-unseen counterparty is now blocked too — the point of a surface-wide block.
        val dave = SteamId("76561190000000123")
        val nextCreator = FakeSteamOfferCreator()
        val nextEvents = RecordingEventObserver()
        loop(
            FakeMarketplaceClient(
                heartbeatResponse = HeartbeatResponse(ttlSeconds = 60, directives = creates(dave, 2, "d")),
            ),
            nextCreator,
            nextEvents,
            limits = limits,
            clock = clock,
            throttle = throttle,
        ).runOnce()

        assertEquals(0, nextCreator.callsFor(dave))
        assertEquals(2, nextEvents.deferred().size)
        assertTrue(nextEvents.deferred().all { it.reason.contains("surface") })
    }

    // ---- batched reporting --------------------------------------------------------------------------

    @Test
    fun a_whole_cycle_of_creates_is_reported_in_one_request() = runTest {
        // The endpoint takes a batch, so N creates across M counterparties cost ONE POST, not N.
        val creator = FakeSteamOfferCreator()
        val directives = creates(alice, 3, "a") + creates(bob, 2, "b") + creates(carol, 1, "c")
        val mp = FakeMarketplaceClient(heartbeatResponse = HeartbeatResponse(ttlSeconds = 60, directives = directives))

        loop(mp, creator).runOnce()

        assertEquals(6, creator.attemptOrder.size)
        assertEquals(6, mp.directiveOutcomes.size, "every create is still reported")
        assertEquals(1, mp.directiveCalls, "…in a single /trade-actions request")
        assertEquals(
            directives.map { it.directiveId },
            mp.directiveOutcomes.map { it.directiveId },
            "the batch carries every directive, in chain order",
        )
    }

    @Test
    fun a_failed_create_is_reported_in_the_same_batch_as_the_successful_ones() = runTest {
        val creator = FakeSteamOfferCreator(
            resultsByPartner = mapOf(alice to listOf(CreateOfferResult.NeedsConfirmation(OfferId("offer-a1")), overQuota)),
        )
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(ttlSeconds = 60, directives = creates(alice, 3, "a") + creates(bob, 1, "b")),
        )

        loop(mp, creator).runOnce()

        assertEquals(1, mp.directiveCalls)
        assertEquals(listOf("a-1", "a-2", "b-1"), mp.directiveOutcomes.map { it.directiveId.value })
        assertEquals(
            listOf(DirectiveStatus.NEEDS_CONFIRMATION, DirectiveStatus.FAILED, DirectiveStatus.NEEDS_CONFIRMATION),
            mp.directiveOutcomes.map { it.status },
        )
    }

    @Test
    fun an_outcome_the_backend_left_out_of_the_batch_is_resent_on_the_next_re_lease() = runTest {
        // A batch can be answered partially. An outcome with no result must NOT be treated as accepted: its
        // stored copy has to survive so the re-lease path can re-send it, or the deal parks with the backend
        // never learning the offer exists.
        val creator = FakeSteamOfferCreator()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(ttlSeconds = 60, directives = creates(alice, 2, "a")),
        )
        mp.directiveIdsWithoutAck = setOf(DirectiveId("a-2"))
        val events = RecordingEventObserver()
        val progress = InMemoryTrackerProgressStore()
        val clock = FakeClock()
        val loop = loop(mp, creator, events, clock = clock, progress = progress)

        loop.runOnce()

        assertEquals(setOf(DirectiveId("a-2")), progress.loadDirectiveOutcomes().keys, "the unacked outcome is kept")
        assertTrue(events.events.filterIsInstance<LifecycleEvent.DirectiveReportFailed>().any { it.directiveId == "a-2" })

        // Re-leased: the stored outcome is re-sent rather than the Steam write being re-run.
        mp.directiveIdsWithoutAck = emptySet()
        mp.heartbeatResponse = HeartbeatResponse(ttlSeconds = 60, directives = creates(alice, 2, "a"))
        clock.advance(1.minutes)
        loop.forceHeartbeatNow()
        loop.runOnce()

        assertEquals(2, creator.callsFor(alice), "no create was re-executed")
        assertTrue(progress.loadDirectiveOutcomes().isEmpty(), "the resent outcome is pruned once accepted")
        assertTrue(events.events.filterIsInstance<LifecycleEvent.DirectiveOutcomeResent>().any { it.directiveId == "a-2" })
    }

    @Test
    fun creates_and_cancels_share_the_same_single_request() = runTest {
        // Both Steam write surfaces buffer into the cycle's one /trade-actions call. A heartbeat can lease many
        // cancels as well as many creates, so batching only the creates would have left the other half spamming.
        val creator = FakeSteamOfferCreator()
        val cancels = (1..3).map { i ->
            Directive(
                directiveId = DirectiveId("c-$i"),
                action = DirectiveAction.CANCEL_OFFER,
                dealId = DealId("deal-c-$i"),
                steamOfferId = OfferId("offer-c-$i"),
            )
        }
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(ttlSeconds = 60, directives = creates(alice, 2, "a") + cancels),
        )

        loop(mp, creator).runOnce()

        assertEquals(1, mp.directiveCalls, "creates and cancels report together, not once per surface")
        assertEquals(listOf("a-1", "a-2", "c-1", "c-2", "c-3"), mp.directiveOutcomes.map { it.directiveId.value })
    }

    @Test
    fun re_served_handled_directives_are_resent_in_one_request() = runTest {
        // The backend can re-lease many handled directives in one heartbeat; answering each with its own POST
        // was the other per-directive spam path.
        val progress = InMemoryTrackerProgressStore()
        val directives = creates(alice, 3, "a")
        directives.forEach { directive ->
            progress.recordHandledDirectives(setOf(directive.directiveId))
            progress.recordDirectiveOutcome(
                DirectiveOutcome(
                    directiveId = directive.directiveId,
                    action = DirectiveAction.CREATE_OFFER,
                    status = DirectiveStatus.NEEDS_CONFIRMATION,
                    dealId = directive.dealId,
                    steamOfferId = OfferId("offer-${directive.directiveId.value}"),
                ),
            )
        }
        val creator = FakeSteamOfferCreator()
        val mp = FakeMarketplaceClient(heartbeatResponse = HeartbeatResponse(ttlSeconds = 60, directives = directives))

        loop(mp, creator, progress = progress).runOnce()

        assertEquals(0, creator.attemptOrder.size, "a handled directive is never re-executed")
        assertEquals(1, mp.directiveCalls, "the whole re-served set is resent in one request")
        assertEquals(3, mp.directiveOutcomes.size)
    }

    // ---- host fast path -----------------------------------------------------------------------------

    @Test
    fun the_host_create_path_is_throttled_too_and_reports_nothing() = runTest {
        val creator = FakeSteamOfferCreator(result = overQuota)
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(ttlSeconds = 60, directives = creates(alice, 1, "a")),
        )
        val loop = loop(mp, creator)
        loop.runOnce()
        val reportsAfterCycle = mp.directiveOutcomes.size

        // The FE relay runs outside the cycle with whatever directive_id it holds — it must not walk past a
        // standing cooldown, or the throttle would be trivially bypassable.
        val result = loop.createTrade(
            directiveId = DirectiveId("fe-1"),
            dealId = DealId("deal-fe-1"),
            draft = TradeDraft(alice, listOf(AssetId("asset-fe-1")), "trade-token"),
        )

        val throttled = assertIs<CreateOfferResult.Throttled>(result)
        assertTrue(throttled.retryAfterSeconds >= 1)
        assertEquals(1, creator.callsFor(alice), "no further Steam write")
        assertEquals(reportsAfterCycle, mp.directiveOutcomes.size, "nothing was written, so nothing is reported")
    }
}
