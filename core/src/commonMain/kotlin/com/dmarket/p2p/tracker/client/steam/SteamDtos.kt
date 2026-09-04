package com.dmarket.p2p.tracker.client.steam

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/**
 * Wire DTOs for Steam's `IEconService` endpoints used by [KtorSteamReadClient].
 *
 * Steam wraps every response body in `{ "response": { … } }`, hence the two-level wrappers.
 * All timestamps are Unix epoch *seconds* (not milliseconds — note the difference from the DMarket
 * API which uses epoch millis). Asset IDs are strings despite looking numeric (they exceed JS
 * `Number.MAX_SAFE_INTEGER`).
 */

// ---- GetTradeOffers ----------------------------------------------------------------------------

@Serializable
data class GetTradeOffersWrapper(@SerialName("response") val response: GetTradeOffersResponse? = null)

/**
 * Steam returns the two directions as separate arrays, and which one a watched offer lands in depends on
 * the side of the deal this account is on — a sale is sent, a purchase is received. Both are requested and
 * merged; neither is a required key (Steam omits an empty array).
 */
@Serializable
data class GetTradeOffersResponse(
    @SerialName("trade_offers_sent") val sent: List<SteamOfferDto> = emptyList(),
    @SerialName("trade_offers_received") val received: List<SteamOfferDto> = emptyList(),
)

// ---- GetTradeOffer (single offer by tradeofferid) ----------------------------------------------

@Serializable
data class GetTradeOfferWrapper(@SerialName("response") val response: GetTradeOfferResponse? = null)

/** `{ "response": { "offer": {…} } }`; empty `response` (null [offer]) for an unknown id, like Steam. */
@Serializable
data class GetTradeOfferResponse(@SerialName("offer") val offer: SteamOfferDto? = null)

@Serializable
data class SteamOfferDto(
    @SerialName("tradeofferid") val tradeOfferId: String,
    /** 32-bit Steam account id of the counterparty. Convert to steamid64 = `76561197960265728 + this`. */
    @SerialName("accountid_other") val accountIdOther: Long,
    /** Steam `ETradeOfferState` integer. Decoded to [com.dmarket.p2p.tracker.model.OfferDisposition] by a [com.dmarket.p2p.tracker.game.GameAdapter]. */
    @SerialName("trade_offer_state") val state: Int,
    @SerialName("items_to_give") val itemsToGive: List<SteamAssetDto> = emptyList(),
    @SerialName("items_to_receive") val itemsToReceive: List<SteamAssetDto> = emptyList(),
    @SerialName("time_created") val timeCreated: Long,
    @SerialName("time_updated") val timeUpdated: Long,
    /**
     * Steam's id for the transfer this offer produced. Present **only once the offer was accepted**, and it is
     * the primary key of the matching `GetTradeHistory` row — which is what lets a watched deal correlate to
     * its transfer exactly, with no asset-id join at all (see
     * [com.dmarket.p2p.tracker.model.steam.SteamOfferSnapshot.tradeId]).
     */
    @SerialName("tradeid") val tradeId: String? = null,
)

/**
 * [classId] and [instanceId] are the item's *kind* rather than its instance, and are only read to corroborate
 * a DMarket asset ref — which is a compound of exactly these numbers, in an order the contract does not fix
 * (see [com.dmarket.p2p.tracker.model.steam.SteamTransfer.assetTokens]). Nullable because they are not needed
 * for anything else and Steam omits them on some shapes; a missing one only means one fewer corroboration.
 */
@Serializable
data class SteamAssetDto(
    @SerialName("appid") val appId: Int,
    @SerialName("contextid") val contextId: String,
    @SerialName("assetid") val assetId: String,
    @SerialName("classid") val classId: String? = null,
    @SerialName("instanceid") val instanceId: String? = null,
)

// ---- GetTradeHistory ---------------------------------------------------------------------------

@Serializable
data class GetTradeHistoryWrapper(@SerialName("response") val response: GetTradeHistoryResponse? = null)

@Serializable
data class GetTradeHistoryResponse(@SerialName("trades") val trades: List<SteamTradeDto> = emptyList())

