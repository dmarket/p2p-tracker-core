package com.dmarket.p2p.tracker.engine

import com.dmarket.p2p.tracker.policy.CooldownLadder
import kotlin.time.Instant

/**
 * Why a due proof was not minted. The reasons are ordered by the loop's evaluation order, which is
 * load-bearing — see [ProofMintPolicy.decide].
 *
 * [message] is the fixed client-side text emitted as `LifecycleEvent.ProofSuppressed.reason`. It lives here
 * rather than in the loop because it names the *decision*, and the decision is made here: a reason string
 * defined next to the emit site drifts from the branch that produces it.
 */
enum class ProofSkipReason(val message: String) {
    /**
     * The backend already answered `verified = false` for this exact transition. Identical bytes cannot earn a
     * different verdict, so spending the proof again buys nothing — and the report is withheld with it.
     */
    ALREADY_REFUSED("an identical proof for this transition was already refused"),

    /**
     * The backend already answered `verified = true` for this exact transition and the verdict is still inside
     * its reuse window. The only reason that leaves the transition [corroborated], so its report still goes
     * out: the transition is live precisely BECAUSE the report keeps being refused, and withholding it too
     * would leave the deal both unproven and silent.
     */
    ALREADY_ACCEPTED("the backend already verified a proof for this transition"),

    /**
     * A **demanded re-attestation** was refused and its own retry window has not elapsed — the freshness
     * axis's settled answer, and the only bound on it. Carries a deadline, like [PROVER_PARKED].
     *
     * Ranked here, with the other two settled answers and ahead of the spending gates, because it is the same
     * kind of fact: something already answered rather than something this client may not afford right now.
     * The two above it can never apply to a demand — they are keyed on a [ProofIntent], and a demand is not
     * one — so in practice this is the first gate a demand meets. See [ProofFreshness.refused] for why the
     * ladder exists at all: a demand bypasses the loop's refused-proof latch by construction, and
     * [com.dmarket.p2p.tracker.policy.NotaryProofThrottle] cannot stand in for it because that breaker clears
     * on a proof having been *generated*, explicitly not on the verdict.
     */
    FRESHNESS_RETRY_PENDING("the demanded fresh proof was refused; its retry window has not elapsed"),

    /**
     * The prover has failed often enough in a row to be parked. Carries a deadline — see
     * `LifecycleEvent.ProofSuppressed.retryAfterSeconds`.
     */
    PROVER_PARKED("the prover is parked after repeated proof failures"),

    /**
     * This cycle has already minted one proof and the next heartbeat is now due, so minting *another* would
     * run the cycle past it. Never the reason for the first proof of a cycle — see [ProofMintPolicy.decide].
     */
    BUDGET_SPENT("this cycle's proving budget is spent; the next heartbeat is due"),
    ;

    /**
     * Whether the backend holds corroboration for the transition despite the skip, so its trade-status report
     * may still be sent. Derived from the reason rather than carried alongside it: the two can then never
     * disagree, which is exactly the bug that shipped when the budget check was evaluated before this one.
     */
    val corroborated: Boolean get() = this == ALREADY_ACCEPTED
}

/** Whether a due proof should be minted now, and if not, why not. */
sealed interface ProofMintVerdict {
    /** Nothing is in the way — spend the MPC session. */
    data object Mint : ProofMintVerdict

    /** Do not mint. [retryAfterSeconds] is set only when the reason carries a deadline. */
    data class Skip(val reason: ProofSkipReason, val retryAfterSeconds: Int? = null) : ProofMintVerdict
}

