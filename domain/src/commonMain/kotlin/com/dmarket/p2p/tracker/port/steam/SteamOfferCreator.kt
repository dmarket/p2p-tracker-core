package com.dmarket.p2p.tracker.port.steam

import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.model.steam.TradeDraft
import com.dmarket.p2p.tracker.policy.SteamCreateFailureCause
import com.dmarket.p2p.tracker.policy.ThrottleScope

/**
 * A Steam *write* surface that POSTs a new trade offer for a committed deal in
 * [com.dmarket.p2p.tracker.model.marketplace.P2PDealState.AWAITING_TRADE]. Together with
 * [SteamOfferCanceller] these are the **only** two Steam writes.
 *
 * Hard-rule boundary: this *creates* an offer (`tradeoffer/new`), leaving it in Steam's
 * `CreatedNeedsConfirmation` state (9). It **never confirms** — confirmation is the user's job on the
 * official Steam app (deep link). There is intentionally no `confirm`/`accept`/Guard-code/`mobileconf`
 * method here or anywhere; the actual builds only the fixed `…/tradeoffer/new/send` create endpoint,
 * so no confirm or `mobileconf` URL can be reached through this port.
 *
 * Like the other Steam ports it is authorised by the device-local [SteamCredential] and never touches
 * the marketplace — the audit boundary (no `MarketplaceClient` method accepts a credential) is unchanged.
 */
interface SteamOfferCreator {
    /**
     * POST a new Steam trade offer described by [draft]. Returns the created offer in
     * `CreatedNeedsConfirmation` — never confirmed by the client.
     */
    suspend fun createOffer(credential: SteamCredential, draft: TradeDraft): CreateOfferResult
}

/** The outcome of a create-trade POST. */
sealed interface CreateOfferResult {
    /** Offer POSTed, awaiting the user's mobile MFA confirm (Steam state 9). The common path. */
    data class NeedsConfirmation(val offerId: OfferId) : CreateOfferResult

    /** Offer already live without a confirm step (rare; e.g. no mobile authenticator). */
    data class Created(val offerId: OfferId) : CreateOfferResult

    /**
     * Steam rejected the create. [error] carries the rejection detail verbatim — free-form third-party
     * text — and [cause] the client's own coded reading of it, which is what a host should render and the
     * only half of the pair that is safe to hand to an untrusted page.
     *
     * [cause] defaults to [SteamCreateFailureCause.OTHER] because a [SteamOfferCreator] actual cannot fill
     * it: the marker vocabulary that names a refusal is host-suppliable config
     * ([com.dmarket.p2p.tracker.config.SteamWriteConfig]) held by the loop, which classifies the result the
     * moment the write returns.
     */
    data class Failed(val error: String, val cause: SteamCreateFailureCause = SteamCreateFailureCause.OTHER) : CreateOfferResult

    /**
     * Blocked **before any Steam write**: the Steam id linked to this DMarket account ([linkedSteamId])
     * is not the account whose token the client holds ([tokenSteamId]) — a wrong-account session. Distinct
     * from [Failed] (a Steam-side rejection) so the caller can surface a "log into the correct Steam
     * account" message rather than a generic create failure. Both ids are public (no credential leaks).
     */
    data class AccountMismatch(val linkedSteamId: SteamId, val tokenSteamId: SteamId) : CreateOfferResult

    /**
     * Suppressed **before any Steam write**: this device already created the offer for this deal, and
     * [offerId] is that existing offer. A duplicate request (the same deal asked for twice, under the
     * same or a fresh `directive_id`) is answered with the *first* result rather than a second live
     * trade offer — see [com.dmarket.p2p.tracker.engine.DealWriteGuard].
     */
    data class AlreadyCreated(val offerId: OfferId) : CreateOfferResult

    /**
     * Suppressed **before any Steam write**: a create for [dealId] is still in flight on this device, so
     * there is no offer id to hand back yet. The caller should wait for the in-flight create's own result
     * rather than retry.
     */
    data class CreateInFlight(val dealId: DealId) : CreateOfferResult

    /**
     * Deferred **before any Steam write**: the create surface is under back-pressure after Steam refused an
     * earlier create — either for this partner alone or for the whole surface ([scope]) — and will not be
     * pushed again for another [retryAfterSeconds]. Distinct from [Failed] because nothing was sent and
     * nothing is wrong with *this* request: a host can say "try again in N minutes" instead of surfacing a
     * failure the user cannot act on. See [com.dmarket.p2p.tracker.policy.SteamWriteThrottle].
     */
    data class Throttled(val scope: ThrottleScope, val retryAfterSeconds: Int) : CreateOfferResult
}