@Serializable
data class SteamTradeDto(
    @SerialName("tradeid") val tradeId: String,
    /** Full 64-bit steamid64 of the trading partner (already expanded, unlike `GetTradeOffers`). */
    @SerialName("steamid_other") val steamIdOther: String,
    @SerialName("time_init") val timeInit: Long,
    /** Steam `ETradeStatus` integer. Decoded to [com.dmarket.p2p.tracker.model.LedgerOutcome] by a [com.dmarket.p2p.tracker.game.GameAdapter]. */
    @SerialName("status") val status: Int,
    @SerialName("assets_given") val assetsGiven: List<SteamAssetDto> = emptyList(),
    @SerialName("assets_received") val assetsReceived: List<SteamAssetDto> = emptyList(),
    /**
     * Last-modified time of the trade record. Typed [JsonPrimitive] rather than `Long` for the same
     * reason as the community inventory flags: [SteamReadResponses.json] is not lenient, so a quoted
     * numeric would abort the whole history decode and blind **every** watched deal at once. Used to
     * correlate a rolled-back trade to the notification that names who rolled it back.
     *
     * **Observed live** (as a bare JSON number) on the record a rollback flipped to `status 12`. Absent
     * on the compensating `status 3` record the same rollback adds, and absent on most ordinary rows —
     * where it simply means attribution cannot be attempted.
     */
    @SerialName("time_mod") val timeMod: JsonPrimitive? = null,
    /**
     * The trade id this record rolls back. **Observed live** (a quoted string) on the compensating record
     * Steam writes per rolled-back trade, and only there — which is why it is the discriminator
     * [com.dmarket.p2p.tracker.engine.TransferCorrelation] uses to keep a compensating record from being
     * mistaken for the deal's own transfer.
     */
    @SerialName("rollback_trade") val rollbackTrade: String? = null,
    /**
     * End of Steam's Trade-Protection window for this transfer — the per-trade settlement time, readable
     * only by a party to the trade. Typed [JsonPrimitive] for the same reason as [timeMod]: one unexpected
     * encoding must not abort the whole history decode and blind every watched deal at once.
     *
     * **Observed live** (a bare JSON number) on an ordinary completed row. Steam **clears** it when the
     * status flips to `12`, so its absence on a rolled-back row is the expected shape, not a fault — which
     * is why the window has to be captured from an earlier read or not at all.
     */
    @SerialName("time_settlement") val timeSettlement: JsonPrimitive? = null,
)

// ---- GetSteamNotifications (reversal attribution) ----------------------------------------------

@Serializable
data class GetSteamNotificationsWrapper(@SerialName("response") val response: GetSteamNotificationsResponse? = null)

@Serializable
data class GetSteamNotificationsResponse(@SerialName("notifications") val notifications: List<SteamNotificationDto> = emptyList())

/**
 * One notification. Only these three fields are mapped onward — the payload also carries `body_data`
 * (free text and counterparty ids for unrelated traffic: comments, invites, gifts, support messages),
 * which is deliberately **not** modelled so it cannot travel past the reader.
 *
 * Numeric fields are typed [JsonPrimitive] for the same non-lenient-decode reason as `time_mod`: this is
 * a rarely-exercised endpoint, and one unexpected encoding must not abort attribution with a throw.
 */
@Serializable
data class SteamNotificationDto(
    @SerialName("notification_type") val notificationType: JsonPrimitive? = null,
    /** 32-bit account id of the actor; expand to steamid64 by adding `76561197960265728`. */
    @SerialName("actor") val actor: JsonPrimitive? = null,
    @SerialName("timestamp") val timestamp: JsonPrimitive? = null,
)

// ---- Community /inventory/{steamid}/{appid}/{ctx} ----------------------------------------------

/**
 * `{ "assets": [ { "assetid": "…" }, … ], … }` — not `response`-wrapped (community endpoint, not IEconService).
 *
 * [success] and [moreItems] are typed [JsonPrimitive], **not** `Int`, on purpose: this is a community
 * endpoint (not the strongly-shaped `IEconService`), and kotlinx *throws* on a type it did not expect
 * (`"success": true` where an `Int` was declared). A throw here fails the whole decode, which the reader
 * reports as an incomplete scan — so a mere encoding change on Steam's side would silently disable
 * stale-listing cancellation for every seller. Reading the raw primitive and coercing keeps that from
 * being a cliff. See [SteamReadResponses.inventoryPage].
 *
 * [moreItems] + [lastAssetId] are Steam's paging cursor: `more_items = 1` means the page was truncated
 * and the next page starts at `start_assetid = last_assetid`.
 */
@Serializable
data class SteamInventoryResponse(
    @SerialName("assets") val assets: List<SteamInventoryAssetDto> = emptyList(),
    @SerialName("success") val success: JsonPrimitive? = null,
    @SerialName("more_items") val moreItems: JsonPrimitive? = null,
    @SerialName("last_assetid") val lastAssetId: String? = null,
    @SerialName("total_inventory_count") val totalInventoryCount: Int? = null,
)

@Serializable
data class SteamInventoryAssetDto(@SerialName("assetid") val assetId: String)
