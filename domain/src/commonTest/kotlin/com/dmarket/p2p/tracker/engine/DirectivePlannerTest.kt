package com.dmarket.p2p.tracker.engine

import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.DirectiveId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.marketplace.Directive
import com.dmarket.p2p.tracker.model.marketplace.DirectiveAction
import com.dmarket.p2p.tracker.model.marketplace.HeartbeatResponse
import com.dmarket.p2p.tracker.model.marketplace.TrackedDeal
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusSource
import com.dmarket.p2p.tracker.model.marketplace.WatchTarget
import com.dmarket.p2p.tracker.support.T0
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

// ---- DirectivePlanner ------------------------------------------------------------------

class DirectivePlannerTest {

    private fun createDirective(
        id: String = "dir-1",
        action: DirectiveAction = DirectiveAction.CREATE_OFFER,
        dealId: String? = "deal-1",
        partnerSteamId: String? = "76561198000000002",
        assetIds: List<String> = listOf("asset-1"),
        tradeToken: String? = "token-1",
        steamOfferId: String? = null,
    ) = Directive(
        directiveId = DirectiveId(id),
        action = action,
        dealId = dealId?.let(::DealId),
        partnerSteamId = partnerSteamId?.let(::SteamId),
        assetIds = assetIds.map(::AssetId),
        tradeToken = tradeToken,
        steamOfferId = steamOfferId?.let(::OfferId),
    )

    private fun heartbeat(vararg directives: Directive) =
        HeartbeatResponse(activeTracking = emptyList(), directives = directives.toList(), ttlSeconds = 60)

    @Test
    fun empty_heartbeat_yields_empty_plan() {
        assertTrue(DirectivePlanner.plan(heartbeat(), emptySet()).isEmpty)
    }

    @Test
    fun create_offer_directive_lands_in_creates() {
        val plan = DirectivePlanner.plan(heartbeat(createDirective()), emptySet())
        assertEquals(1, plan.creates.size)
        assertTrue(plan.cancels.isEmpty())
    }

    @Test
    fun already_handled_directive_lands_in_already_handled_not_creates() {
        val directive = createDirective(id = "dir-handled")
        val plan = DirectivePlanner.plan(
            heartbeat(directive),
            handled = setOf(DirectiveId("dir-handled")),
        )
        assertTrue(plan.creates.isEmpty(), "handled directive must never be executed")
        assertEquals(listOf(directive), plan.alreadyHandled)
    }

    @Test
    fun plan_with_only_already_handled_is_empty_to_execute_but_carries_the_directive() {
        val directive = createDirective(id = "dir-handled")
        val plan = DirectivePlanner.plan(heartbeat(directive), handled = setOf(DirectiveId("dir-handled")))
        assertTrue(plan.isEmpty, "isEmpty means nothing to execute")
        assertEquals(1, plan.alreadyHandled.size, "the re-served directive must survive for the re-report pass")
    }

    @Test
    fun handled_malformed_directive_still_lands_in_already_handled() {
        val malformed = createDirective(id = "dir-handled", partnerSteamId = null)
        val plan = DirectivePlanner.plan(heartbeat(malformed), handled = setOf(DirectiveId("dir-handled")))
        assertEquals(1, plan.alreadyHandled.size)
    }

    @Test
    fun handled_unknown_action_directive_still_lands_in_already_handled() {
        val unknown = createDirective(id = "dir-handled", action = DirectiveAction.UNKNOWN)
        val plan = DirectivePlanner.plan(heartbeat(unknown), handled = setOf(DirectiveId("dir-handled")))
        assertEquals(1, plan.alreadyHandled.size)
    }

    @Test
    fun mixed_handled_and_new_directives_partition_correctly() {
        val handled = createDirective(id = "dir-handled")
        val fresh = createDirective(id = "dir-fresh", assetIds = listOf("asset-2"))
        val plan = DirectivePlanner.plan(heartbeat(handled, fresh), handled = setOf(DirectiveId("dir-handled")))
        assertEquals(listOf(fresh), plan.creates)
        assertEquals(listOf(handled), plan.alreadyHandled)
        assertFalse(plan.isEmpty)
    }

