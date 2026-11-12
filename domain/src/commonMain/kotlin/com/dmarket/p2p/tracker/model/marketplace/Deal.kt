package com.dmarket.p2p.tracker.model.marketplace

import com.dmarket.p2p.tracker.model.AccountId
import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.SteamId
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
        /**
         * Accepts the proto name and the gateway's spelling of it: `GET /p2p/deals/{id}` serves the state with the
         * `P2P_DEAL_STATE_` prefix dropped (`COMMITTED`).
         */
        fun fromWire(name: String): P2PDealState =
            entries.firstOrNull { it.wireName == name || it.wireName == "$WIRE_PREFIX$name" } ?: UNKNOWN

        private const val WIRE_PREFIX = "P2P_DEAL_STATE_"
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
 *
 * The deal detail's own fields, all absent on an older gateway:
 * - [cancellable]: `true` only in `AWAITING_SELLER_ACCEPT`, the one state the buyer can cancel in.
 * - [nextAction]: the gateway's next-step vocabulary, verbatim (`seller_accept`, `seller_create_trade`,
 *   `buyer_accept_trade`, `await_settlement`, `none`); an unknown value is passed on, not refused.
 * - [createOffer]: the deal's pending `create_offer` directive, served while COMMITTED (and, once the seller's
 *   consent is required, only after they gave it). A host never runs it itself: the create goes through
 *   `startDelivery`.
 * - [cancelFee]: what a buyer's cancellation costs until [cancelFeeEndTime], as the service quotes it; absent is
 *   not zero.
 * - [counterparty]: the other party, relative to the caller.
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
    val cancellable: Boolean? = null,
    val nextAction: String? = null,
    val createOffer: Directive? = null,
    val cancelFee: Money? = null,
    val cancelFeeEndTime: Instant? = null,
    val counterparty: DealCounterparty? = null,
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
        "createdAt=$createdAt, updatedAt=$updatedAt, cancellable=$cancellable, nextAction=$nextAction, " +
        "createOffer=$createOffer, cancelFee=$cancelFee, cancelFeeEndTime=$cancelFeeEndTime, counterparty=$counterparty)"
}

/**
 * The other party of a deal, relative to the caller: the buyer sees the seller, the seller the buyer. [name] is the
 * Steam persona only when [steamId] is set (the gateway starts from the DMarket username); an account lookup that
 * failed leaves [accountId] alone, so an absent [steamId] means no Steam-derived identity. [accountId] is `null` when
 * the gateway sent none; the rest still names the other party.
 */
data class DealCounterparty(
    val accountId: AccountId? = null,
    val steamId: SteamId? = null,
    val name: String? = null,
    val avatar: String? = null,
    val badges: CounterpartyBadges? = null,
)

/** Steam trust signals on a [DealCounterparty], as the gateway sends them. */
data class CounterpartyBadges(
    val steamLevel: Int? = null,
    val tradingStatus: String? = null,
    val profilePrivate: Boolean? = null,
    val validTradeUrl: Boolean? = null,
)
