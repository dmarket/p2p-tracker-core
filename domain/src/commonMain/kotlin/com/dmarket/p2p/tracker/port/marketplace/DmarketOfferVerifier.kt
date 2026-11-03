package com.dmarket.p2p.tracker.port.marketplace

import com.dmarket.p2p.tracker.model.OfferId
import kotlin.time.Instant

/**
 * Asks DMarket whether a Steam trade offer is one of its own (a deposit or withdrawal bot's), so a received offer
 * from an account named like a DMarket bot can be told apart from a fake one.
 *
 * [DmarketOfferAnswer.isOurs] is `true` or `false` only for an answer DMarket actually gave, and `null` when there is
 * none (a failed request, an unexpected reply). `null` must never raise a warning: the caller reads it as "unknown".
 * An implementation never throws for a failed request and never touches the account's session.
 */
fun interface DmarketOfferVerifier {
    suspend fun isOurs(offerId: OfferId): DmarketOfferAnswer
}

/**
 * DMarket's answer about one offer, and the server time of the reply (its HTTP `Date`), `null` when the reply had
 * none or a malformed one. The received-offer name check measures its grace on this server clock, so a device clock
 * change cannot shorten or stretch it.
 */
data class DmarketOfferAnswer(val isOurs: Boolean?, val serverTime: Instant? = null)