    @Test
    fun malformed_create_offer_missing_partner_is_dropped() {
        val malformed = createDirective(partnerSteamId = null)
        assertTrue(DirectivePlanner.plan(heartbeat(malformed), emptySet()).isEmpty)
    }

    @Test
    fun malformed_create_offer_empty_assets_is_dropped() {
        val malformed = createDirective(assetIds = emptyList())
        assertTrue(DirectivePlanner.plan(heartbeat(malformed), emptySet()).isEmpty)
    }

    @Test
    fun malformed_create_offer_lands_in_dropped() {
        val malformed = createDirective(partnerSteamId = null)
        val plan = DirectivePlanner.plan(heartbeat(malformed), emptySet())
        assertTrue(plan.isEmpty, "a dropped directive is not executable")
        assertEquals(malformed, plan.dropped.single().directive)
        assertEquals("create_offer missing partner_steam_id", plan.dropped.single().reason, "reason pinpoints the field")
    }

    @Test
    fun malformed_cancel_offer_lands_in_dropped() {
        val malformed = createDirective(action = DirectiveAction.CANCEL_OFFER, steamOfferId = null)
        val plan = DirectivePlanner.plan(heartbeat(malformed), emptySet())
        assertEquals(malformed, plan.dropped.single().directive)
        assertEquals("cancel_offer missing steam_offer_id", plan.dropped.single().reason)
    }

    @Test
    fun malformed_inventory_scan_lands_in_dropped() {
        val malformed = createDirective(action = DirectiveAction.REPORT_INVENTORY, assetIds = emptyList())
        val plan = DirectivePlanner.plan(heartbeat(malformed), emptySet())
        assertEquals(malformed, plan.dropped.single().directive)
        assertEquals("report_inventory has no asset_ids", plan.dropped.single().reason)
    }

    @Test
    fun unknown_action_is_ignored_not_dropped() {
        // UNKNOWN is forward-compatible: silently ignored, NOT surfaced as a dropped (malformed) directive.
        val unknown = createDirective(action = DirectiveAction.UNKNOWN)
        val plan = DirectivePlanner.plan(heartbeat(unknown), emptySet())
        assertTrue(plan.dropped.isEmpty())
        assertTrue(plan.isEmpty)
    }

    @Test
    fun handled_malformed_directive_is_already_handled_not_dropped() {
        val malformed = createDirective(id = "dir-handled", partnerSteamId = null)
        val plan = DirectivePlanner.plan(heartbeat(malformed), handled = setOf(DirectiveId("dir-handled")))
        assertEquals(listOf(malformed), plan.alreadyHandled)
        assertTrue(plan.dropped.isEmpty(), "a handled directive is re-reported, never re-classified as dropped")
    }

    @Test
    fun cancel_offer_directive_lands_in_cancels() {
        val cancel = createDirective(action = DirectiveAction.CANCEL_OFFER, steamOfferId = "offer-99")
        val plan = DirectivePlanner.plan(heartbeat(cancel), emptySet())
        assertEquals(1, plan.cancels.size)
        assertTrue(plan.creates.isEmpty())
    }

    @Test
    fun cancel_offer_without_steam_offer_id_is_dropped() {
        val malformed = createDirective(action = DirectiveAction.CANCEL_OFFER, steamOfferId = null)
        assertTrue(DirectivePlanner.plan(heartbeat(malformed), emptySet()).isEmpty)
    }

    @Test
    fun inventory_scan_directive_lands_in_inventory_scans() {
        val scan = createDirective(action = DirectiveAction.REPORT_INVENTORY)
        val plan = DirectivePlanner.plan(heartbeat(scan), emptySet())
        assertEquals(1, plan.inventoryScans.size)
    }

