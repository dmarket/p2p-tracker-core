package com.dmarket.p2p.tracker.engine

import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.TradeId
import com.dmarket.p2p.tracker.model.marketplace.TrackedDeal
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusSource
import com.dmarket.p2p.tracker.policy.CooldownLadder
import kotlin.random.Random
import kotlin.time.Instant

/**
 * One deal's demand for a freshly-attested proof of its trade — the backend's *freshness mark*, resolved.
 *
 * [tradeId] is non-null by construction, which is the point of having a type at all: the history axis's
 * proven read addresses one trade by id, and a demand that cannot name one is not a unit of work but a
 * contract gap. `SteamProofReadMapper` answers an absent id with a `requireNotNull` throw that costs a
 * prover attempt and reads as a proof failure; [ProofFreshness.due] sorts those into
 * [FreshProofPlan.unbindable] instead, where they can be said out loud for free.
 */
data class FreshProofDemand(val dealId: DealId, val tradeId: TradeId, val proveAfter: Instant) {
    companion object {
        /**
         * The axis a demand is always on, fixed by **construction** and deliberately not derived from
         * [TrackedDeal.watch].
         *
         * `steam_trade_id` addresses `GetTradeStatus?tradeid=…` and nothing else, so there is no second
         * reading of what a mark asks for. Deriving the axis from the watch set would add one: `watch` is a
         * polling instruction, [com.dmarket.p2p.tracker.model.marketplace.WatchTarget.fromWire] maps an
         * unrecognised spelling to `UNKNOWN`, and a client that inferred "no trade axis, therefore no demand"
         * would answer a backend typo by silently parking every payout it named.
         */
        val AXIS: TradeStatusSource = TradeStatusSource.HISTORY
    }
}

/**
 * Where this device stands against one deal's freshness mark: the greatest mark a verified proof of its own
 * has satisfied, and the retry ladder for a mark whose proof was refused.
 *
 * [satisfied] is the latch, and it holds a **backend-issued** value rather than a local clock reading — see
 * [ProofFreshness.satisfied]. [attempting] names the mark [attempts] and [retryAt] belong to, so a *greater*
 * mark starts its own ladder instead of inheriting an exhausted rung from the previous one. The backend
 * stamps a mark once and republishes it byte-identical until it is answered, so the only greater mark is its
 * manual re-check (behind a 10-minute cooldown) — rare, and exactly the case where inheriting an exhausted
 * rung would be wrong.
 *
 * Nothing here is written before the backend has answered, which is what makes a worker killed mid-proof
 * cost one re-mint and never a wedge: there is no in-flight flag to be left set, and therefore nothing that
 * would need a lease or a force-tick to clear.
 */
data class FreshProofProgress(
    val satisfied: Instant? = null,
    val attempting: Instant? = null,
    val attempts: Int = 0,
    val retryAt: Instant? = null,
)

/**
 * What the freshness axis wants of this cycle: the demands that are due, and the marks that name no trade.
 *
 * [unbindable] is carried rather than dropped because a demand nobody can answer is exactly the state a
 * stranded payout is investigated from, and it is invisible everywhere else — the deal reports nothing, the
 * mint loop is never reached, and the backend's own view is "asked, unanswered".
 *
 * **It now guards a contract rather than an expected state, and is kept at the backend's explicit ask**
 * (2026-09-02). The mark is stamped fail-closed even when the trade id is unknown, and this branch was the
 * only thing anywhere that would have said so out loud; that prompted a fix at the source instead — Steam
 * puts `tradeid` on an offer the moment it is accepted, which is the same event that starts the protection
 * hold, and the backend was dropping that field when parsing the acceptance proof. The id is now banked
 * write-once a whole hold before anything needs it, with the completion proof as a second source. So an
 * entry here means a violated contract, which is the right thing for it to report.
 */
data class FreshProofPlan(val demands: List<FreshProofDemand> = emptyList(), val unbindable: List<DealId> = emptyList()) {
    val isEmpty: Boolean get() = demands.isEmpty() && unbindable.isEmpty()

    companion object {
        val EMPTY: FreshProofPlan = FreshProofPlan()
    }
}

