package com.dmarket.p2p.tracker.engine

import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.TradeId
import com.dmarket.p2p.tracker.model.marketplace.TrackedDeal
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusSource
import com.dmarket.p2p.tracker.model.marketplace.WatchTarget
import com.dmarket.p2p.tracker.support.T0
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class ProofFreshnessTest {
    private val mark = T0 + 1.hours
    private val later = mark + 1.minutes

    private fun tracked(
        id: String = "d1",
        tradeId: String? = "744935517744884653",
        proveAfter: kotlin.time.Instant? = null,
        proofRequired: Boolean = true,
        watch: Set<WatchTarget> = setOf(WatchTarget.GET_TRADE_STATUS),
    ) = TrackedDeal(
        dealId = DealId(id),
        steamOfferId = OfferId("offer-$id"),
        watch = watch,
        proofRequired = proofRequired,
        steamTradeId = tradeId?.let(::TradeId),
        proveAfter = proveAfter,
    )

    // ---- due ----------------------------------------------------------------------------------

    @Test
    fun no_mark_is_no_demand() {
        // The whole feature is inert until the backend stamps one — that is what lets it ship ahead of the
        // gate image without changing any behaviour.
        assertEquals(FreshProofPlan.EMPTY, ProofFreshness.due(listOf(tracked()), emptyMap()))
    }

    @Test
    fun a_mark_the_device_has_not_satisfied_is_due_and_names_its_trade() {
        val plan = ProofFreshness.due(listOf(tracked(proveAfter = mark)), emptyMap())

        assertEquals(listOf(FreshProofDemand(DealId("d1"), TradeId("744935517744884653"), mark)), plan.demands)
        assertEquals(emptyList(), plan.unbindable)
    }

    @Test
    fun the_same_mark_re_presented_is_not_due_again() {
        // The load-bearing case, and it is the COMMON one rather than an edge: the tracking list is re-supplied
        // by every heartbeat and re-read by every watch-only wake, so a non-strict comparison would turn one
        // demand into one full MPC session per wake for the life of the deal.
        val progress = mapOf(DealId("d1") to FreshProofProgress(satisfied = mark))

        assertEquals(FreshProofPlan.EMPTY, ProofFreshness.due(listOf(tracked(proveAfter = mark)), progress))
    }

    @Test
    fun a_greater_mark_is_due_even_though_an_earlier_one_was_satisfied() {
        // The backend's manual re-check — the only path that produces a greater mark, since a stamped one is
        // republished byte-identical until it is answered.
        val progress = mapOf(DealId("d1") to FreshProofProgress(satisfied = mark))
        val plan = ProofFreshness.due(listOf(tracked(proveAfter = later)), progress)

        assertEquals(listOf(later), plan.demands.map { it.proveAfter })
    }

    @Test
    fun a_mark_that_moved_backwards_is_skipped() {
        // Monotone only. The client-side cost is a payout the backend does not release, and it self-heals the
        // moment a greater mark is stamped; the alternative is a flapping backend replica becoming per-wake
        // MPC traffic. That the backend cannot then release on a stale proof is ITS attestation-time check,
        // not something asserted here.
        val progress = mapOf(DealId("d1") to FreshProofProgress(satisfied = later))

        assertEquals(FreshProofPlan.EMPTY, ProofFreshness.due(listOf(tracked(proveAfter = mark)), progress))
    }

    @Test
    fun a_mark_with_no_trade_id_is_unbindable_rather_than_silently_dropped() {
        // It cannot be served — the proven read addresses one trade by id — but it is exactly the state a
        // stranded payout is investigated from, and it is invisible everywhere else.
        val plan = ProofFreshness.due(listOf(tracked(tradeId = null, proveAfter = mark)), emptyMap())

        assertEquals(emptyList(), plan.demands)
        assertEquals(listOf(DealId("d1")), plan.unbindable)
        assertTrue(!plan.isEmpty, "an unbindable mark must survive the caller's empty-plan return")
    }

    @Test
    fun a_demand_is_raised_without_proof_required_and_without_a_trade_axis_in_the_watch_set() {
        // Deliberately un-gated on both. The mark IS the request, and the same backend sets `proof_required`
        // and populates `watch` — so a flag or a spelling lagging the mark by one deploy would park a
        // settlement indefinitely, whereas acting on a spurious mark costs one MPC session.
        val plan = ProofFreshness.due(
            listOf(tracked(proofRequired = false, watch = setOf(WatchTarget.GET_TRADE_OFFER), proveAfter = mark)),
            emptyMap(),
        )

        assertEquals(1, plan.demands.size)
    }

    @Test
    fun each_deals_mark_is_judged_against_its_own_standing() {
        val tracking = listOf(
            tracked("d1", proveAfter = mark),
            tracked("d2", proveAfter = mark),
            tracked("d3", tradeId = null, proveAfter = mark),
            tracked("d4"),
        )
        val progress = mapOf(DealId("d1") to FreshProofProgress(satisfied = mark))
        val plan = ProofFreshness.due(tracking, progress)

        assertEquals(listOf(DealId("d2")), plan.demands.map { it.dealId })
        assertEquals(listOf(DealId("d3")), plan.unbindable)
    }

    @Test
    fun the_demand_axis_is_the_history_axis() {
        // Fixed by construction rather than derived from `watch`: `steam_trade_id` addresses GetTradeStatus
        // and nothing else, and `WatchTarget.fromWire` answers UNKNOWN for a spelling it does not recognise —
        // so deriving it would let one backend typo disable the feature in silence.
        assertEquals(TradeStatusSource.HISTORY, FreshProofDemand.AXIS)
    }

    // ---- refused / satisfied ------------------------------------------------------------------

    private val demand = FreshProofDemand(DealId("d1"), TradeId("744935517744884653"), mark)

    @Test
    fun a_refusal_leaves_the_mark_unsatisfied_and_arms_the_first_rung() {
        // "Do not mark satisfied, retry" — for EVERY reason, with no dependency on the backend's wording.
        val next = ProofFreshness.refused(null, demand, T0, cooldownBaseMs = 60_000, cooldownMaxMs = 1_800_000, random = Random(1))

        assertNull(next.satisfied)
        assertEquals(mark, next.attempting)
        assertEquals(1, next.attempts)
        assertTrue(next.retryAt!! > T0, "a refusal must arm a retry window, or the bound does not bound")
    }

    @Test
    fun a_second_refusal_of_the_same_mark_climbs_the_ladder() {
        val first = ProofFreshness.refused(null, demand, T0, 60_000, 1_800_000, Random(1))
        val second = ProofFreshness.refused(first, demand, T0, 60_000, 1_800_000, Random(1))

        assertEquals(2, second.attempts)
        assertTrue(second.retryAt!! > first.retryAt!!, "rung 2 must wait longer than rung 1")
    }

    @Test
    fun a_re_stamped_mark_starts_its_own_ladder() {
        // A fresh demand is a fresh question. Inheriting the abandoned mark's rung would hold a newly-stamped
        // one off for as long as the previous episode had earned — which is a payout delayed for a reason
        // that no longer exists.
        val exhausted = FreshProofProgress(attempting = mark, attempts = 7, retryAt = T0 + 20.minutes)
        val next = ProofFreshness.refused(exhausted, demand.copy(proveAfter = later), T0, 60_000, 1_800_000, Random(1))

        assertEquals(1, next.attempts)
        assertEquals(later, next.attempting)
    }

    @Test
    fun a_refusal_preserves_an_earlier_satisfied_mark() {
        // The latch may only move forward: an episode that fails must not re-open a mark this device closed.
        val standing = FreshProofProgress(satisfied = mark)
        val next = ProofFreshness.refused(standing, demand.copy(proveAfter = later), T0, 60_000, 1_800_000, Random(1))

        assertEquals(mark, next.satisfied)
    }

    @Test
    fun satisfaction_records_the_backends_mark_and_drops_the_ladder() {
        // The mark that was answered, never a local clock reading — so `due`'s comparison is between two
        // backend-issued instants and cannot be moved by a device whose clock is wrong. And a fresh value
        // rather than a copy: a stale `attempting` would let the NEXT mark's first refusal inherit this
        // episode's exhausted rung.
        val laddered = FreshProofProgress(attempting = mark, attempts = 9, retryAt = T0 + 30.minutes)
        val next = ProofFreshness.satisfied(laddered, demand)

        assertEquals(mark, next.satisfied)
        assertNull(next.attempting)
        assertEquals(0, next.attempts)
        assertNull(next.retryAt)
    }

    @Test
    fun satisfaction_never_walks_the_latch_backwards() {
        // Reachable if a heartbeat carrying an older mark is answered after a newer one was already closed.
        val standing = FreshProofProgress(satisfied = later)

        assertEquals(later, ProofFreshness.satisfied(standing, demand).satisfied)
    }

    @Test
    fun a_satisfied_mark_ends_the_demand() {
        // The round trip the exact-proof-count acceptance rests on: one demand, one proof, then silence.
        val standing = ProofFreshness.satisfied(null, demand)

        assertEquals(FreshProofPlan.EMPTY, ProofFreshness.due(listOf(tracked(proveAfter = mark)), mapOf(DealId("d1") to standing)))
    }
}
