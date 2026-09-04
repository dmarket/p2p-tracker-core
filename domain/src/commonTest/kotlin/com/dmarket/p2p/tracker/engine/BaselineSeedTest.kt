package com.dmarket.p2p.tracker.engine

import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.marketplace.TrackedDeal
import com.dmarket.p2p.tracker.model.marketplace.WatchTarget
import com.dmarket.p2p.tracker.support.T0
import kotlin.test.Test
import kotlin.test.assertEquals

class BaselineSeedTest {
    private fun tracked(id: String, lastOfferCode: Int? = null, proofRequired: Boolean = true) = TrackedDeal(
        dealId = DealId(id),
        steamOfferId = OfferId("offer-$id"),
        watch = setOf(WatchTarget.GET_TRADE_OFFER),
        proofRequired = proofRequired,
        lastOfferCode = lastOfferCode,
    )

    @Test
    fun a_backend_code_fills_a_gap_the_client_has_no_record_of() {
        // The whole point: a fresh install knows nothing, so without this every live transition is re-detected
        // and — on a proofRequired deal — re-proved at a full MPC session each.
        val merged = BaselineSeed.merge(listOf(tracked("d1", lastOfferCode = 6)), emptyMap())

        assertEquals(6, merged.getValue(DealId("d1")).lastOfferCode)
    }

    @Test
    fun the_clients_own_record_always_wins() {
        // A stored entry is this device's record of an ACCEPTED report, and it can legitimately be ahead of
        // what the heartbeat echoed back — a report accepted after that response was built. Overwriting it
        // would re-open a transition this device has already closed.
        val stored = mapOf(DealId("d1") to ReportedStatus(lastOfferCode = 3))
        val merged = BaselineSeed.merge(listOf(tracked("d1", lastOfferCode = 2)), stored)

        assertEquals(3, merged.getValue(DealId("d1")).lastOfferCode)
    }

    @Test
    fun seeding_preserves_the_history_axis_of_the_same_deal() {
        // The axes are reported independently, so a deal can hold a history baseline while its offer axis is
        // unseen. Rebuilding the entry instead of copying it would silently drop the history side — including
        // the two sticky flags that end the attribution retry loop.
        val stored = mapOf(
            DealId("d1") to ReportedStatus(
                lastHistoryCode = 12,
                historyInitiatorReported = true,
                historySettlementReported = true,
            ),
        )
        val merged = BaselineSeed.merge(listOf(tracked("d1", lastOfferCode = 6)), stored).getValue(DealId("d1"))

        assertEquals(6, merged.lastOfferCode)
        assertEquals(12, merged.lastHistoryCode)
        assertEquals(true, merged.historyInitiatorReported)
        assertEquals(true, merged.historySettlementReported)
    }

    @Test
    fun a_backend_with_nothing_to_say_changes_nothing_at_all() {
        // `null` is the default on the wire, so every backend that does not send the field lands here — and
        // must be exactly today's behaviour.
        val stored = mapOf(DealId("d1") to ReportedStatus(lastOfferCode = 2))
        assertEquals(stored, BaselineSeed.merge(listOf(tracked("d1")), stored))
        assertEquals(stored, BaselineSeed.merge(emptyList(), stored))
    }

    @Test
    fun seeding_is_idempotent_across_cycles() {
        // The same heartbeat value is folded in on every watch pass, and the merge is in-memory only — so it
        // has to converge rather than drift.
        val tracking = listOf(tracked("d1", lastOfferCode = 6))
        val once = BaselineSeed.merge(tracking, emptyMap())

        assertEquals(once, BaselineSeed.merge(tracking, once))
    }

    @Test
    fun only_the_deals_the_backend_spoke_about_are_touched() {
        val stored = mapOf(DealId("d2") to ReportedStatus(lastOfferCode = 9))
        val merged = BaselineSeed.merge(listOf(tracked("d1", lastOfferCode = 6), tracked("d2")), stored)

        assertEquals(6, merged.getValue(DealId("d1")).lastOfferCode)
        assertEquals(9, merged.getValue(DealId("d2")).lastOfferCode, "d2 carried no backend code — leave it alone")
    }

    @Test
    fun a_seeded_code_suppresses_the_report_and_its_proof() {
        // End-to-end through the reducer, which is the behaviour the field exists to buy: the backend already
        // has offer code 6, Steam still shows 6, so there is nothing to report and nothing to prove.
        val tracking = listOf(tracked("d1", lastOfferCode = 6))
        val observed = mapOf(DealId("d1") to ObservedTrade(offerState = 6))

        val cold = TrackerTick.reduce(T0, tracking, observed, emptyMap())
        assertEquals(1, cold.reports.size, "without the seed this is a fresh report")
        assertEquals(1, cold.proofIntents.size, "…and a fresh MPC session")

        val seeded = TrackerTick.reduce(T0, tracking, observed, BaselineSeed.merge(tracking, emptyMap()))
        assertEquals(emptyList(), seeded.reports)
        assertEquals(emptyList(), seeded.proofIntents)
    }

    @Test
    fun a_seed_behind_reality_still_reports_the_newer_code() {
        // The safe direction. `reduce` compares by inequality and the backend applies LWW over forward
        // transitions, so a stale seed costs one report the backend discards — never a missed transition.
        val tracking = listOf(tracked("d1", lastOfferCode = 2))
        val observed = mapOf(DealId("d1") to ObservedTrade(offerState = 6))

        val plan = TrackerTick.reduce(T0, tracking, observed, BaselineSeed.merge(tracking, emptyMap()))

        assertEquals(1, plan.reports.size)
        assertEquals(6, plan.reports.single().steamStatusCode)
    }
}