/**
 * The pure rule set behind DMA-280's freshness mark: which deals owe a re-attestation, and how a refused one
 * is bounded.
 *
 * **What the mark is for.** The backend used to release a protection hold on a completion flag recorded
 * earlier in the deal, and nothing re-read Steam at the moment of release. The real Steam order is
 * complete-**then**-roll-back, and the rollback goes unreported because the only reader of that trade is the
 * seller's own client — so the hold matured on a fact that was no longer true. At expiry the backend now
 * stamps [TrackedDeal.proveAfter] on the watch entry, naming [TrackedDeal.steamTradeId] and the instant to
 * beat, and releases only against a proof attested at or after it.
 *
 * **Why this cannot be a [TrackerTick] concern.** That reducer answers "what changed?", and a demand exists
 * precisely when nothing has: it skips a deal with no observation at all, and every proof intent it raises is
 * inside a `code != baseline` branch. A demand also has no honest Steam status code to carry — at hold expiry
 * there is no observation, and [ReportedStatus.lastHistoryCode] is null on a second device or a fresh install
 * because [BaselineSeed] declines to seed the history axis. It needs none: the proven read is built from the
 * trade id alone, and the code is what the read *discovers*.
 *
 * **Why it is not a [ProofIntent] either.** That type is the key of a persisted ledger and of the
 * report-to-intent pairing, both compared field by field, so widening it to carry a demand would corrupt
 * stored rows and silently un-gate a report. Keeping the two apart makes three hazards unrepresentable rather
 * than guarded: the loop's refused-proof latch cannot swallow a demand, an hour-old acceptance cannot answer
 * a mark that exists to refuse exactly that, and no report can lose its intent.
 *
 * No clock, no IO, no store — [Instant]s and the [Random] are the caller's, so every rule here is
 * table-testable without the loop. Same contract as [BaselineSeed.merge] and [ProofMintPolicy.decide].
 */
object ProofFreshness {
    /** `LifecycleEvent.ProofSuppressed.reason` for a mark that names no trade — a contract gap, not a skip. */
    const val UNBINDABLE: String = "a fresh proof was demanded but the deal names no trade to prove"

    /**
     * The demands due at this heartbeat, and the marks that cannot be served.
     *
     * **Gated on the mark alone — not on [TrackedDeal.proofRequired], and not on the deal watching a trade
     * axis.** The mark *is* the request: the backend does not stamp one unless it is holding a payout on it,
     * and it is the same backend that decides `proof_required` and populates `watch`. Adding either as a
     * conjunct would mean a flag or a `watch` spelling that lagged the mark by one deploy parks a settlement
     * indefinitely, with the seller's funds locked and nothing in the client able to notice — whereas acting
     * on a mark that turns out to be spurious costs one MPC session. Only one of those two is recoverable,
     * and it is not the silent one.
     *
     * **Strict `>`, and equality is the common case rather than an edge.** The same mark is re-presented on
     * every heartbeat and on every watch-only wake from the cached tracking list, so "not greater ⇒ skip" is
     * what stops one demand becoming one proof per wake for the life of the deal. A mark that moves
     * *backwards* is skipped by the same comparison; the cost of that is a payout the backend does not
     * release, and it self-heals the moment a greater mark is stamped. (That the backend cannot then release
     * on a stale proof is a property of the backend's own attestation-time check, not something the client
     * enforces.)
     *
     * **The wire's granularity is whole seconds**, so two marks stamped inside one second are indistinguishable
     * here (confirmed with the backend, 2026-09-02). Safe as the mechanism stands — a mark is stamped once and
     * republished unchanged, and the only greater one is the manual re-check behind a 10-minute cooldown — but
     * a future path that re-stamps faster than that would need sub-second precision on the wire, or this
     * comparison would drop it silently.
     *
     * @param tracking the heartbeat's `active_tracking`, carrying whatever marks the backend has stamped.
     * @param progress this device's persisted standing per deal.
     */
    fun due(tracking: List<TrackedDeal>, progress: Map<DealId, FreshProofProgress>): FreshProofPlan {
        if (tracking.isEmpty()) return FreshProofPlan.EMPTY
        val demands = mutableListOf<FreshProofDemand>()
        val unbindable = mutableListOf<DealId>()
        for (deal in tracking) {
            val mark = deal.proveAfter ?: continue
            val satisfied = progress[deal.dealId]?.satisfied
            if (satisfied != null && mark <= satisfied) continue
            // The wire's id, with no fallback to a locally observed one. It is the backend's own statement of
            // which trade it stamped this mark for, and the local join is weaker in exactly the scenario the
            // mark exists for: `TradeTrackerLoop.correlateTransfer`'s asset-ref path can match a *different*
            // trade of the same asset, because an item returns under its original id after a rollback and may
            // be sold again.
            val tradeId = deal.steamTradeId
            if (tradeId == null) {
                unbindable += deal.dealId
                continue
            }
            demands += FreshProofDemand(deal.dealId, tradeId, mark)
        }
        return if (demands.isEmpty() && unbindable.isEmpty()) FreshProofPlan.EMPTY else FreshProofPlan(demands, unbindable)
    }

