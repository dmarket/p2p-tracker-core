package com.dmarket.p2p.tracker.model.marketplace

import com.dmarket.p2p.tracker.model.AccountId
import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.net.NetworkRedaction
import kotlin.time.Instant

/**
 * The public projection of a P2P deal's lifecycle state (`P2PDealState`, golden contract). The
 * internal Temporal workflow states are more granular; the
 * client only ever sees this projection.
 *
 * Under the golden C1 contract the tracker is **directive-driven**, not state-reactive: it watches the
 * deals named in the heartbeat's `active_tracking[]` and reports raw Steam codes — it does not decide
 * actions from these states. The enum is kept for read-out / display and for resolving which deals are
 * worth watching. [UNKNOWN] keeps the client forward-compatible with states the backend adds later
 * (the wire mapper falls back to it rather than throwing).
 */
enum class P2PDealState(val wireName: String) {
    LOCKED("P2P_DEAL_STATE_LOCKED"),
    AWAITING_SELLER_ACCEPT("P2P_DEAL_STATE_AWAITING_SELLER_ACCEPT"),
    COMMITTED("P2P_DEAL_STATE_COMMITTED"),
    AWAITING_TRADE("P2P_DEAL_STATE_AWAITING_TRADE"),
    AWAITING_TERMINAL("P2P_DEAL_STATE_AWAITING_TERMINAL"),
    VERIFYING("P2P_DEAL_STATE_VERIFYING"),
    COMPLETED("P2P_DEAL_STATE_COMPLETED"),

    /**
     * System fallback for a missing/invalid proof or no TERMINAL by deadline. The golden contract is
     * explicit that this is **NOT** the PRD buyer-dispute.
     */
    MANUAL_REVIEW("P2P_DEAL_STATE_MANUAL_REVIEW"),
    PENALTY("P2P_DEAL_STATE_PENALTY"),
    CANCELLED("P2P_DEAL_STATE_CANCELLED"),

    /** A state the backend added that this client version doesn't recognise — treated as wait. */
    UNKNOWN("UNKNOWN"),
    ;

    /**
     * Whether this deal is one the tracker watches a Steam trade for (offer/history axes) until
     * terminal — `AWAITING_TRADE` (offer being created) and `AWAITING_TERMINAL` (buyer accepting).
     * The seller's COMMIT (`AWAITING_SELLER_ACCEPT`) is a C2 (app) action, not the tracker's.
     */
    val isWatchable: Boolean
        get() = this == AWAITING_TRADE || this == AWAITING_TERMINAL

    companion object {
        fun fromWire(name: String): P2PDealState = entries.firstOrNull { it.wireName == name } ?: UNKNOWN
    }
}

/** A monetary amount (golden `Money`): minor units plus an ISO-4217 currency code. */
data class Money(val currencyCode: String, val amountCents: Long)

/**
 * One P2P deal as seen by the tracker (golden `Deal`). This is a
 * backend→client read only — the tracker never sends a `Deal`, and never sends its own account id
 * (the backend derives identity from the token).
 *
 * Only **one** Steam id is carried: [steamOfferId] (`tradeofferid`). The contract also declares a
 * history-phase `steam_trade_id`, but nothing ever bound it — it is declared with no producer, so the value
 * is always empty — and the contract now says outright not to model it. History correlation keys on Steam's
 * own `tradeid` off the offer snapshot instead, falling back to [assetId]; neither ever came from here.
 *
 * [trustedAcceptUri] is the backend-vouched Steam link the buyer (who has no plugin) accepts in — primarily
 * a C2 concern, carried here for completeness.
 */
data class Deal(
    val dealId: DealId,
    val state: P2PDealState,
    val buyerAccountId: AccountId,
    val sellerAccountId: AccountId,
    val offerId: OfferId,
    val assetId: AssetId,
    val price: Money,
    val steamOfferId: OfferId? = null,
    val reasonCode: String? = null,
    val trustedAcceptUri: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    /**
     * Redacted: [trustedAcceptUri] embeds `?partner=…&token=…`, and that `token` is a bearer capability.
     * Passed through [NetworkRedaction.redactUrl] rather than blanked, so the link's shape and its
     * non-secret params stay readable — the same treatment the network observer gives every URL.
     */
    override fun toString(): String = "Deal(dealId=$dealId, state=$state, buyerAccountId=$buyerAccountId, " +
        "sellerAccountId=$sellerAccountId, offerId=$offerId, assetId=$assetId, price=$price, " +
        "steamOfferId=$steamOfferId, reasonCode=$reasonCode, " +
        "trustedAcceptUri=${trustedAcceptUri?.let { NetworkRedaction.redactUrl(it) }}, " +
        "createdAt=$createdAt, updatedAt=$updatedAt)"
}
