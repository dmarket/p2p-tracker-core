package com.dmarket.p2p.tracker.engine

import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.marketplace.DealRole
import com.dmarket.p2p.tracker.model.marketplace.TrackedDeal

/**
 * Pure, zero-IO decision: may a non-idempotent Steam write (`create_offer` / `cancel_offer`) reach Steam
 * for a deal, given which side of that deal the backend says this account is on?
 *
 * **Why the client needs its own answer.** The backend serves `active_tracking[]` to *both* participants
 * of a deal but leases the two write directives to the seller alone — the buyer must never be instructed
 * to write to Steam on the deal. Until now that guarantee rested entirely on the backend's indexing:
 * nothing on this side could tell a seller-role write from a buyer-role one, because a `Directive` carries
 * no side of its own. `TrackedDeal.role` is the only per-deal signal that can, which is what this object
 * turns into a verdict — defence in depth against a backend index regression, and the check a host-driven
 * write ([com.dmarket.p2p.tracker.port.steam.SteamOfferCreator]'s callers) has no other way to make.
 *
 * **Fails open, deliberately.** Only a deal the backend is *presently reporting* as [DealRole.BUYER]
 * refuses a write. No heartbeat yet, a deal absent from the list, or a [DealRole.UNKNOWN] entry all allow
 * it — this field is not in the frozen contract yet, and a client that refused writes on its absence would
 * brick the seller flow against any backend that does not send it. The asymmetry is intentional: the cost
 * of failing open is a write the backend already refuses to lease; the cost of failing closed is every
 * legitimate sale.
 */
object DealRoleBinding {

    /**
     * The side this account is on for [dealId], as of [activeTracking] (the last heartbeat's list, `null`
     * when no heartbeat has landed yet). [DealRole.UNKNOWN] when the deal is not in the list at all — a
     * write can legitimately precede its watch entry, so absence is not an opinion.
     */
    fun roleOf(activeTracking: List<TrackedDeal>?, dealId: DealId?): DealRole {
        if (activeTracking == null || dealId == null) return DealRole.UNKNOWN
        return activeTracking.firstOrNull { it.dealId == dealId }?.role ?: DealRole.UNKNOWN
    }

    /** `false` only for a deal the backend presently reports as [DealRole.BUYER]; see the fail-open rule. */
    fun allowsWrite(activeTracking: List<TrackedDeal>?, dealId: DealId?): Boolean = roleOf(activeTracking, dealId) != DealRole.BUYER
}