    /**
     * The standing after the backend answered `verified = false` for [demand]: the mark stays unsatisfied and
     * this mark's retry ladder advances a rung.
     *
     * **Every refusal lands here, whatever its cause, and that is now the only implementable design rather
     * than the deliberate widening it was written as.** The ticket named one retriable reason ("likely
     * `stale_attestation`") and implied the rest were terminal. Both halves turned out wrong (backend,
     * 2026-09-02): the code for this case is `stale_for_mark` — "correct trade, correct transition, taken
     * before we asked" — while `stale_attestation` is a different verdict, the `max_attestation_age` bound.
     * And decisively, `SubmitProofResponse` carries `{deal_id, verified, reason}` with the machine-readable
     * code logged server-side and never returned, so there is nothing to branch on even for a client that
     * wanted to. A ladder satisfies "do not mark satisfied, retry" for every cause without that dependency,
     * and bounds the cost of the causes that are genuinely hopeless.
     *
     * **The bound is mandatory rather than defensive.** A demand bypasses the loop's refused-proof latch by
     * construction, and [com.dmarket.p2p.tracker.policy.NotaryProofThrottle] cannot stand in for it: that
     * breaker clears on a proof having been *generated*, explicitly not on the verdict, so a backend that
     * keeps refusing resets it on every attempt. With nothing else in the way a permanently-refused mark is
     * one full MPC session per wake, for as long as the deal is watched — the cost regime that ledger was
     * created to kill. This is also the per-intent ladder `NotaryProofThrottle`'s KDoc notes it does not have.
     *
     * Rungs are drawn with equal jitter off the notary breaker's own base/max, so a mark refused across many
     * devices does not retry in lockstep. Reusing those two numbers rather than adding a pair of knobs is the
     * house rule about unmeasured config: if the two surfaces ever need to differ, that is when a field earns
     * a slot.
     */
    fun refused(
        current: FreshProofProgress?,
        demand: FreshProofDemand,
        now: Instant,
        cooldownBaseMs: Int,
        cooldownMaxMs: Int,
        random: Random,
    ): FreshProofProgress {
        // Re-based when the mark moved: a fresh demand is a fresh question, and inheriting the previous
        // mark's rung would hold a newly-stamped mark off for as long as the abandoned one had earned.
        val rung = if (current?.attempting == demand.proveAfter) CooldownLadder.next(current.attempts) else 1
        return FreshProofProgress(
            satisfied = current?.satisfied,
            attempting = demand.proveAfter,
            attempts = rung,
            retryAt = now + CooldownLadder.drawEqualJitter(rung, cooldownBaseMs, cooldownMaxMs, random),
        )
    }

    /**
     * The standing after the backend answered `verified = true` for [demand].
     *
     * **The mark that was answered, never a local clock reading.** The comparison in [due] is then between two
     * backend-issued instants and cannot be moved by a device whose clock is wrong — unlike anything derived
     * from `now`, where a fast client would mark a demand satisfied that it had not answered, which is the
     * stale-flag payout this whole mechanism exists to stop.
     *
     * A fresh value rather than a `copy`, so the ladder fields are dropped with the episode they belonged to:
     * a stale [FreshProofProgress.attempting] left standing would let the *next* mark's first refusal inherit
     * this one's exhausted rung. [maxOf] because the latch may only ever move forward — recording an older
     * mark than one already satisfied would re-open a demand this device has closed.
     */
    fun satisfied(current: FreshProofProgress?, demand: FreshProofDemand): FreshProofProgress =
        FreshProofProgress(satisfied = maxOf(demand.proveAfter, current?.satisfied ?: demand.proveAfter))
}
