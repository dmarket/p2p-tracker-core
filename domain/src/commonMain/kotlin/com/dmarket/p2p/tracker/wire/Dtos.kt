package com.dmarket.p2p.tracker.wire

import com.dmarket.p2p.tracker.net.NetworkRedaction
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * JSON wire DTOs for the golden **C1 trade-tracker** REST contract under `/exchange/v1/p2p/ext/`
 * (`heartbeat`, `trade-events`, `notary`, `trade-actions`, `inventory`) plus the C2 deal reads the
 * host may proxy.
 *
 * Shape rules: fields are **lowerCamelCase**. The golden contract text says `snake_case`, but the
 * live `exchange-gateway` serialises protobuf via **protojson**, which emits camelCase JSON
 * (`ttlSeconds`, `serverTime`, `directiveId`, …) and accepts either casing on input. These DTOs match
 * the wire the backend actually produces; a snake_case-keyed client silently drops every response
 * field (`ignoreUnknownKeys`) and fails to decode required ones. The contract's `snake_case` line
 * is a documentation discrepancy, not this.
 *
 * All Steam/account/deal ids are **strings** (64-bit Steam ids exceed JS safe-integer range — a
 * number-typed deserialize silently corrupts them); `Money{currency, amount}` with amount =
 * **cents-as-string**; timestamps are RFC3339 strings (`google.protobuf.Timestamp`); `proofPayload`
 * is base64 of `bytes`. Mappers in `P2pMappers.kt` bridge to the domain. The client **never** sends
 * its own `accountId` — the backend derives identity from the Bearer token.
 */

// ---- POST /heartbeat ---------------------------------------------------------------------------

@Serializable
data class HeartbeatRequestDto(
    @SerialName("clientVersion") val clientVersion: String,
    @SerialName("platform") val platform: String,
    @SerialName("foreground") val foreground: Boolean,
    @SerialName("steamId") val steamId: String,
    @SerialName("deviceId") val deviceId: String,
)

@Serializable
data class TrackedDealDto(
    @SerialName("dealId") val dealId: String,
    @SerialName("steamOfferId") val steamOfferId: String? = null,
    @SerialName("watch") val watch: List<String> = emptyList(),
    @SerialName("proofRequired") val proofRequired: Boolean = false,
    /**
     * `"buyer"` | `"seller"` — which side of this deal the authenticated account is on, since the backend
     * serves the watch to both participants. **Ahead of the frozen contract:** the live backend sends it,
     * the golden `TrackedDeal` does not declare it yet, so it is nullable here and maps to
     * [com.dmarket.p2p.tracker.model.marketplace.DealRole.UNKNOWN] when absent — never a required field.
     */
    @SerialName("role") val role: String? = null,
    /**
     * Last settled offer-axis code the backend holds for this deal — see
     * [com.dmarket.p2p.tracker.model.marketplace.TrackedDeal.lastOfferCode].
     *
     * **Ahead of the frozen contract**, like [role] was: nullable here so a backend that does not send it is
     * indistinguishable from one that has nothing to report, and the client keeps today's behaviour either way.
     */
    @SerialName("lastOfferCode") val lastOfferCode: Int? = null,
    /**
     * Steam's `tradeid` for the trade this deal settled into (golden `steam_trade_id`, field 6) — the trade a
     * [proveAfter] mark is asking to be re-attested. See
     * [com.dmarket.p2p.tracker.model.marketplace.TrackedDeal.steamTradeId].
     *
     * **Ahead of the frozen contract**, like [role] and [lastOfferCode]. Kept as a `String` rather than mapped
     * here because protojson renders an unset proto3 string as `""`, and `TradeId`'s own `init require` throws
     * on a blank — inside `toDomain()` that throw takes the WHOLE heartbeat down, not one deal (see
     * [P2pMappers][com.dmarket.p2p.tracker.wire.toDomain]).
     */
    @SerialName("steamTradeId") val steamTradeId: String? = null,
    /**
     * The freshness mark: RFC3339, the instant a proof of this deal's watch axis must be attested at or after
     * (golden `prove_after`, field 7). Absent ⇒ no demand, i.e. today's behaviour. See
     * [com.dmarket.p2p.tracker.model.marketplace.TrackedDeal.proveAfter].
     *
     * A `String` for the same reason as [steamTradeId], and a sharper one: `Instant.parse` throws on a
     * malformed value, and one such value in one entry would abort the decode of every deal, every directive
     * and the TTL. The parse lives in the mapper, guarded.
     */
    @SerialName("proveAfter") val proveAfter: String? = null,
)

