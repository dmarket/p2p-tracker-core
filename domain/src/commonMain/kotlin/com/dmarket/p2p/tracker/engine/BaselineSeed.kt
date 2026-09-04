package com.dmarket.p2p.tracker.engine

import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.marketplace.TrackedDeal

/**
 * Folds the backend's own record of what it already knows into the client's dedup baseline.
 *
 * **The gap this closes.** [ReportedStatus] is written only when *this device* gets a report accepted, so it
 * is local knowledge about a shared fact. A fresh install, a second device, or cleared storage therefore
 * starts from nothing and re-detects every live transition — and on a `proofRequired` deal, re-proves it. At
 * ~38 MB and one proof per cycle, an eleven-deal account pays hundreds of megabytes and ten-odd minutes to
 * rediscover state the backend has had all along. The backend is the authority on what it holds; it is
 * strictly cheaper for it to say so than for the client to re-derive it by proving.
 *
 * **Only where the client knows nothing.** A stored entry is this device's own record of an accepted report
 * and always wins: it may be ahead of what the backend echoed back (a report accepted since that heartbeat
 * was built), and overwriting it would re-open a transition this device has already closed. So the seed fills
 * gaps and never overrides — which also makes it idempotent, since the same heartbeat value can be folded in
 * on every cycle with no drift.
 *
 * **Offer axis only.** [TrackedDeal.lastOfferCode] is the only axis asked for, because the history axis
 * deliberately withholds its baseline while a rollback still lacks attribution (see `TrackerTick.reduce`) —
 * seeding a history code would suppress exactly the re-report that carries the actor, turning a saved MPC
 * session into a parked deal. If the backend ever offers a history code it must come with a flag saying the
 * record is complete, and this is where that would be handled.
 *
 * **Safe against a wrong seed in one direction only, which is why the contract wording matters.**
 * `TrackerTick.reduce` compares codes by inequality and the backend applies LWW over forward transitions, so
 * a seed *behind* reality merely produces a report the backend discards. A seed *ahead* of reality suppresses
 * a report the backend still needs, and nothing downstream catches that — hence [TrackedDeal.lastOfferCode]
 * means "settled", and absent means "I have nothing".
 *
 * **⚠️ INERT IN EVERY ENVIRONMENT SINCE IT SHIPPED — there is no producer** (established with the backend,
 * 2026-09-02, tracked as DMA-267). `last_offer_code` is not in the golden proto and p2p never sets it, so
 * this object folds in nothing and the "hundreds of megabytes per fresh install" above is being spent right
 * now. A second wall stands behind the first: the `/p2p/ext/…` routes are not protojson passthrough — the
 * gateway's `entity.P2PTrackedDeal` declares five fields and its handler copies five — so a proto-only fix
 * would still be invisible here, and DMA-267 needs the struct *and* the copy. Its field number is 8, since
 * DMA-280 took 6 and 7. Nothing to change on this side: the day a producer exists, this reads it.
 */
object BaselineSeed {
    /**
     * @param tracking the heartbeat's `active_tracking`, carrying whatever the backend already holds.
     * @param stored the locally persisted dedup baseline.
     * @return [stored], plus an offer-axis entry for every tracked deal the backend has a code for and the
     *   client does not.
     */
    fun merge(tracking: List<TrackedDeal>, stored: Map<DealId, ReportedStatus>): Map<DealId, ReportedStatus> {
        // The copy is made on first write, not up front: the steady state is "the backend added nothing this
        // cycle" — every deal already seeded stays seeded, and this runs on every watch pass — so the common
        // path should allocate nothing at all.
        var merged: MutableMap<DealId, ReportedStatus>? = null
        for (deal in tracking) {
            val code = deal.lastOfferCode ?: continue
            val existing = (merged ?: stored)[deal.dealId]
            if (existing?.lastOfferCode != null) continue
            // `copy` rather than a fresh value: a deal can hold a history baseline while its offer axis is
            // unseen (the axes are reported independently), and rebuilding would silently drop it.
            val target = merged ?: stored.toMutableMap().also { merged = it }
            target[deal.dealId] = (existing ?: ReportedStatus()).copy(lastOfferCode = code)
        }
        return merged ?: stored
    }
}
