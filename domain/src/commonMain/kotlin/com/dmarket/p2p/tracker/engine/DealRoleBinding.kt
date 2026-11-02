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
 * **No heartbeat, no write.** Every Steam write is a directive the backend leased in a heartbeat, so a write
 * with no tracking list behind it has no lease behind it either, and is refused. There used to be an exception
 * for a host-built create on a cold worker; that entry point is gone, and so is the exception.
 *
 * **Otherwise fails open on the role, deliberately.** Within a heartbeat, only a deal the backend reports as
 * [DealRole.BUYER] refuses a write. A deal absent from the list or a [DealRole.UNKNOWN] entry allows it: the
 * directive itself is the backend's lease, `role` is not in the frozen contract yet, and a client that refused
 * writes on its absence would brick the seller flow against any backend that does not send it.
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

    /** `false` with no heartbeat ([activeTracking] `null`) and for a deal the backend reports as [DealRole.BUYER]. */
    fun allowsWrite(activeTracking: List<TrackedDeal>?, dealId: DealId?): Boolean =
        activeTracking != null && roleOf(activeTracking, dealId) != DealRole.BUYER
}
