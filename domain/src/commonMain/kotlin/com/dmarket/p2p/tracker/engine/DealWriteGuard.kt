package com.dmarket.p2p.tracker.engine

import com.dmarket.p2p.tracker.model.ClaimPhase
import com.dmarket.p2p.tracker.model.DealWriteClaim
import com.dmarket.p2p.tracker.model.DealWriteKey
import com.dmarket.p2p.tracker.model.marketplace.DirectiveAction
import com.dmarket.p2p.tracker.model.marketplace.TrackedDeal
import kotlin.time.Duration
import kotlin.time.Instant

/** What a caller asking to perform a non-idempotent Steam write for a deal is allowed to do. */
sealed interface ClaimVerdict {
    /** No live claim stands in the way — take the claim and write. */
    data object Proceed : ClaimVerdict

    /**
     * The write is a **duplicate**: [claim] is the standing claim that blocks it. Grouping the two
     * blocked verdicts under one type lets a caller handle "duplicate" exhaustively without a dead
     * [Proceed] branch.
     */
    sealed interface Duplicate : ClaimVerdict {
        val claim: DealWriteClaim
    }

    /** This device already completed the write; replay [claim]'s stored outcome instead of writing again. */
    data class AlreadyCompleted(override val claim: DealWriteClaim) : Duplicate

    /** This device is writing right now (a concurrent duplicate); there is no outcome to replay yet. */
    data class InFlight(override val claim: DealWriteClaim) : Duplicate
}

/**
 * The pure decision behind deal-keyed write claims: may this `create_offer` / `cancel_offer` reach
 * Steam, and which stale claims should be released.
 *
 * No clock, no IO, no store — [now] is supplied by the caller, so every rule here is table-testable.
 * The IO half (atomic claim-taking, persistence) lives in the `:core` claim store; this object owns
 * *only* the verdict.
 */
object DealWriteGuard {
    /**
     * The verdict for a write against the claim currently stored for its `(deal, action)` key
     * ([existing], `null` when none).
     *
     * An expired claim yields [ClaimVerdict.Proceed]: [ttl] is the anti-wedge backstop for a claim whose
     * releasing signal never arrived (a crash between the Steam write and its bookkeeping, or a deal the
     * backend stopped reporting entirely). It must stay well above the backend's directive-lease TTL so
     * it can never expire while the backend still considers the write outstanding.
     */
    fun evaluate(existing: DealWriteClaim?, now: Instant, ttl: Duration): ClaimVerdict = when {
        existing == null -> ClaimVerdict.Proceed
        now - existing.claimedAt >= ttl -> ClaimVerdict.Proceed
        existing.phase == ClaimPhase.IN_FLIGHT -> ClaimVerdict.InFlight(existing)
        else -> ClaimVerdict.AlreadyCompleted(existing)
    }

    /**
     * The claims to release, given the backend's current [activeTracking] list and the writes it is
     * leasing in the same heartbeat ([leasedWrites], the `(deal, action)` keys of its `directives[]`).
     *
     * A claim is stale when it is older than [ttl] (the same backstop [evaluate] applies), or — for a
     * [ClaimPhase.COMPLETED] claim only — when the backend's view says the write can no longer be
     * duplicated:
     * - its deal is **absent** from `active_tracking` — the deal is done or gone, so there is nothing
     *   left to duplicate, or
     * - a [DirectiveAction.CREATE_OFFER] claim's deal is present but carries **no** `steam_offer_id` —
     *   the backend is telling us no offer exists for it, so a re-create is legitimate rather than a
     *   duplicate. (A cancel claim is not released on this signal: a missing offer id is exactly what a
     *   *successful* cancel looks like.)
     *
     * An [ClaimPhase.IN_FLIGHT] claim is **never** released by these signals — only by [ttl]. Its write
     * has not landed yet, so the backend legitimately still reports the deal without a `steam_offer_id`,
     * and releasing on that would re-open the very race the claim exists to close. The writer itself
     * releases an in-flight claim the moment its write resolves (or throws).
     *
     * A claim in [leasedWrites] is likewise kept (short of [ttl]): the backend asking for that exact write
     * *right now* is proof the deal is still in play, and it is the case the guard matters most in — a
     * re-leased create under a fresh `directive_id` must be answered with the offer we already made, not
     * with a second one. Releasing on the same heartbeat that re-leases the write would defeat the guard
     * entirely.
     *
     * Call this only on the post-heartbeat path. An empty [activeTracking] is read as "the backend is
     * watching nothing", which is true after a successful heartbeat but not after a failed one.
     */
    fun staleClaims(
        claims: Collection<DealWriteClaim>,
        activeTracking: List<TrackedDeal>,
        leasedWrites: Set<DealWriteKey>,
        now: Instant,
        ttl: Duration,
    ): Set<DealWriteKey> {
        if (claims.isEmpty()) return emptySet()
        val tracked = activeTracking.associateBy { it.dealId }
        return claims.filter { claim ->
            val deal = tracked[claim.dealId]
            when {
                now - claim.claimedAt >= ttl -> true
                claim.phase == ClaimPhase.IN_FLIGHT -> false
                claim.key in leasedWrites -> false
                deal == null -> true
                else -> claim.action == DirectiveAction.CREATE_OFFER && deal.steamOfferId == null
            }
        }.map { it.key }.toSet()
    }
}