    @Test
    fun unknown_action_is_silently_ignored() {
        val unknown = createDirective(action = DirectiveAction.UNKNOWN)
        assertTrue(DirectivePlanner.plan(heartbeat(unknown), emptySet()).isEmpty)
    }

    @Test
    fun multiple_creates_for_different_deals_all_land() {
        val d1 = createDirective(id = "dir-1")
        val d2 = createDirective(id = "dir-2", dealId = "deal-2", assetIds = listOf("asset-2"))
        val plan = DirectivePlanner.plan(heartbeat(d1, d2), emptySet())
        assertEquals(2, plan.creates.size)
    }

    @Test
    fun second_create_for_the_same_deal_in_one_batch_is_dropped() {
        val first = createDirective(id = "dir-1")
        val duplicate = createDirective(id = "dir-2")
        val plan = DirectivePlanner.plan(heartbeat(first, duplicate), emptySet())
        assertEquals(listOf(DirectiveId("dir-1")), plan.creates.map { it.directiveId })
        assertEquals(listOf(DirectiveId("dir-2")), plan.dropped.map { it.directive.directiveId })
        assertTrue(plan.dropped.single().reason.contains("duplicate create_offer"))
    }

    @Test
    fun second_cancel_for_the_same_deal_in_one_batch_is_dropped() {
        val first = createDirective(id = "dir-1", action = DirectiveAction.CANCEL_OFFER, steamOfferId = "offer-1")
        val duplicate = createDirective(id = "dir-2", action = DirectiveAction.CANCEL_OFFER, steamOfferId = "offer-1")
        val plan = DirectivePlanner.plan(heartbeat(first, duplicate), emptySet())
        assertEquals(listOf(DirectiveId("dir-1")), plan.cancels.map { it.directiveId })
        assertTrue(plan.dropped.single().reason.contains("duplicate cancel_offer"))
    }

    @Test
    fun a_create_and_a_cancel_for_one_deal_do_not_block_each_other() {
        val create = createDirective(id = "dir-1")
        val cancel = createDirective(id = "dir-2", action = DirectiveAction.CANCEL_OFFER, steamOfferId = "offer-1")
        val plan = DirectivePlanner.plan(heartbeat(create, cancel), emptySet())
        assertEquals(1, plan.creates.size)
        assertEquals(1, plan.cancels.size)
        assertTrue(plan.dropped.isEmpty())
    }

    /** A malformed create must not consume the deal's slot — the well-formed one behind it still runs. */
    @Test
    fun malformed_create_does_not_drop_the_valid_create_for_the_same_deal() {
        val malformed = createDirective(id = "dir-bad", partnerSteamId = null)
        val valid = createDirective(id = "dir-good")
        val plan = DirectivePlanner.plan(heartbeat(malformed, valid), emptySet())
        assertEquals(listOf(DirectiveId("dir-good")), plan.creates.map { it.directiveId })
        assertEquals(listOf(DirectiveId("dir-bad")), plan.dropped.map { it.directive.directiveId })
    }

    /** Two report_inventory directives are idempotent reads, so the write-dedup must not touch them. */
    @Test
    fun multiple_inventory_scans_in_one_batch_all_land() {
        val s1 = createDirective(id = "dir-1", action = DirectiveAction.REPORT_INVENTORY, dealId = null)
        val s2 = createDirective(id = "dir-2", action = DirectiveAction.REPORT_INVENTORY, dealId = null)
        val plan = DirectivePlanner.plan(heartbeat(s1, s2), emptySet())
        assertEquals(2, plan.inventoryScans.size)
    }
}

// ---- TrackerTick -----------------------------------------------------------------------

class TrackerTickTest {

    /** What the backend actually sends for a transfer-axis deal (`watch=[GetTradeHistory]`). */
    private val historyWatch = setOf(WatchTarget.GET_TRADE_HISTORY)