@Serializable
data class DirectiveDto(
    @SerialName("directiveId") val directiveId: String,
    @SerialName("dealId") val dealId: String? = null,
    @SerialName("action") val action: String,
    @SerialName("partnerSteamId") val partnerSteamId: String? = null,
    @SerialName("assetIds") val assetIds: List<String> = emptyList(),
    @SerialName("tradeToken") val tradeToken: String? = null,
    @SerialName("contextId") val contextId: Int = 0,
    @SerialName("steamOfferId") val steamOfferId: String? = null,
) {
    /**
     * Redacted: [tradeToken] is a bearer capability. This is also what `HeartbeatResponseDto.toString()`
     * prints, so an interpolated heartbeat response would otherwise dump every leased deal's token.
     * Serialization is unaffected — the wire still carries the value.
     */
    override fun toString(): String = "DirectiveDto(directiveId=$directiveId, dealId=$dealId, action=$action, " +
        "partnerSteamId=$partnerSteamId, assetIds=$assetIds, " +
        "tradeToken=${if (tradeToken == null) "null" else "<redacted>"}, " +
        "contextId=$contextId, steamOfferId=$steamOfferId)"
}

@Serializable
data class HeartbeatResponseDto(
    @SerialName("activeTracking") val activeTracking: List<TrackedDealDto> = emptyList(),
    @SerialName("directives") val directives: List<DirectiveDto> = emptyList(),
    @SerialName("serverTime") val serverTime: String? = null,
    @SerialName("ttlSeconds") val ttlSeconds: Int = 0,
    @SerialName("linkedSteamId") val linkedSteamId: String? = null,
)

// ---- POST /trade-events (ReportTradeStatus — raw codes, no proof) ------------------------------

@Serializable
data class TradeStatusReportDto(
    @SerialName("dealId") val dealId: String,
    @SerialName("source") val source: String, // "offer" | "history"
    @SerialName("steamStatusCode") val steamStatusCode: Int,
    @SerialName("clientTime") val clientTime: String, // RFC3339
    /**
     * Who reversed the trade, on history-`12` reports only — a **claim**, validated backend-side as a
     * steamid64, ignored when empty, and admissible as an input to attribution but never as authorization
     * for a forfeit. `null` is omitted by [com.dmarket.p2p.tracker.wire.TrackerJson], so an ordinary
     * status report serialises byte-identically to before this field existed.
     */
    @SerialName("reversalInitiatorSteamId") val reversalInitiatorSteamId: String? = null,
    /**
     * Steam's `time_settlement` for this trade — the end of its Trade-Protection window — on history reports
     * only, as RFC3339 (**not** the unix seconds Steam publishes; the conversion is this client's).
     *
     * Self-reported, and validated backend-side: bounded to ±30 days of now and admissible only to **extend**
     * a recorded window, never shorten it. Neither rule is copied here — a client-side bound would only
     * suppress values the backend would have accepted. `null` is omitted by
     * [com.dmarket.p2p.tracker.wire.TrackerJson]; the backend reads an absent value as "window not
     * established", never as "the window has passed".
     */
    @SerialName("settlementTime") val settlementTime: String? = null, // RFC3339
)

@Serializable
data class ReportTradeStatusRequestDto(@SerialName("reports") val reports: List<TradeStatusReportDto> = emptyList())

@Serializable
data class TradeStatusResultDto(
    @SerialName("dealId") val dealId: String,
    @SerialName("accepted") val accepted: Boolean = false,
    @SerialName("reason") val reason: String? = null,
    /**
     * Which axis this result answers for (`"offer"` | `"history"`), when the backend says. **Not in the
     * frozen contract yet** — a batch legitimately carries both axes of the same deal, and without this the
     * two are indistinguishable, so a result can only be matched to its report by deal. Nullable and
     * ignored when absent: a `dealId`-only result still matches, it just cannot disambiguate the axes.
     */
    @SerialName("source") val source: String? = null,
)

@Serializable
data class ReportTradeStatusResponseDto(@SerialName("results") val results: List<TradeStatusResultDto> = emptyList())

// ---- POST /notary (SubmitProof — decisive set; impl-deferred for MVP) --------------------------

@Serializable
data class SubmitProofRequestDto(
    @SerialName("dealId") val dealId: String,
    @SerialName("proofPayload") val proofPayload: String, // base64 of postcard Presentation
)

@Serializable
data class SubmitProofResponseDto(
    @SerialName("dealId") val dealId: String,
    @SerialName("verified") val verified: Boolean = false,
    @SerialName("reason") val reason: String? = null,
)

// ---- POST /trade-actions (ReportDirectives — batched create/cancel outcomes, `reports` envelope) -

/** One directive outcome; the element type of the [ReportDirectivesRequestDto] batch. */
@Serializable
data class ReportDirectiveRequestDto(
    @SerialName("directiveId") val directiveId: String,
    @SerialName("dealId") val dealId: String? = null,
    @SerialName("action") val action: String,
    @SerialName("status") val status: String, // "success" | "needs_confirmation" | "failed"
    @SerialName("steamOfferId") val steamOfferId: String? = null,
    @SerialName("error") val error: String? = null,
)

