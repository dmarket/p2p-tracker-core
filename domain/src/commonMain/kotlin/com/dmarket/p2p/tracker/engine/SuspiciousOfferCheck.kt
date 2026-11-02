package com.dmarket.p2p.tracker.engine

import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.marketplace.TrackedDeal
import com.dmarket.p2p.tracker.model.steam.CreateLease
import com.dmarket.p2p.tracker.model.steam.SteamSentOffer
import com.dmarket.p2p.tracker.model.steam.SuspicionLevel
import com.dmarket.p2p.tracker.model.steam.SuspiciousOffer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Flags this account's outgoing Steam offers that give items away with no DMarket deal behind them: the shape a
 * scammer asks a seller to confirm in Steam Mobile. Pure, like [UnansweredCreateCheck]: the loop reads the inputs and
 * emits the result, and nothing here blocks, cancels or changes an offer.
 *
 * An offer is [SuspicionLevel.UNLINKED_GIVE_ONLY] when all of these hold:
 * - it is active ([ACTIVE_STATES]: Active, or waiting for mobile confirmation);
 * - it gives at least one item and receives none;
 * - no tracked deal names it as its `steamOfferId`;
 * - no live [CreateLease] covers it: same partner, every given asset in the lease, created no earlier than
 *   [UnansweredCreateCheck.CLOCK_TOLERANCE] before the lease;
 * - the user has not marked the partner as trusted.
 *
 * An active offer to a partner on the known-scam list, or whose persona name [DmarketImpersonation.impersonates], is
 * [SuspicionLevel.IMPERSONATION] regardless of the rest, including a lease and the user's trust: a scammer can get a
 * real deal registered too.
 */
object SuspiciousOfferCheck {
    /** `ETradeOfferState` Active (2) and CreatedNeedsConfirmation (9): the offers that can still be confirmed. */
    val ACTIVE_STATES: Set<Int> = setOf(2, 9)

    /** How long a lease covers an offer. Past it the deal has its `steamOfferId`, or is long closed. */
    val LEASE_TTL: Duration = 24.hours

    /** The level of one offer. */
    fun classify(
        offer: SteamSentOffer,
        tracking: List<TrackedDeal>,
        leases: List<CreateLease>,
        trustedPartners: Set<SteamId>,
        knownScamPartners: Set<SteamId>,
        now: Instant,
        personaNames: Map<SteamId, String> = emptyMap(),
    ): SuspicionLevel {
        if (offer.state !in ACTIVE_STATES) return SuspicionLevel.NONE
        if (offer.partner in knownScamPartners) return SuspicionLevel.IMPERSONATION
        if (personaNames[offer.partner]?.let(DmarketImpersonation::impersonates) == true) return SuspicionLevel.IMPERSONATION
        val giveOnly = offer.assetIdsGiven.isNotEmpty() && offer.assetIdsReceived.isEmpty()
        val linked = tracking.any { it.steamOfferId == offer.offerId }
        val leased = leases.any { lease ->
            now - lease.createdAt < LEASE_TTL &&
                lease.partner == offer.partner &&
                lease.assetIds.containsAll(offer.assetIdsGiven) &&
                offer.createdAt >= lease.createdAt - UnansweredCreateCheck.CLOCK_TOLERANCE
        }
        return if (!giveOnly || linked || leased || offer.partner in trustedPartners) {
            SuspicionLevel.NONE
        } else {
            SuspicionLevel.UNLINKED_GIVE_ONLY
        }
    }

    /** Every offer in [sent] whose [classify] level is not [SuspicionLevel.NONE], in [sent]'s order. */
    fun evaluate(
        sent: List<SteamSentOffer>,
        tracking: List<TrackedDeal>,
        leases: List<CreateLease>,
        trustedPartners: Set<SteamId>,
        knownScamPartners: Set<SteamId>,
        now: Instant,
        personaNames: Map<SteamId, String> = emptyMap(),
    ): List<SuspiciousOffer> = sent.mapNotNull { offer ->
        val level = classify(offer, tracking, leases, trustedPartners, knownScamPartners, now, personaNames)
        if (level == SuspicionLevel.NONE) {
            null
        } else {
            SuspiciousOffer(offer.offerId, offer.partner, offer.assetIdsGiven, level, personaNames[offer.partner])
        }
    }
}
