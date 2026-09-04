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
     * directive. The `:core` actual builds only the fixed `…/tradeoffer/{id}/cancel` URL.
     */
    suspend fun cancelOffer(credential: SteamCredential, offerId: OfferId)
}
