package com.dmarket.p2p.tracker.engine

import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusSource
import com.dmarket.p2p.tracker.support.T0
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class ProofMintPolicyTest {
    private val intent = ProofIntent(DealId("d1"), TradeStatusSource.OFFER, 2)
    private val other = ProofIntent(DealId("d2"), TradeStatusSource.OFFER, 2)
    private val ttlMs = 3_600_000

    private fun decide(
        now: Instant = T0,
        refused: Set<ProofIntent> = emptySet(),
        accepted: Map<ProofIntent, Instant> = emptyMap(),
        acceptedTtlMs: Int = ttlMs,
        parkedUntil: Instant? = null,
        minted: Boolean = true,
        deadline: Instant? = null,
    ) = ProofMintPolicy.decide(intent, now, refused, accepted, acceptedTtlMs, parkedUntil, minted, deadline)

    @Test
    fun nothing_in_the_way_mints() {
        assertEquals(ProofMintVerdict.Mint, decide(deadline = T0 + 1.minutes))
    }

    @Test
    fun each_reason_is_recognised_on_its_own() {
        assertEquals(
            ProofMintVerdict.Skip(ProofSkipReason.ALREADY_REFUSED),
            decide(refused = setOf(intent)),
        )
        assertEquals(
            ProofMintVerdict.Skip(ProofSkipReason.ALREADY_ACCEPTED),
            decide(accepted = mapOf(intent to T0)),
        )
        assertEquals(
            ProofMintVerdict.Skip(ProofSkipReason.PROVER_PARKED, retryAfterSeconds = 90),
            decide(parkedUntil = T0 + 90.seconds),
        )
        assertEquals(
            ProofMintVerdict.Skip(ProofSkipReason.BUDGET_SPENT),
            decide(deadline = T0),
        )
    }

    @Test
    fun a_settled_backend_answer_outranks_every_spending_gate() {
        // The regression this policy exists to make unrepresentable. With BOTH spending gates closed, a
        // transition the backend has already answered must still be reported by its own reason — the refusal
        // withholds the report, the acceptance releases it, and neither has anything to do with whether this
        // client may spend an MPC session right now.
        val everythingClosed = mapOf(
            "refused" to decide(refused = setOf(intent), parkedUntil = T0 + 1.minutes, deadline = T0),
            "accepted" to decide(accepted = mapOf(intent to T0), parkedUntil = T0 + 1.minutes, deadline = T0),
        )
        assertEquals(ProofMintVerdict.Skip(ProofSkipReason.ALREADY_REFUSED), everythingClosed.getValue("refused"))
        assertEquals(ProofMintVerdict.Skip(ProofSkipReason.ALREADY_ACCEPTED), everythingClosed.getValue("accepted"))
    }

    @Test
    fun a_refusal_outranks_an_acceptance_of_the_same_transition() {
        // Both latched is only reachable across a respawn (the refusal latch is in-memory, the acceptance is
        // persisted), and the backend's *latest* word is the refusal — so the report must stay withheld.
        val verdict = decide(refused = setOf(intent), accepted = mapOf(intent to T0))
        assertEquals(ProofMintVerdict.Skip(ProofSkipReason.ALREADY_REFUSED), verdict)
        assertFalse((verdict as ProofMintVerdict.Skip).reason.corroborated)
    }

    @Test
    fun the_park_outranks_the_budget() {
        // Both are spending gates, but the park is the longer-lived and more informative answer, and it is the
        // one that carries a deadline a host can render.
        val verdict = decide(parkedUntil = T0 + 45.seconds, deadline = T0)
        assertEquals(ProofMintVerdict.Skip(ProofSkipReason.PROVER_PARKED, retryAfterSeconds = 45), verdict)
    }

    @Test
    fun only_an_acceptance_leaves_the_transition_corroborated() {
        // The property the report gate reads. Derived from the reason so the two cannot drift.
        assertTrue(ProofSkipReason.ALREADY_ACCEPTED.corroborated)
        for (reason in ProofSkipReason.entries - ProofSkipReason.ALREADY_ACCEPTED) {
            assertFalse(reason.corroborated, "$reason must not release a report")
        }
    }

    @Test
    fun an_acceptance_of_a_different_transition_is_not_this_one() {
        // Keyed on the whole intent — deal, axis and code — so a later decisive code never inherits an
        // earlier one's verdict.
        assertEquals(ProofMintVerdict.Mint, decide(accepted = mapOf(other to T0)))
    }

    @Test
    fun an_acceptance_expires_with_its_reuse_window() {
        val accepted = mapOf(intent to T0)
        assertEquals(ProofMintVerdict.Skip(ProofSkipReason.ALREADY_ACCEPTED), decide(now = T0 + 59.minutes, accepted = accepted))
        assertEquals(ProofMintVerdict.Mint, decide(now = T0 + 1.hours, accepted = accepted))
    }

    @Test
    fun a_zero_reuse_window_disables_the_acceptance_skip() {
        // The documented kill switch, and it needs no branch of its own: a non-negative age is never < 0.
        assertEquals(ProofMintVerdict.Mint, decide(accepted = mapOf(intent to T0), acceptedTtlMs = 0))
    }

    @Test
    fun a_backwards_clock_expires_an_acceptance_rather_than_freezing_it() {
        // A host time correction must only ever cost a proof, never suppress one forever.
        assertEquals(ProofMintVerdict.Mint, decide(now = T0 - 1.minutes, accepted = mapOf(intent to T0)))
    }

    @Test
    fun an_elapsed_park_and_an_absent_deadline_do_not_block() {
        // The caller passes `null` once the cooldown has expired (that is the store's job), and a client with
        // no heartbeat scheduled yet has no budget to protect.
        assertEquals(ProofMintVerdict.Mint, decide(parkedUntil = null, deadline = null))
    }

    @Test
    fun a_deadline_exactly_now_is_spent_and_one_millisecond_later_is_not() {
        assertEquals(ProofMintVerdict.Skip(ProofSkipReason.BUDGET_SPENT), decide(deadline = T0))
        assertEquals(ProofMintVerdict.Mint, decide(deadline = T0 + 1.milliseconds))
    }

    @Test
    fun the_budget_never_refuses_the_first_proof_of_a_cycle() {
        // The regression from dev 2026-08-26: the wake landed 107 ms before the heartbeat was due, so the cycle
        // was watch-only, and the 287 ms deal-watch read put the clock past the deadline before a single proof
        // had been minted. Every due proof was then refused as "budget spent" — a cycle that spent nothing and
        // achieved nothing. The budget bounds the CHAIN; it cannot refuse the thing it is budgeting for.
        assertEquals(ProofMintVerdict.Mint, decide(minted = false, deadline = T0))
        assertEquals(ProofMintVerdict.Mint, decide(minted = false, deadline = T0 - 10.minutes))
        // …and it does bound the chain once one has been spent.
        assertEquals(ProofMintVerdict.Skip(ProofSkipReason.BUDGET_SPENT), decide(minted = true, deadline = T0))
    }

    @Test
    fun the_other_three_reasons_apply_to_the_first_proof_too() {
        // Only the budget is conditional on having spent one. A settled backend answer and a parked prover are
        // just as true for the first intent as for the fifth.
        assertEquals(ProofMintVerdict.Skip(ProofSkipReason.ALREADY_REFUSED), decide(minted = false, refused = setOf(intent)))
        assertEquals(ProofMintVerdict.Skip(ProofSkipReason.ALREADY_ACCEPTED), decide(minted = false, accepted = mapOf(intent to T0)))
        assertEquals(
            ProofMintVerdict.Skip(ProofSkipReason.PROVER_PARKED, retryAfterSeconds = 30),
            decide(minted = false, parkedUntil = T0 + 30.seconds),
        )
    }

    @Test
    fun a_park_deadline_never_reports_zero_seconds() {
        // "retry after 0s" reads as "retry now"; the floor is CooldownLadder's, shared with the throttles.
        val verdict = decide(parkedUntil = T0 + 200.milliseconds) as ProofMintVerdict.Skip
        assertEquals(1, verdict.retryAfterSeconds)
    }

    @Test
    fun only_the_two_cooldown_reasons_carry_a_deadline() {
        // Exhaustive over the enum on purpose: a new reason does not compile until it is produced here, which
        // is what stops one being added without a caller that can reach it.
        val withDeadline = ProofSkipReason.entries.filter { reason ->
            val verdict = when (reason) {
                ProofSkipReason.ALREADY_REFUSED -> decide(refused = setOf(intent))
                ProofSkipReason.ALREADY_ACCEPTED -> decide(accepted = mapOf(intent to T0))
                ProofSkipReason.FRESHNESS_RETRY_PENDING -> decideFreshness(progress = laddered(T0 + 30.seconds))
                ProofSkipReason.PROVER_PARKED -> decide(parkedUntil = T0 + 30.seconds)
                ProofSkipReason.BUDGET_SPENT -> decide(deadline = T0)
            }
            (verdict as ProofMintVerdict.Skip).retryAfterSeconds != null
        }
        assertEquals(listOf(ProofSkipReason.FRESHNESS_RETRY_PENDING, ProofSkipReason.PROVER_PARKED), withDeadline)
    }

    // ---- decideFreshness (DMA-280) ------------------------------------------------------------

    private fun laddered(retryAt: Instant?, satisfied: Instant? = null) =
        FreshProofProgress(satisfied = satisfied, attempting = T0, attempts = 1, retryAt = retryAt)

    private fun decideFreshness(
        progress: FreshProofProgress? = null,
        now: Instant = T0,
        parkedUntil: Instant? = null,
        minted: Boolean = true,
        deadline: Instant? = null,
    ) = ProofMintPolicy.decideFreshness(progress, now, parkedUntil, minted, deadline)

    @Test
    fun a_first_demand_mints() {
        assertEquals(ProofMintVerdict.Mint, decideFreshness(deadline = T0 + 1.minutes))
    }

    @Test
    fun a_demand_is_not_blocked_by_a_settled_answer_about_a_transition() {
        // The substance of DMA-280 rather than a simplification. An acceptance up to `acceptedProofTtlMs` old
        // satisfying a payout IS the stale-flag release the ticket exists to stop, and a refusal latch that
        // outlived the worker would make the ticket's "retry on the next tick" unimplementable. Neither is
        // even expressible here: both are keyed on a ProofIntent, and a demand does not have one. This test
        // pins that the entry point takes no such argument — it would not compile if it did.
        assertEquals(ProofMintVerdict.Mint, decideFreshness(deadline = T0 + 1.minutes))
    }

    @Test
    fun a_refused_demand_waits_out_its_own_ladder_and_says_how_long() {
        val verdict = decideFreshness(progress = laddered(T0 + 45.seconds))

        assertEquals(ProofMintVerdict.Skip(ProofSkipReason.FRESHNESS_RETRY_PENDING, retryAfterSeconds = 45), verdict)
    }

    @Test
    fun an_elapsed_retry_window_mints_again() {
        // The whole point of a ladder rather than a latch: the demand comes back.
        assertEquals(ProofMintVerdict.Mint, decideFreshness(progress = laddered(T0 + 45.seconds), now = T0 + 45.seconds))
        assertEquals(ProofMintVerdict.Mint, decideFreshness(progress = laddered(T0 + 45.seconds), now = T0 + 1.minutes))
    }

    @Test
    fun a_retry_window_ending_exactly_now_has_elapsed() {
        // Same boundary rule as `parkedUntil` and the write claims.
        assertEquals(ProofMintVerdict.Mint, decideFreshness(progress = laddered(T0), now = T0))
    }

    @Test
    fun a_standing_with_no_ladder_does_not_block() {
        // A deal that has satisfied an earlier mark and has never been refused. `due` decides whether the new
        // mark is owed; this gate has nothing to say about it.
        assertEquals(ProofMintVerdict.Mint, decideFreshness(progress = laddered(retryAt = null, satisfied = T0)))
    }

    @Test
    fun both_spending_gates_still_apply_to_a_demand() {
        // Deliberately not exempted. The park's own incident — six deals, every attempt wedged, one cycle held
        // ~16 minutes and 412 s between heartbeats — would starve the very heartbeat that carries the next
        // mark, and the budget bounds a chain of demands exactly as it bounds a chain of transitions.
        assertEquals(
            ProofMintVerdict.Skip(ProofSkipReason.PROVER_PARKED, retryAfterSeconds = 30),
            decideFreshness(parkedUntil = T0 + 30.seconds),
        )
        assertEquals(ProofMintVerdict.Skip(ProofSkipReason.BUDGET_SPENT), decideFreshness(deadline = T0))
    }

    @Test
    fun the_budget_never_refuses_the_first_demand_of_a_cycle() {
        // Same rule as the transition path, and reachable the same way: the Steam reads run before the gate,
        // so a wake landing milliseconds before the heartbeat is due arrives here already past the deadline.
        assertEquals(ProofMintVerdict.Mint, decideFreshness(minted = false, deadline = T0 - 10.minutes))
    }

    @Test
    fun the_ladder_outranks_both_spending_gates() {
        // It is a settled answer about this mark, not a question of affordability — so it is reported by its
        // own reason even with everything else closed, exactly as the two intent-keyed answers are.
        val verdict = decideFreshness(progress = laddered(T0 + 45.seconds), parkedUntil = T0 + 1.minutes, deadline = T0)

        assertEquals(ProofMintVerdict.Skip(ProofSkipReason.FRESHNESS_RETRY_PENDING, retryAfterSeconds = 45), verdict)
    }

    @Test
    fun a_ladder_deadline_never_reports_zero_seconds() {
        val verdict = decideFreshness(progress = laddered(T0 + 200.milliseconds)) as ProofMintVerdict.Skip

        assertEquals(1, verdict.retryAfterSeconds)
    }

    @Test
    fun a_backwards_clock_expires_a_retry_window_rather_than_freezing_it() {
        // Consistent with the acceptance window above: a host time correction may cost a proof, never suppress
        // one forever. Here a clock that jumped forward past the window simply retries.
        assertEquals(ProofMintVerdict.Mint, decideFreshness(progress = laddered(T0), now = T0 + 1.hours))
    }

    @Test
    fun a_pending_retry_never_releases_a_report() {
        // `corroborated` is what the loop's report gate reads, and only an acceptance may set it. A demand
        // waiting out a ladder has nothing corroborated at all.
        assertFalse(ProofSkipReason.FRESHNESS_RETRY_PENDING.corroborated)
    }
}
