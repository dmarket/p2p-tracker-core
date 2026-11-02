package com.dmarket.p2p.tracker.model.steam

import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.SteamId
import kotlin.time.Instant

/**
 * What this device was about to send to Steam, written **before** its own `tradeoffer/new/send` for a deal. An
 * outgoing offer that matches a lease is one this device made for a DMarket deal, even before the backend reports
 * the offer id back on a heartbeat. See `com.dmarket.p2p.tracker.engine.SuspiciousOfferCheck`.
 */
data class CreateLease(val dealId: DealId, val partner: SteamId, val assetIds: Set<AssetId>, val createdAt: Instant)

/** How an offer looks to the suspicious-offer check. */
enum class SuspicionLevel {
    /**
     * Linked to a deal, leased by this device, not give-only, not active, or to a partner the user trusts. A received
     * offer is this unless its sender is on the known-scam list.
     */
    NONE,

    /** A sent offer that gives items, gets nothing back, and no DMarket deal or lease of this device explains it. */
    UNLINKED_GIVE_ONLY,

    /** To a partner on the known-scam list, or whose persona name poses as DMarket, whatever else is true of the offer. */
    IMPERSONATION,
}

/**
 * An active offer the check flagged. [level] is never [SuspicionLevel.NONE]; [personaName] is the partner's, when read.
 * [partner] and [assetIdsGiven] are from this account's side in either [direction] (see [SteamSentOffer]).
 */
data class SuspiciousOffer(
    val offerId: OfferId,
    val partner: SteamId,
    val assetIdsGiven: Set<AssetId>,
    val level: SuspicionLevel,
    val personaName: String? = null,
    val direction: OfferDirection = OfferDirection.SENT,
)