    private fun tracked(
        dealId: String = "deal-1",
        proofRequired: Boolean = false,
        steamOfferId: String? = null,
        watch: Set<WatchTarget> = setOf(WatchTarget.GET_TRADE_OFFER),
    ) = TrackedDeal(
        dealId = DealId(dealId),
        steamOfferId = steamOfferId?.let(::OfferId),
        watch = watch,
        proofRequired = proofRequired,
    )

    @Test
    fun empty_tracking_yields_empty_plan() {
        assertTrue(TrackerTick.reduce(T0, emptyList(), emptyMap(), emptyMap()).isEmpty)
    }

    @Test
    fun new_offer_code_is_reported() {
        val plan = TrackerTick.reduce(
            T0,
            activeTracking = listOf(tracked()),
            observed = mapOf(DealId("deal-1") to ObservedTrade(offerState = 2)),
            reported = emptyMap(),
        )
        assertEquals(1, plan.reports.size)
        val report = plan.reports.single()
        assertEquals(DealId("deal-1"), report.dealId)
        assertEquals(TradeStatusSource.OFFER, report.source)
        assertEquals(2, report.steamStatusCode)
    }

    @Test
    fun unchanged_code_is_not_re_reported() {
        val plan = TrackerTick.reduce(
            T0,
            activeTracking = listOf(tracked()),
            observed = mapOf(DealId("deal-1") to ObservedTrade(offerState = 2)),
            reported = mapOf(DealId("deal-1") to ReportedStatus(lastOfferCode = 2)),
        )
        assertTrue(plan.isEmpty, "unchanged code must not generate a report")
    }

    @Test
    fun changed_offer_code_is_reported() {
        val plan = TrackerTick.reduce(
            T0,
            activeTracking = listOf(tracked()),
            observed = mapOf(DealId("deal-1") to ObservedTrade(offerState = 3)),
            reported = mapOf(DealId("deal-1") to ReportedStatus(lastOfferCode = 2)),
        )
        assertEquals(1, plan.reports.size)
        assertEquals(3, plan.reports.single().steamStatusCode)
    }

    @Test
    fun history_axis_reported_independently_of_offer_axis() {
        val plan = TrackerTick.reduce(
            T0,
            activeTracking = listOf(tracked()),
            observed = mapOf(DealId("deal-1") to ObservedTrade(offerState = 2, historyStatus = 12)),
            reported = mapOf(DealId("deal-1") to ReportedStatus(lastOfferCode = 2)),
        )
        assertEquals(1, plan.reports.size)
        assertEquals(TradeStatusSource.HISTORY, plan.reports.single().source)
        assertEquals(12, plan.reports.single().steamStatusCode)
    }

    @Test
    fun decisive_offer_code_with_proof_required_emits_proof_intent() {
        val plan = TrackerTick.reduce(
            T0,
            activeTracking = listOf(tracked(proofRequired = true)),
            observed = mapOf(DealId("deal-1") to ObservedTrade(offerState = 3)),
            reported = emptyMap(),
        )
        assertEquals(1, plan.proofIntents.size)
        assertEquals(TradeStatusSource.OFFER, plan.proofIntents.single().source)
        assertEquals(3, plan.proofIntents.single().steamStatusCode)
    }

    @Test
    fun non_decisive_code_does_not_emit_proof_intent_even_when_proof_required() {
        val plan = TrackerTick.reduce(
            T0,
            activeTracking = listOf(tracked(proofRequired = true)),
            observed = mapOf(DealId("deal-1") to ObservedTrade(offerState = 1)),
            reported = emptyMap(),
        )
        assertTrue(plan.proofIntents.isEmpty())
        assertEquals(1, plan.reports.size)
    }

    @Test
    fun decisive_code_without_proof_required_does_not_emit_proof_intent() {
        val plan = TrackerTick.reduce(
            T0,
            activeTracking = listOf(tracked(proofRequired = false)),
            observed = mapOf(DealId("deal-1") to ObservedTrade(offerState = 3)),
            reported = emptyMap(),
        )
        assertTrue(plan.proofIntents.isEmpty())
        assertEquals(1, plan.reports.size)
    }

