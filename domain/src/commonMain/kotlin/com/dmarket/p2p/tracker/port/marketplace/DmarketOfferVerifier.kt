package com.dmarket.p2p.tracker.port.marketplace

import com.dmarket.p2p.tracker.model.OfferId

/**
 * Asks DMarket whether a Steam trade offer is one of its own (a deposit or withdrawal bot's), so a received offer
 * from an account named like a DMarket bot can be told apart from a fake one.
 *
 * Returns `true` or `false` only for an answer DMarket actually gave, and `null` when there is none (a failed
 * request, an unexpected reply). `null` must never raise a warning: the caller reads it as "unknown". An
 * implementation never throws for a failed request and never touches the account's session.
 */
fun interface DmarketOfferVerifier {
    suspend fun isOurs(offerId: OfferId): Boolean?
}
