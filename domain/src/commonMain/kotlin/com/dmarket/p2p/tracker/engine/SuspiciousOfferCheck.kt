package com.dmarket.p2p.tracker.engine

import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.marketplace.TrackedDeal
import com.dmarket.p2p.tracker.model.steam.CreateLease
import com.dmarket.p2p.tracker.model.steam.OfferDirection
import com.dmarket.p2p.tracker.model.steam.SteamSentOffer
import com.dmarket.p2p.tracker.model.steam.SuspicionLevel
import com.dmarket.p2p.tracker.model.steam.SuspiciousOffer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Flags this account's Steam offers that give items away with no DMarket deal behind them: the shape a
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
 *
 * A received offer ([OfferDirection.RECEIVED]) is only ever [SuspicionLevel.IMPERSONATION] or [SuspicionLevel.NONE]:
 * it is flagged when it is active, takes at least one of this account's items, and comes from a partner on the
 * known-scam list. DMarket's own deposit bots send exactly that give-only shape, so the give-only rule does not apply
 * to it, and leases and trust cannot explain an offer this device did not make. With the received-offer name check on,
 * a candidate whose partner's persona name poses as DMarket is also flagged once DMarket has said the offer is not one
 * of its own ([dmarketVerdicts] `false`) and that answer has held for at least the grace ([notOursFor], measured on
 * DMarket's server clock from its first "not ours"); a `true`, a missing verdict, no measurement or a shorter one leaves
 * it [SuspicionLevel.NONE]. Neither Steam's `time_created` nor the device clock is used for the grace: a device clock
 * running fast, or moved between scans, would shorten it. Only a known-scam partner outranks DMarket's `true`.
 */
object SuspiciousOfferCheck {
    /** `ETradeOfferState` Active (2) and CreatedNeedsConfirmation (9): the offers that can still be confirmed. */
    val ACTIVE_STATES: Set<Int> = setOf(2, 9)

    /** How long a lease covers an offer. Past it the deal has its `steamOfferId`, or is long closed. */
    val LEASE_TTL: Duration = 24.hours

    /**
     * Whether [offer] can be flagged at all: active, and for a received offer one that takes something from this
     * account. The loop reads persona names only for the partners of the candidates.
     */
    fun candidate(offer: SteamSentOffer): Boolean = offer.state in ACTIVE_STATES &&
        (offer.direction == OfferDirection.SENT || offer.assetIdsGiven.isNotEmpty())

    /**
     * Whether [offer] is a received [candidate] not from a known-scam partner: the received offers whose verdict can rest
     * on the partner's persona name and DMarket's answer.
     */
    fun receivedNameCandidate(offer: SteamSentOffer, knownScamPartners: Set<SteamId>): Boolean =
        offer.direction == OfferDirection.RECEIVED && candidate(offer) && offer.partner !in knownScamPartners

    /**
     * Whether [offer] is a received candidate whose verdict rests on DMarket's answer: not from a known-scam partner,
     * and from a partner whose persona name poses as DMarket. The loop asks DMarket about these offers and no other.
     */
    fun asksDmarket(offer: SteamSentOffer, knownScamPartners: Set<SteamId>, personaNames: Map<SteamId, String>): Boolean =
        receivedNameCandidate(offer, knownScamPartners) && posesAsDmarket(offer.partner, personaNames)

    private fun posesAsDmarket(partner: SteamId, personaNames: Map<SteamId, String>): Boolean =
        personaNames[partner]?.let(DmarketImpersonation::impersonates) == true

    /**
     * The level of one offer. [receivedNameCheck], [dmarketVerdicts] (absent = no answer), [isTradeOurGrace] and
     * [notOursFor] (how long DMarket has been answering `false`, by its server clock) apply to received offers only.
     */
    fun classify(
        offer: SteamSentOffer,
        tracking: List<TrackedDeal>,
        leases: List<CreateLease>,
        trustedPartners: Set<SteamId>,
        knownScamPartners: Set<SteamId>,
        now: Instant,
        personaNames: Map<SteamId, String> = emptyMap(),
        receivedNameCheck: Boolean = false,
        dmarketVerdicts: Map<OfferId, Boolean> = emptyMap(),
        isTradeOurGrace: Duration = Duration.ZERO,
        notOursFor: Map<OfferId, Duration> = emptyMap(),
    ): SuspicionLevel {
        if (!candidate(offer)) return SuspicionLevel.NONE
        if (offer.partner in knownScamPartners) return SuspicionLevel.IMPERSONATION
        if (offer.direction == OfferDirection.RECEIVED) {
            val held = notOursFor[offer.offerId]
            val pastGrace = isTradeOurGrace == Duration.ZERO || held != null && held >= isTradeOurGrace
            val notOurs = dmarketVerdicts[offer.offerId] == false && pastGrace
            return if (receivedNameCheck && notOurs && posesAsDmarket(offer.partner, personaNames)) {
                SuspicionLevel.IMPERSONATION
            } else {
                SuspicionLevel.NONE
            }
        }
        if (posesAsDmarket(offer.partner, personaNames)) return SuspicionLevel.IMPERSONATION
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

    /** Every offer in [sent] (either direction) whose [classify] level is not [SuspicionLevel.NONE], in [sent]'s order. */
    fun evaluate(
        sent: List<SteamSentOffer>,
        tracking: List<TrackedDeal>,
        leases: List<CreateLease>,
        trustedPartners: Set<SteamId>,
        knownScamPartners: Set<SteamId>,
        now: Instant,
        personaNames: Map<SteamId, String> = emptyMap(),
        receivedNameCheck: Boolean = false,
        dmarketVerdicts: Map<OfferId, Boolean> = emptyMap(),
        isTradeOurGrace: Duration = Duration.ZERO,
        notOursFor: Map<OfferId, Duration> = emptyMap(),
    ): List<SuspiciousOffer> = sent.mapNotNull { offer ->
        val level = classify(
            offer, tracking, leases, trustedPartners, knownScamPartners, now, personaNames,
            receivedNameCheck, dmarketVerdicts, isTradeOurGrace, notOursFor,
        )
        if (level == SuspicionLevel.NONE) {
            null
        } else {
            // Off the list, an impersonation can only have come from the partner's persona name, in either direction.
            val byName = level == SuspicionLevel.IMPERSONATION && offer.partner !in knownScamPartners
            SuspiciousOffer(offer.offerId, offer.partner, offer.assetIdsGiven, level, personaNames[offer.partner], offer.direction, byName)
        }
    }
}