    @Test
    fun history_12_with_proof_required_emits_proof_intent() {
        val plan = TrackerTick.reduce(
            T0,
            activeTracking = listOf(tracked(proofRequired = true, watch = historyWatch)),
            observed = mapOf(DealId("deal-1") to ObservedTrade(historyStatus = 12)),
            reported = emptyMap(),
        )
        assertEquals(1, plan.proofIntents.size)
        assertEquals(TradeStatusSource.HISTORY, plan.proofIntents.single().source)
    }

    @Test
    fun history_3_with_proof_required_emits_proof_intent() {
        // The regression this exists for: history 3 is the PAYOUT place. The backend holds the deal at
        // AwaitingTerminal until a positive Complete(3) clears the protection window, and with proofs
        // enforced it will not take that transition unsigned — so a client that raises no intent here freezes
        // every proof-enforced deal at payout, permanently, with the funds locked.
        val plan = TrackerTick.reduce(
            T0,
            activeTracking = listOf(tracked(proofRequired = true, watch = historyWatch)),
            observed = mapOf(DealId("deal-1") to ObservedTrade(historyStatus = 3)),
            reported = emptyMap(),
        )
        assertEquals(1, plan.proofIntents.size)
        assertEquals(TradeStatusSource.HISTORY, plan.proofIntents.single().source)
        assertEquals(3, plan.proofIntents.single().steamStatusCode)
    }

    @Test
    fun a_history_watched_deal_raises_no_offer_proof_but_still_reports_the_offer_code() {
        // THE REGRESSION. The account-wide list read observes an offer code for every tracked deal, so a
        // deal the backend watches on the transfer axis sees decisive offer codes too. Raising a proof for
        // one produced an offer presentation the backend rejects as the wrong read, and proof-before-report
        // then withheld this very report behind that rejection — so the deal stalled on an axis nobody asked
        // to prove. Observed live on 2026-09-03: `watch=[GetTradeHistory]`, offer code 3, verdict
        // "the proof covers the sent-offers list, not the trade history".
        //
        // The report is the half that must survive: `TrackedDeal.lastOfferCode` is the backend's own
        // offer-axis baseline, so it consumes offer reports for deals it watches elsewhere.
        val plan = TrackerTick.reduce(
            T0,
            activeTracking = listOf(tracked(proofRequired = true, watch = historyWatch)),
            observed = mapOf(DealId("deal-1") to ObservedTrade(offerState = 3)),
            reported = emptyMap(),
        )
        assertTrue(plan.proofIntents.isEmpty(), "an unwatched axis must not be proven")
        assertEquals(1, plan.reports.size)
        assertEquals(TradeStatusSource.OFFER, plan.reports.single().source)
        assertEquals(3, plan.reports.single().steamStatusCode)
    }

    @Test
    fun an_offer_watched_deal_raises_no_history_proof() {
        // The mirror of the above. Unreachable through the loop today — the history read is itself
        // watch-gated on the polling path, so an offer-watched deal has no `historyStatus` to observe — but
        // asserted here so the two axes are held to one rule rather than two.
        val plan = TrackerTick.reduce(
            T0,
            activeTracking = listOf(tracked(proofRequired = true, watch = setOf(WatchTarget.GET_TRADE_OFFER))),
            observed = mapOf(DealId("deal-1") to ObservedTrade(historyStatus = 3)),
            reported = emptyMap(),
        )
        assertTrue(plan.proofIntents.isEmpty())
        assertEquals(1, plan.reports.size)
    }

    @Test
    fun a_GetTradeStatus_watch_counts_as_the_transfer_axis() {
        // Both spellings have been observed on the wire for the same axis; neither may be the one that
        // silently stops proving.
        val plan = TrackerTick.reduce(
            T0,
            activeTracking = listOf(tracked(proofRequired = true, watch = setOf(WatchTarget.GET_TRADE_STATUS))),
            observed = mapOf(DealId("deal-1") to ObservedTrade(historyStatus = 3)),
            reported = emptyMap(),
        )
        assertEquals(1, plan.proofIntents.size)
        assertEquals(TradeStatusSource.HISTORY, plan.proofIntents.single().source)
    }

