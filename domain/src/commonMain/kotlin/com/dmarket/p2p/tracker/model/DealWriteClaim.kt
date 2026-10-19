package com.dmarket.p2p.tracker.model

import com.dmarket.p2p.tracker.model.marketplace.DirectiveAction
import com.dmarket.p2p.tracker.model.marketplace.DirectiveOutcome
import kotlin.time.Instant

/** How far a claimed non-idempotent Steam write has got. */
enum class ClaimPhase {
    /** The Steam write is running right now — nothing to replay yet. */
    IN_FLIGHT,

    /** The Steam write finished and its [DealWriteClaim.outcome] can be replayed to a duplicate caller. */
    COMPLETED,
}

/**
 * One device's claim on a **non-idempotent Steam write for a deal** — the deal-keyed dedup record that
 * makes `create_offer` / `cancel_offer` safe against a caller that asks twice.
 *
 * Why deal-keyed rather than `directive_id`-keyed (which
 * [com.dmarket.p2p.tracker.engine.DirectivePlanner] and the tracker progress store already do): a host
 * fast-path caller (or a backend re-lease) can present a *fresh* `directive_id` for a deal whose offer
 * this device already created, and directive-id single-flight is structurally blind to that. Steam
 * cannot dedupe it either — the result is two live trade offers for one deal.
 *
 * The key is `(dealId, action)`, so a create and a cancel for the same deal never block each other.
 * [directiveId] records which caller won the claim (a duplicate arriving under a different id is still
 * suppressed, and the stored [outcome] can be re-reported under the new lease).
 *
 * Immutable and clock-free — [com.dmarket.p2p.tracker.engine.DealWriteGuard] decides everything about
 * it from a `now` passed in by the caller.
 */
data class DealWriteClaim(
    val dealId: DealId,
    /** [DirectiveAction.CREATE_OFFER] or [DirectiveAction.CANCEL_OFFER] — the only non-idempotent writes. */
    val action: DirectiveAction,
    val phase: ClaimPhase,
    val claimedAt: Instant,
    /** The directive id that took the claim; a later duplicate may arrive under a different one. */
    val directiveId: DirectiveId,
    /** The completed write's outcome (carries the `steam_offer_id`), replayed to duplicates. Set iff [ClaimPhase.COMPLETED]. */
    val outcome: DirectiveOutcome? = null,
    /**
     * Set on an [ClaimPhase.IN_FLIGHT] create whose request got no answer from Steam: what to look for among the
     * account's sent offers to learn whether it made one. The claim stays in flight meanwhile, so no second create
     * starts for the deal. See [com.dmarket.p2p.tracker.engine.UnansweredCreateCheck].
     */
    val unanswered: UnansweredCreate? = null,
) {
    /** The store key: one live claim per deal per write action. */
    val key: DealWriteKey get() = DealWriteKey(dealId, action)
}

/**
 * A `create_offer` sent to Steam with no answer back — a timeout, a refused connection, a failed lookup — so whether
 * Steam made the offer is unknown. [partner] and [assetIds] are what the offer would carry; [deadline] is the
 * create's deadline, past which nothing is concluded about it.
 */
data class UnansweredCreate(val partner: SteamId, val assetIds: Set<AssetId>, val deadline: Instant? = null)

/** The `(deal, action)` identity of a claim — one live non-idempotent write per pair. */
data class DealWriteKey(val dealId: DealId, val action: DirectiveAction)