/**
 * The request body: **a batch of outcomes** under a `reports` envelope — the same field name `/trade-events`
 * uses ([ReportTradeStatusRequestDto]), so the two reporting endpoints are spelled identically.
 *
 * A cycle can execute many directives — one `create_offer` per counterparty chain step, plus a `cancel_offer`
 * per leased cancel — and reporting each in its own POST meant a request per write (101 in the session that
 * motivated this). One call per cycle carries the same information.
 */
@Serializable
data class ReportDirectivesRequestDto(@SerialName("reports") val reports: List<ReportDirectiveRequestDto> = emptyList())

/** One directive's ack; the element type of the [ReportDirectivesResponseDto] batch. */
@Serializable
data class ReportDirectiveResponseDto(
    @SerialName("directiveId") val directiveId: String,
    @SerialName("accepted") val accepted: Boolean = false,
    @SerialName("reason") val reason: String? = null,
)

/**
 * The response body: one result per submitted report, matched back by `directiveId` (unique within a request,
 * so the pairing is exact — unlike `/trade-events`, where one deal can appear on two axes, which is why that
 * endpoint needs `ReportAcknowledgement`'s elimination pass and this one does not). A report with no result is
 * read as **not accepted**, so its outcome stays stored for a later resend rather than being silently
 * forgotten. See `com.dmarket.p2p.tracker.engine.DirectiveAcknowledgement`.
 */
@Serializable
data class ReportDirectivesResponseDto(@SerialName("results") val results: List<ReportDirectiveResponseDto> = emptyList())

// ---- POST /inventory (ReportInventory — R6 staleness; backend computes the diff) ---------------

@Serializable
data class ReportInventoryRequestDto(
    @SerialName("directiveId") val directiveId: String,
    @SerialName("steamId") val steamId: String, // MUST == account.steamId (wrong-account guard)
    @SerialName("deviceId") val deviceId: String,
    @SerialName("scanComplete") val scanComplete: Boolean = false,
    @SerialName("presentAssetIds") val presentAssetIds: List<String> = emptyList(),
    @SerialName("contextId") val contextId: Int = 0,
)

@Serializable
data class ReportInventoryResponseDto(
    @SerialName("cancelledOfferIds") val cancelledOfferIds: List<String> = emptyList(),
    @SerialName("accepted") val accepted: Boolean = false,
    @SerialName("reason") val reason: String? = null,
)

// ---- C2 deal reads (proxied by the host) -------------------------------------------------------

@Serializable
data class MoneyDto(
    @SerialName("currency") val currency: String,
    @SerialName("amount") val amount: String, // ISO-4217 code; amount = cents-as-string ("607")
)

@Serializable
data class DealDto(
    @SerialName("dealId") val dealId: String,
    @SerialName("state") val state: String,
    @SerialName("buyerAccountId") val buyerAccountId: String,
    @SerialName("sellerAccountId") val sellerAccountId: String,
    @SerialName("offerId") val offerId: String,
    @SerialName("assetId") val assetId: String,
    @SerialName("price") val price: MoneyDto,
    @SerialName("steamOfferId") val steamOfferId: String? = null,
    @SerialName("reasonCode") val reasonCode: String? = null,
    /**
     * The backend-vouched accept link, under the spelling both producers now speak (the proto field is
     * `trusted_accept_uri` and the feed row projects it verbatim).
     */
    @SerialName("trustedAcceptUri") val trustedAcceptUri: String? = null,
    /**
     * The **previous** spelling of the same value, kept as a read fallback. The deal detail served
     * `trustedAcceptUrl` until it was aligned onto the proto spelling, so an environment that has not taken
     * that change yet still answers with this key — and reading only one of the two would decode the accept
     * link to `null` on one side of the deploy, silently. Never populated when [trustedAcceptUri] is present;
     * resolve through [com.dmarket.p2p.tracker.model.marketplace.Deal.trustedAcceptUri].
     */
    @SerialName("trustedAcceptUrl") val trustedAcceptUrlLegacy: String? = null,
    @SerialName("createTime") val createTime: String, // RFC3339
    @SerialName("updateTime") val updateTime: String, // RFC3339
) {
    /** Redacted: the accept link embeds a bearer `token=` — see [com.dmarket.p2p.tracker.model.marketplace.Deal]. */
    override fun toString(): String = "DealDto(dealId=$dealId, state=$state, buyerAccountId=$buyerAccountId, " +
        "sellerAccountId=$sellerAccountId, offerId=$offerId, assetId=$assetId, price=$price, " +
        "steamOfferId=$steamOfferId, reasonCode=$reasonCode, " +
        "trustedAcceptUri=${(trustedAcceptUri ?: trustedAcceptUrlLegacy)?.let { NetworkRedaction.redactUrl(it) }}, " +
        "createTime=$createTime, updateTime=$updateTime)"
}

@Serializable
data class DealActionResponseDto(
    @SerialName("state") val state: String,
    @SerialName("applied") val applied: Boolean = false,
    @SerialName("reasonCode") val reasonCode: String? = null,
)
