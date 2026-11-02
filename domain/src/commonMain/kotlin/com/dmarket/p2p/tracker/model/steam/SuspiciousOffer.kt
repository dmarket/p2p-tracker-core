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

/** How an outgoing offer looks to the suspicious-offer check. */
enum class SuspicionLevel {
    /** Linked to a deal, leased by this device, not give-only, not active, or to a partner the user trusts. */
    NONE,

    /** Gives items, gets nothing back, and no DMarket deal or lease of this device explains it. */
    UNLINKED_GIVE_ONLY,

    /** To a partner on the known-scam list, whatever else is true of the offer. */
    IMPERSONATION,
}

/** An active outgoing offer the check flagged. [level] is never [SuspicionLevel.NONE]. */
data class SuspiciousOffer(val offerId: OfferId, val partner: SteamId, val assetIdsGiven: Set<AssetId>, val level: SuspicionLevel)