    @Test
    fun a_deal_naming_no_recognised_axis_still_proves_both() {
        // FAIL-OPEN, and it is the important half of this gate. `watch` defaults to empty and an unrecognised
        // wire name maps to UNKNOWN, so a strict gate would let a backend that stops sending `watch` — or
        // renames a target — silently stop producing every proof, which is a deal that never settles with
        // nothing on either side saying why. Losing one wrong-axis proof is cheap; losing all of them is not.
        for (watch in listOf(emptySet(), setOf(WatchTarget.UNKNOWN))) {
            val offer = TrackerTick.reduce(
                T0,
                activeTracking = listOf(tracked(proofRequired = true, watch = watch)),
                observed = mapOf(DealId("deal-1") to ObservedTrade(offerState = 3)),
                reported = emptyMap(),
            )
            assertEquals(1, offer.proofIntents.size, "offer axis, watch=$watch")
            val history = TrackerTick.reduce(
                T0,
                activeTracking = listOf(tracked(proofRequired = true, watch = watch)),
                observed = mapOf(DealId("deal-1") to ObservedTrade(historyStatus = 3)),
                reported = emptyMap(),
            )
            assertEquals(1, history.proofIntents.size, "history axis, watch=$watch")
        }
    }

    @Test
    fun an_unrecognised_target_alongside_a_known_one_does_not_reopen_the_gate() {
        // The fail-open is "names NOTHING we recognise", not "names something we don't". A deal that says
        // GetTradeHistory plus a name from a newer backend is still a transfer-axis deal, and its offer axis
        // stays ungoverned by the unknown entry.
        val plan = TrackerTick.reduce(
            T0,
            activeTracking = listOf(
                tracked(proofRequired = true, watch = setOf(WatchTarget.GET_TRADE_HISTORY, WatchTarget.UNKNOWN)),
            ),
            observed = mapOf(DealId("deal-1") to ObservedTrade(offerState = 3, historyStatus = 3)),
            reported = emptyMap(),
        )
        assertEquals(1, plan.proofIntents.size)
        assertEquals(TradeStatusSource.HISTORY, plan.proofIntents.single().source)
    }

    @Test
    fun the_buyers_two_refusals_emit_proof_intents() {
        // 7 Declined and 4 Countered are the buyer's two ways of refusing, and the backend gates both under
        // one place (`offer_declined`) — so both need a proof or the refusal does not move the deal.
        for (code in listOf(4, 7)) {
            val plan = TrackerTick.reduce(
                T0,
                activeTracking = listOf(tracked(proofRequired = true)),
                observed = mapOf(DealId("deal-1") to ObservedTrade(offerState = code)),
                reported = emptyMap(),
            )
            assertEquals(1, plan.proofIntents.size, "offer $code must raise an intent")
            assertEquals(code, plan.proofIntents.single().steamStatusCode, "offer $code")
        }
    }

    @Test
    fun deal_not_in_observed_produces_nothing() {
        val plan = TrackerTick.reduce(
            T0,
            activeTracking = listOf(tracked("deal-1")),
            observed = mapOf(DealId("deal-other") to ObservedTrade(offerState = 3)),
            reported = emptyMap(),
        )
        assertTrue(plan.isEmpty)
        assertEquals(0, plan.suppressed, "a deal with no observation is not a suppressed one")
    }

    @Test
    fun suppressed_counts_the_axes_that_matched_the_baseline() {
        // The loop reports this count as its watch verdict, which is what separates "nothing changed" from
        // "we never saw the axis" — two states that used to be the same silent cycle.
        val plan = TrackerTick.reduce(
            T0,
            activeTracking = listOf(tracked()),
            observed = mapOf(DealId("deal-1") to ObservedTrade(offerState = 3, historyStatus = 12)),
            reported = mapOf(DealId("deal-1") to ReportedStatus(lastOfferCode = 3, lastHistoryCode = 12)),
        )
        assertTrue(plan.isEmpty, "both axes matched the baseline, so there is nothing to send")
        assertEquals(2, plan.suppressed)
    }

