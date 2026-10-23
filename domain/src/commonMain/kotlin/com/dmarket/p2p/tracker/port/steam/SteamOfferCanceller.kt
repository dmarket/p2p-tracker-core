package com.dmarket.p2p.tracker.port.steam

import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.steam.SteamCredential

/**
 * One of the two Steam *write* surfaces in the codebase (the other is [SteamOfferCreator]) — and it
 * can do exactly one thing: cancel a sent trade offer in response to a backend `cancel_offer`
 * directive.
 *
 * This is the structural enforcement of the hard rules. There is intentionally no `confirm`,
 * `accept`, Steam Guard, or `mobileconf` method here or anywhere — the writes can only *create* (and
 * stop at state 9) or *cancel*, never confirm. The actual in `:core` builds only the fixed
 * `…/tradeoffer/{id}/cancel` URL, so no other Steam endpoint can be reached through this port.
 *
 * Like [SteamReadClient] and [NotaryProver], it is authorised by the device-local [SteamCredential]
 * and never touches the marketplace — the audit boundary (no `MarketplaceClient` method accepts a
 * credential) is unchanged.
 */
interface SteamOfferCanceller {
    /**
     * Cancel an outgoing Steam trade offer the backend told us to wind down via a `cancel_offer`
     * directive. The `:core` actual builds only the fixed `…/tradeoffer/{id}/cancel` URL. Never throws for
     * Steam's answer or its absence: both are a [CancelOfferResult].
     */
    suspend fun cancelOffer(credential: SteamCredential, offerId: OfferId): CancelOfferResult
}

/**
 * What Steam's answer to a cancel says about the write. Read from the body on **every** HTTP status: Steam serves
 * its EResult envelope `{"success":<n>}` under a `500` as readily as under a `200`.
 */
sealed interface CancelOfferResult {
    /** The cancel landed: `{"tradeofferid":"<id>"}` on a 2xx, or EResult `1`. */
    data object Cancelled : CancelOfferResult

    /**
     * EResult `11` (InvalidState): the offer was no longer outstanding, so there was nothing to cancel. It says
     * nothing about why — cancelled, accepted, declined, expired and countered all answer the same — so it is
     * not a cancellation.
     */
    data object NotLive : CancelOfferResult

    /**
     * Whether the write landed is unknown: no response at all (a transport failure), or EResult `16` (Timeout).
     * Only a read of the offer can tell; reporting `failed` unread would run the write again.
     */
    data class Unanswered(val error: String) : CancelOfferResult

    /** The cancel did not land: a `strError`, any other EResult, or a body that is not one of Steam's answers. */
    data class Refused(val error: String) : CancelOfferResult
}
