package com.dmarket.p2p.tracker.model

import kotlin.jvm.JvmInline

/**
 * Strongly-typed identifiers.
 *
 * The reference passes these around as bare `string`s, which makes it easy to hand a Steam offer id
 * to something expecting a deal id. Wrapping each in a value class costs nothing at runtime and
 * makes those mix-ups a compile error.
 *
 * The C1 trade-tracker contract is `deal_id`-centric: the seller plugin
 * tracks and acts on deals keyed by [DealId]; the backend derives the account from the Bearer token.
 * A Steam trade-offer is identified by [OfferId]; the directive-lease key is the install-scoped
 * [DeviceId].
 */

/** A Steam 64-bit account id (`steamid64`). */
@JvmInline
value class SteamId(val value: String) {
    init {
        require(value.isNotBlank()) { "SteamId must not be blank" }
    }
}

/** A Steam trade-offer id (the id of an in-flight offer). */
@JvmInline
value class OfferId(val value: String) {
    init {
        require(value.isNotBlank()) { "OfferId must not be blank" }
    }
}

/**
 * A Steam **trade** id (`tradeid`) — the history-phase transfer id, distinct from an [OfferId]
 * (`tradeofferid`, the offer phase). The golden contract keys these separately: `steam_offer_id`
 * appears once the offer is created, `steam_trade_id` once it is accepted.
 */
@JvmInline
value class TradeId(val value: String) {
    init {
        require(value.isNotBlank()) { "TradeId must not be blank" }
    }
}

/** A Steam inventory asset id (the specific item instance). */
@JvmInline
value class AssetId(val value: String) {
    init {
        require(value.isNotBlank()) { "AssetId must not be blank" }
    }
}

/**
 * A DMarket P2P deal id — the backend-authoritative key for one deal in the new contract
 * (`/p2p/deals`). The seller plugin polls and acts on deals keyed by this.
 */
@JvmInline
value class DealId(val value: String) {
    init {
        require(value.isNotBlank()) { "DealId must not be blank" }
    }
}

/**
 * A DMarket account id (buyer or seller), distinct from a Steam [SteamId]. The contract keys deals on
 * `buyer_account_id` / `seller_account_id`; these are DMarket identities, not Steam ones.
 *
 * The C1 trade-tracker never sends its own account id — the backend derives identity from the Bearer
 * token — so this appears only on backend→client `Deal` reads, never in a client request.
 */
@JvmInline
value class AccountId(val value: String) {
    init {
        require(value.isNotBlank()) { "AccountId must not be blank" }
    }
}

/**
 * The id of a one-shot backend [Directive] (`create_offer` / `cancel_offer` / `report_inventory`).
 * The single-flight key: a directive is leased to exactly one [DeviceId], and the client reports its
 * outcome keyed on this id (`POST /trade-actions`).
 */
@JvmInline
value class DirectiveId(val value: String) {
    init {
        require(value.isNotBlank()) { "DirectiveId must not be blank" }
    }
}

/**
 * An install-scoped, persistent device identifier sent on every `POST /heartbeat`. It must survive
 * token refresh / re-login / restart (it is **not** a session id) — the backend leases each directive
 * to one `device_id`, so a stable value is what stops two devices from executing the same
 * `create_offer`/`cancel_offer` twice.
 */
@JvmInline
value class DeviceId(val value: String) {
    init {
        require(value.isNotBlank()) { "DeviceId must not be blank" }
    }
}