/**
 * The decision in front of proof generation: the reasons a due proof is not spent, in one ordered `when`
 * instead of a stack of `continue` guards in the loop.
 *
 * **Two entry points, one ordering.** [decide] is for a changed transition (a [ProofIntent]) and [decideFreshness]
 * for a backend-demanded re-attestation ([ProofFreshness]). They differ only in their *settled-answer* gate —
 * a transition consults the two verdicts the backend has already given for those exact bytes, a demand
 * consults its own retry ladder, and a demand cannot consult the other two at all because they are keyed on an
 * intent it does not have. Everything about affordability is [spendingGates], shared, so the part of the order
 * that has already cost a shipped bug exists once.
 *
 * **Why the order is load-bearing, and why it is enforced here rather than by prose.** The first two reasons
 * cost no prover at all — they are answers the backend has already given. The last two are about whether this
 * client may spend an MPC session *right now*. Evaluating a spending gate before a settled answer withholds a
 * report for a reason that has nothing to do with it: that shipped once, when the budget check was placed
 * above [ProofSkipReason.ALREADY_ACCEPTED] and a transition the backend had already corroborated was
 * suppressed as "budget spent". Ordering it inside one function makes that mistake unrepresentable, and
 * [ProofSkipReason.corroborated] being derived from the reason means the report gate cannot disagree with it.
 *
 * **Deliberately per-intent, not a batch plan.** Both spending gates can flip *inside* one pass: a failure
 * parks the prover, and a slow proof pushes the clock past the cycle deadline. A single up-front plan over all
 * intents would be computed against state that the first mint invalidates.
 *
 * **The budget gate is keyed on `mintedThisCycle`, not on the deadline alone, and that is load-bearing.** The
 * first version assumed a cycle always begins with time on the clock — "a WATCH_ONLY cycle only exists because
 * the deadline has not arrived yet". True at cycle *start*, false where the gate is actually evaluated: the
 * Steam reads run first. Observed on dev 2026-08-26 — the wake landed **107 ms** before the heartbeat was due,
 * the deal-watch read took 287 ms, and the only due proof was refused as "budget spent" having minted nothing
 * at all. A force-tick masked it, because forcing sets the heartbeat due, which makes the cycle a HEARTBEAT one
 * and hands it a fresh full-cadence deadline. Once the heartbeat aims one poll floor inside the advertised
 * cadence, every wake lands within milliseconds of the deadline, so this was reachable on most cycles.
 *
 * Pure: no clock, no IO, no store. [now] and every piece of state are the caller's, which is what makes the
 * whole ordering table-testable — it was previously reachable only through the loop.
 */
object ProofMintPolicy {
    /**
     * @param refused transitions the backend has answered `verified = false` for (the loop's in-memory latch).
     * @param accepted transitions the backend has answered `verified = true` for, and when — narrowed to the
     *   tracked deals by the caller.
     * @param acceptedTtlMs how long an acceptance may be reused (`NotaryConfig.acceptedProofTtlMs`); `0`
     *   disables the reuse, which needs no branch of its own (a non-negative age is never `< 0`).
     * @param proverParkedUntil when the prover's cooldown expires, or `null` if it is not parked.
     * @param mintedThisCycle whether this pass has already spent a proof — what makes [cycleDeadline] a bound
     *   on the *chain* rather than on any single proof.
     * @param cycleDeadline when this cycle must stop minting, or `null` for no bound.
     */
    fun decide(
        intent: ProofIntent,
        now: Instant,
        refused: Set<ProofIntent>,
        accepted: Map<ProofIntent, Instant>,
        acceptedTtlMs: Int,
        proverParkedUntil: Instant?,
        mintedThisCycle: Boolean,
        cycleDeadline: Instant?,
    ): ProofMintVerdict = when {
        intent in refused -> ProofMintVerdict.Skip(ProofSkipReason.ALREADY_REFUSED)
        isCorroborated(intent, now, accepted, acceptedTtlMs) -> ProofMintVerdict.Skip(ProofSkipReason.ALREADY_ACCEPTED)
        else -> spendingGates(now, proverParkedUntil, mintedThisCycle, cycleDeadline)
    }

