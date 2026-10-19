package com.dmarket.p2p.tracker.engine

import com.dmarket.p2p.tracker.model.DealWriteClaim
import com.dmarket.p2p.tracker.model.UnansweredCreate
import com.dmarket.p2p.tracker.model.steam.SteamSentOffer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** What the account's sent offers say about an [UnansweredCreate]. */
sealed interface UnansweredCreateVerdict {
    /** Steam made the offer: [offer] is it. */
    data class Found(val offer: SteamSentOffer) : UnansweredCreateVerdict

    /** Not among the sent offers, but too soon after the attempt to conclude anything: look again later. */
    data object NotYet : UnansweredCreateVerdict

    /** Not among the sent offers, long enough after the attempt: Steam made none. */
    data object Absent : UnansweredCreateVerdict
}

/**
 * The pure half of settling a create whose request got no answer from Steam. The loop holds such a create's claim
 * in flight and, on later cycles, reads the account's sent offers; this decides what one read means.
 *
 * An offer is the create's when it is sent to the same partner, gives every asset the create gave, and was made no
 * earlier than [CLOCK_TOLERANCE] before the attempt (Steam's clock against this host's). The newest such offer wins.
 */
object UnansweredCreateCheck {
    /** How far Steam's `time_created` may run behind this host's clock and still be the attempt's offer. */
    val CLOCK_TOLERANCE: Duration = 5.minutes

    /**
     * When the check stops: the claim's [ttl] (after which the claim no longer guards the deal) or the create's
     * deadline, whichever comes first. Past it nothing is concluded, so nothing is reported against the seller.
     */
    fun closesAt(claim: DealWriteClaim, unanswered: UnansweredCreate, ttl: Duration): Instant {
        val expiry = claim.claimedAt + ttl
        return unanswered.deadline?.let { minOf(it, expiry) } ?: expiry
    }

    /** Whether the check for [claim] is over at [now]; see [closesAt]. */
    fun isClosed(claim: DealWriteClaim, unanswered: UnansweredCreate, now: Instant, ttl: Duration): Boolean =
        now >= closesAt(claim, unanswered, ttl)

    /**
     * The verdict of one read of [sentOffers] at [now]. An absence counts only once [grace] has passed since the
     * attempt ([DealWriteClaim.claimedAt]): Steam may still have been working on a request this host stopped
     * waiting for, and its offer list may lag the create.
     */
    fun evaluate(
        claim: DealWriteClaim,
        unanswered: UnansweredCreate,
        sentOffers: List<SteamSentOffer>,
        now: Instant,
        grace: Duration,
    ): UnansweredCreateVerdict {
        val earliest = claim.claimedAt - CLOCK_TOLERANCE
        val match = sentOffers
            .filter { it.partner == unanswered.partner && it.assetIdsGiven.containsAll(unanswered.assetIds) && it.createdAt >= earliest }
            .maxByOrNull { it.createdAt }
        return when {
            match != null -> UnansweredCreateVerdict.Found(match)
            now - claim.claimedAt < grace -> UnansweredCreateVerdict.NotYet
            else -> UnansweredCreateVerdict.Absent
        }
    }
}