    @Test
    fun an_unchanged_rollback_is_re_asserted_once_a_reversal_actor_is_resolved() {
        // The deadlock this breaks: the actor rides ONLY a report, and the code that carries it is already
        // in the baseline — so pure dedup left the backend holding an actor-undecided rollback forever.
        val plan = TrackerTick.reduce(
            T0,
            activeTracking = listOf(tracked()),
            observed = mapOf(
                DealId("deal-1") to ObservedTrade(historyStatus = 12, reversalInitiator = SteamId("76561198000000001")),
            ),
            reported = mapOf(DealId("deal-1") to ReportedStatus(lastHistoryCode = 12)),
        )
        val report = plan.reports.single()
        assertEquals(TradeStatusSource.HISTORY, report.source)
        assertEquals(12, report.steamStatusCode)
        assertEquals(SteamId("76561198000000001"), report.reversalInitiatorSteamId)
        assertEquals(0, plan.suppressed, "a re-asserted rollback is sent, not suppressed")
    }

    @Test
    fun an_unchanged_rollback_without_an_actor_stays_suppressed() {
        // The terminating half: the loop stops resolving an actor once one is on record, so the re-assert
        // above cannot become a per-tick re-report.
        val plan = TrackerTick.reduce(
            T0,
            activeTracking = listOf(tracked()),
            observed = mapOf(DealId("deal-1") to ObservedTrade(historyStatus = 12)),
            reported = mapOf(DealId("deal-1") to ReportedStatus(lastHistoryCode = 12, historyInitiatorReported = true)),
        )
        assertTrue(plan.isEmpty)
        assertEquals(1, plan.suppressed)
    }

    @Test
    fun the_settlement_window_rides_a_changed_history_report() {
        val plan = TrackerTick.reduce(
            T0,
            activeTracking = listOf(tracked()),
            observed = mapOf(
                DealId("deal-1") to ObservedTrade(historyStatus = 3, settlementAt = Instant.fromEpochSeconds(1_786_356_000)),
            ),
            reported = emptyMap(),
        )
        val report = plan.reports.single()
        assertEquals(TradeStatusSource.HISTORY, report.source)
        assertEquals(Instant.fromEpochSeconds(1_786_356_000), report.settlementTime)
    }

    @Test
    fun the_settlement_window_never_rides_the_offer_axis() {
        // The backend accepts it on history reports only — the offer axis has no such field, and sending it
        // there would be a claim about a window Steam never published on that record.
        val plan = TrackerTick.reduce(
            T0,
            activeTracking = listOf(tracked()),
            observed = mapOf(
                DealId("deal-1") to ObservedTrade(offerState = 3, settlementAt = Instant.fromEpochSeconds(1_786_356_000)),
            ),
            reported = emptyMap(),
        )
        val report = plan.reports.single()
        assertEquals(TradeStatusSource.OFFER, report.source)
        assertNull(report.settlementTime)
    }

    @Test
    fun an_unchanged_history_code_is_re_asserted_once_a_settlement_window_appears() {
        // Same deadlock shape as attribution, and it matters more here: Steam CLEARS `time_settlement` on the
        // rollback flip, so a window not sent while the code is unchanged can never be read again.
        val plan = TrackerTick.reduce(
            T0,
            activeTracking = listOf(tracked()),
            observed = mapOf(
                DealId("deal-1") to ObservedTrade(historyStatus = 3, settlementAt = Instant.fromEpochSeconds(1_786_356_000)),
            ),
            reported = mapOf(DealId("deal-1") to ReportedStatus(lastHistoryCode = 3)),
        )
        val report = plan.reports.single()
        assertEquals(Instant.fromEpochSeconds(1_786_356_000), report.settlementTime)
        assertEquals(0, plan.suppressed, "a re-asserted window is sent, not suppressed")
    }

