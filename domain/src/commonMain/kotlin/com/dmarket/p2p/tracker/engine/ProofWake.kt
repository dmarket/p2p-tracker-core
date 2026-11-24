package com.dmarket.p2p.tracker.engine

import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.PROVE_OFFER_REASON
import com.dmarket.p2p.tracker.model.PROVE_RELEASE_REASON
import com.dmarket.p2p.tracker.model.Wake
import com.dmarket.p2p.tracker.model.marketplace.DealRole
import com.dmarket.p2p.tracker.model.marketplace.TrackedDeal
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusSource

/**
 * The deals of [tracking] (the last heartbeat's list) that still wait on the proof this wake asks for (contract
 * §2.4, §6.4); empty for a wake that is no proof wake.
 *
 * Which deals: the one whose `transactionId` the wake names; none when the list carries transaction ids and none
 * matches (the deal is no longer tracked); every deal when the list carries none (a backend that does not send them).
 *
 * Waits: `prove_offer` (W2, repeated until offer 2/3 is proven) on a proof-enforced deal of ours whose offer code the
 * backend has not settled on a decisive one, an offer still awaiting Steam Guard (9) included; `prove_release` (W4)
 * on a deal with a freshness mark.
 */
fun Wake.dealsAwaitingProof(tracking: List<TrackedDeal>): Set<DealId> {
    val waits: (TrackedDeal) -> Boolean = when (reason) {
        PROVE_OFFER_REASON -> { deal ->
            deal.proofRequired &&
                deal.role != DealRole.BUYER &&
                deal.lastOfferCode?.let { DecisiveTransitions.isDecisive(TradeStatusSource.OFFER, it) } != true
        }
        PROVE_RELEASE_REASON -> { deal -> deal.proveAfter != null }
        else -> return emptySet()
    }
    val deals = if (transactionId != null && tracking.any { it.transactionId != null }) {
        tracking.filter { it.transactionId == transactionId }
    } else {
        tracking
    }
    return deals.filter(waits).mapTo(mutableSetOf()) { it.dealId }
}