    /**
     * The gate in front of a **demanded** re-attestation ([ProofFreshness]) rather than a changed transition.
     *
     * **It deliberately does not take `refused` or `accepted`, and that is the substance of DMA-280 rather than
     * a simplification.** Those two answer "can identical bytes earn a different verdict?", and a demand is
     * precisely the case where the bytes are *not* identical: the backend is asking for an attestation newer
     * than a mark it stamped itself. [ProofSkipReason.ALREADY_ACCEPTED] is the sharper one — an acceptance up
     * to `acceptedProofTtlMs` old satisfying a payout is the stale-flag release the whole ticket exists to
     * stop — and neither is even expressible here, because both are keyed on a [ProofIntent] and a demand is
     * not one. Keeping the types apart is what makes that unrepresentable instead of guarded.
     *
     * **Both spending gates still apply, and both are load-bearing for a demand too.**
     * [ProofSkipReason.PROVER_PARKED] because the incident it exists for — six deals, every attempt wedged,
     * one cycle held ~16 minutes and 412 s between heartbeats — does not become acceptable when the proof is a
     * re-attestation; a demand exempted from it would starve the very heartbeat that carries the next mark.
     * [ProofSkipReason.BUDGET_SPENT] for the same reason it bounds a chain of transitions.
     *
     * **A cooldown outlasting the release grace costs a label, not the payout** — correcting an earlier note
     * here that called it a known cost and attributed the recovery to a backend "re-stamp" that does not
     * exist (backend, 2026-09-02). A mark is stamped once, at hold expiry, and republished byte-identical on
     * every heartbeat until a proof satisfies it or the deal ends. When the grace expires the deal neither
     * pays nor settles: it **freezes**, the demand stays on the watch entry, the entry outlives the freeze,
     * and the first proof attested after the mark clears the freeze and runs the payout. So the breaker's
     * 30-minute ceiling delays a settlement and shows "state cannot be confirmed" for those minutes; it
     * cannot lose one, because the demand is still outstanding when the breaker clears. That is also the
     * reason not to exempt a demand from these gates — the exemption would buy minutes at the price of
     * re-arming a measured incident, with no money on the other side of the trade.
     *
     * @param progress this deal's persisted freshness standing, or `null` if it has none yet.
     */
    fun decideFreshness(
        progress: FreshProofProgress?,
        now: Instant,
        proverParkedUntil: Instant?,
        mintedThisCycle: Boolean,
        cycleDeadline: Instant?,
    ): ProofMintVerdict {
        val retryAt = progress?.retryAt
        // A window ending exactly at `now` has elapsed — the same boundary rule `parkedUntil` uses.
        if (retryAt != null && now < retryAt) {
            return ProofMintVerdict.Skip(
                ProofSkipReason.FRESHNESS_RETRY_PENDING,
                retryAfterSeconds = CooldownLadder.retryAfterSeconds(retryAt, now),
            )
        }
        return spendingGates(now, proverParkedUntil, mintedThisCycle, cycleDeadline)
    }

    /**
     * The two reasons that are about whether this client may spend an MPC session *right now*, shared by both
     * entry points so the ordering cannot diverge between them.
     *
     * Extracted rather than duplicated for the reason this object's own doc gives: a second copy is a second
     * ordering, and this is the file where getting the order wrong has already cost a shipped bug.
     */
    private fun spendingGates(
        now: Instant,
        proverParkedUntil: Instant?,
        mintedThisCycle: Boolean,
        cycleDeadline: Instant?,
    ): ProofMintVerdict = when {
        proverParkedUntil != null -> ProofMintVerdict.Skip(
            ProofSkipReason.PROVER_PARKED,
            retryAfterSeconds = CooldownLadder.retryAfterSeconds(proverParkedUntil, now),
        )
        mintedThisCycle && cycleDeadline != null && now >= cycleDeadline -> ProofMintVerdict.Skip(ProofSkipReason.BUDGET_SPENT)
        else -> ProofMintVerdict.Mint
    }

    /**
     * Whether [intent] has a verdict in [accepted] that is still inside its reuse window.
     *
     * A clock that has moved BACKWARDS (a host time correction, a restored machine) makes the age negative;
     * that reads as expired rather than as infinitely fresh, so a bad clock can only ever cost a proof, never
     * suppress one forever.
     */
    private fun isCorroborated(intent: ProofIntent, now: Instant, accepted: Map<ProofIntent, Instant>, ttlMs: Int): Boolean {
        val at = accepted[intent] ?: return false
        val age = now - at
        return !age.isNegative() && age.inWholeMilliseconds < ttlMs
    }
}