    @Test
    fun an_unchanged_history_code_without_a_window_stays_suppressed() {
        // The terminating half: the loop stops populating the window once one is on record, so the re-assert
        // above cannot become a per-tick re-report.
        val plan = TrackerTick.reduce(
            T0,
            activeTracking = listOf(tracked()),
            observed = mapOf(DealId("deal-1") to ObservedTrade(historyStatus = 3)),
            reported = mapOf(DealId("deal-1") to ReportedStatus(lastHistoryCode = 3, historySettlementReported = true)),
        )
        assertTrue(plan.isEmpty)
        assertEquals(1, plan.suppressed)
    }

    @Test
    fun an_actor_and_a_window_on_one_observation_produce_a_single_report() {
        // Both re-assert the same unchanged code. Two reports for one axis would race the backend's LWW and
        // let one axis be marked accepted off the other's acknowledgement.
        val plan = TrackerTick.reduce(
            T0,
            activeTracking = listOf(tracked()),
            observed = mapOf(
                DealId("deal-1") to ObservedTrade(
                    historyStatus = 12,
                    reversalInitiator = SteamId("76561198000000001"),
                    settlementAt = Instant.fromEpochSeconds(1_786_356_000),
                ),
            ),
            reported = mapOf(DealId("deal-1") to ReportedStatus(lastHistoryCode = 12)),
        )
        val report = plan.reports.single()
        assertEquals(SteamId("76561198000000001"), report.reversalInitiatorSteamId)
        assertEquals(Instant.fromEpochSeconds(1_786_356_000), report.settlementTime)
    }
}

// ---- DecisiveTransitions ---------------------------------------------------------------

class DecisiveTransitionsTest {

    @Test
    fun every_enforced_place_has_a_decisive_code() {
        // One assertion per place the backend enforces, named by the place rather than by the number, so a
        // set that drifts from the contract fails with the place it dropped.
        val places = mapOf(
            "offer_created / offer_confirmed" to (TradeStatusSource.OFFER to 2),
            "offer_accepted" to (TradeStatusSource.OFFER to 3),
            "offer_declined (countered)" to (TradeStatusSource.OFFER to 4),
            "offer_cancelled" to (TradeStatusSource.OFFER to 6),
            "offer_declined" to (TradeStatusSource.OFFER to 7),
            "trade_completed" to (TradeStatusSource.HISTORY to 3),
            "trade_reversed" to (TradeStatusSource.HISTORY to 12),
        )
        for ((place, axis) in places) {
            assertTrue(DecisiveTransitions.isDecisive(axis.first, axis.second), "$place (${axis.first} ${axis.second})")
        }
    }

    @Test
    fun history_3_is_decisive_because_it_is_the_payout_place() {
        // Regression for a freeze, not a taste: the backend holds the deal at AwaitingTerminal until a
        // positive history Complete(3) clears the protection window, and with proofs enforced it will not
        // take that transition unsigned. Omitting it froze every proof-enforced deal at payout, permanently.
        assertTrue(DecisiveTransitions.isDecisive(TradeStatusSource.HISTORY, 3))
    }

    @Test
    fun other_offer_codes_are_not_decisive() {
        // 8 InvalidItems and 10 CanceledBySecondFactor are out by the host's ruling, not by oversight;
        // 9 is the pre-confirmation state the offer passes THROUGH on its way to 2.
        for (code in listOf(0, 1, 5, 8, 9, 10, 11)) {
            assertFalse(DecisiveTransitions.isDecisive(TradeStatusSource.OFFER, code), "offer code $code")
        }
    }

    @Test
    fun other_history_codes_are_not_decisive() {
        for (code in listOf(0, 1, 2, 11, 13)) {
            assertFalse(DecisiveTransitions.isDecisive(TradeStatusSource.HISTORY, code), "history code $code")
        }
    }
}
