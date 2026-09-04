package com.dmarket.p2p.tracker.wire

import com.dmarket.p2p.tracker.model.AccountId
import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.DirectiveId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.TradeId
import com.dmarket.p2p.tracker.model.marketplace.Deal
import com.dmarket.p2p.tracker.model.marketplace.DealActionResult
import com.dmarket.p2p.tracker.model.marketplace.DealRole
import com.dmarket.p2p.tracker.model.marketplace.Directive
import com.dmarket.p2p.tracker.model.marketplace.DirectiveAck
import com.dmarket.p2p.tracker.model.marketplace.DirectiveAction
import com.dmarket.p2p.tracker.model.marketplace.DirectiveOutcome
import com.dmarket.p2p.tracker.model.marketplace.HeartbeatRequest
import com.dmarket.p2p.tracker.model.marketplace.HeartbeatResponse
import com.dmarket.p2p.tracker.model.marketplace.InventoryAck
import com.dmarket.p2p.tracker.model.marketplace.InventoryReport
import com.dmarket.p2p.tracker.model.marketplace.Money
import com.dmarket.p2p.tracker.model.marketplace.P2PDealState
import com.dmarket.p2p.tracker.model.marketplace.ProofResult
import com.dmarket.p2p.tracker.model.marketplace.ProofSubmission
import com.dmarket.p2p.tracker.model.marketplace.TrackedDeal
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusReport
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusResult
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusSource
import com.dmarket.p2p.tracker.model.marketplace.WatchTarget
import kotlin.time.Instant

/**
 * Domain ⇄ wire conversions for the golden C1 contract. Kept separate from the DTOs so the JSON
 * contract is auditable at a glance. Timestamps are RFC3339 strings (`Instant.parse`/`toString`),
 * `Money.amount` is cents-as-string, `proof_payload` is opaque base64, and `account_id` is never sent.
 */

// ---- heartbeat ---------------------------------------------------------------------------------

fun HeartbeatRequest.toDto(): HeartbeatRequestDto = HeartbeatRequestDto(
    clientVersion = clientVersion,
    platform = platform,
    foreground = foreground,
    steamId = steamId.value,
    deviceId = deviceId.value,
)

fun TrackedDealDto.toDomain(): TrackedDeal = TrackedDeal(
    dealId = DealId(dealId),
    steamOfferId = steamOfferId?.takeIf { it.isNotBlank() }?.let(::OfferId),
    watch = watch.map(WatchTarget::fromWire).toSet(),
    proofRequired = proofRequired,
    role = DealRole.fromWire(role),
    lastOfferCode = lastOfferCode,
    steamTradeId = steamTradeId?.takeIf { it.isNotBlank() }?.let(::TradeId),
    proveAfter = proveAfter.toFreshnessMark(),
)

/**
 * The backend's freshness mark, or `null` for "no demand" — which is what every malformed shape degrades to.
 * Three guards, and each one is a live hazard rather than defensive habit:
 *
 *  - **blank.** The one guard here that is precaution rather than a known shape: protojson omits an unset
 *    `Timestamp` rather than spelling it `""`, so this is the gateway's hand-written REST projection being
 *    covered, not protojson. (For the sibling `steam_trade_id` the same guard is *mandatory* — that one is a
 *    proto3 **string**, which protojson does render as `""`, and `TradeId`'s `init require` throws on it.)
 *  - **unparseable.** `runCatching`, deliberately NOT the bare `Instant::parse` used for
 *    [HeartbeatResponseDto.serverTime] below. A throw here does not lose one deal: it aborts the whole
 *    [HeartbeatResponseDto.toDomain], surfaces in the loop's final `catch (_: Throwable)` as a status-less
 *    heartbeat failure, counts toward `SERVER_ERROR_THRESHOLD`, and shows the user "we lost connection with
 *    DMarket" on a healthy connection — with no tracking list, and with the next-heartbeat schedule never
 *    advanced, so every wake re-fails on the same body. Fail-open is the only safe direction: a dropped mark
 *    costs one delayed payout that the backend re-demands on its next heartbeat.
 *  - **the epoch.** A zero `google.protobuf.Timestamp` parses perfectly and reads as a mark every deal on
 *    the account is behind — i.e. one full MPC session per tracked deal per cycle, across the install base.
 *    An absent demand must be an absent field, never a zero instant.
 *
 * Truncated to whole milliseconds, so the in-memory value and the persisted one are the same value by
 * construction rather than by convention: every `Instant` this client stores round-trips through epoch
 * millis, and a nanosecond-precision mark read back from storage is strictly less than the same mark
 * re-parsed from the next heartbeat — which would defeat the monotone latch and re-prove forever. Flooring
 * puts the client's copy at most 1 ms early, which is harmless: the client never compares attestation times,
 * the mark is only its own latch key.
 */
private fun String?.toFreshnessMark(): Instant? {
    val text = this?.takeIf { it.isNotBlank() } ?: return null
    val millis = runCatching { Instant.parse(text) }.getOrNull()?.toEpochMilliseconds() ?: return null
    return if (millis > 0) Instant.fromEpochMilliseconds(millis) else null
}

fun DirectiveDto.toDomain(): Directive = Directive(
    directiveId = DirectiveId(directiveId),
    action = DirectiveAction.fromWire(action),
    dealId = dealId?.takeIf { it.isNotBlank() }?.let(::DealId),
    partnerSteamId = partnerSteamId?.takeIf { it.isNotBlank() }?.let(::SteamId),
    assetIds = assetIds.map(::AssetId),
    tradeToken = tradeToken,
    contextId = contextId,
    steamOfferId = steamOfferId?.takeIf { it.isNotBlank() }?.let(::OfferId),
)

fun HeartbeatResponseDto.toDomain(): HeartbeatResponse = HeartbeatResponse(
    activeTracking = activeTracking.map { it.toDomain() },
    directives = directives.map { it.toDomain() },
    serverTime = serverTime?.takeIf { it.isNotBlank() }?.let(Instant::parse),
    ttlSeconds = ttlSeconds,
    linkedSteamId = linkedSteamId?.takeIf { it.isNotBlank() }?.let(::SteamId),
)

// ---- trade-events (ReportTradeStatus) ----------------------------------------------------------

fun TradeStatusReport.toDto(): TradeStatusReportDto = TradeStatusReportDto(
    dealId = dealId.value,
    source = source.wireName,
    steamStatusCode = steamStatusCode,
    clientTime = clientTime.toString(),
    reversalInitiatorSteamId = reversalInitiatorSteamId?.value,
    settlementTime = settlementTime?.toString(),
)

fun List<TradeStatusReport>.toRequestDto(): ReportTradeStatusRequestDto = ReportTradeStatusRequestDto(reports = map { it.toDto() })

fun TradeStatusResultDto.toDomain(): TradeStatusResult = TradeStatusResult(
    dealId = DealId(dealId),
    accepted = accepted,
    reason = reason,
    // Tolerant like `DealRole.fromWire` / `WatchTarget.fromWire`, and for the same reason: this field is not
    // in the frozen contract, and the live gateway serialises with protojson — which renders an enum as its
    // proto name (`HISTORY`, or `TRADE_STATUS_SOURCE_HISTORY`), not the lowercase wire spelling. Matching
    // case-insensitively on the last `_`-separated segment reads all three alike. An unrecognised value maps
    // to `null` ("no opinion"), which downgrades this result to deal-level matching rather than mis-matching.
    source = source?.lowercase()?.substringAfterLast('_')?.let { token ->
        TradeStatusSource.entries.firstOrNull { it.wireName == token }
    },
)

fun ReportTradeStatusResponseDto.toDomain(): List<TradeStatusResult> = results.map { it.toDomain() }

// ---- notary (SubmitProof) ----------------------------------------------------------------------

fun ProofSubmission.toDto(): SubmitProofRequestDto = SubmitProofRequestDto(dealId = dealId.value, proofPayload = proofPayload)

fun SubmitProofResponseDto.toDomain(): ProofResult = ProofResult(dealId = DealId(dealId), verified = verified, reason = reason)

// ---- trade-actions (ReportDirective) -----------------------------------------------------------

fun DirectiveOutcome.toDto(): ReportDirectiveRequestDto = ReportDirectiveRequestDto(
    directiveId = directiveId.value,
    dealId = dealId?.value,
    action = action.wireName,
    status = status.wireName,
    steamOfferId = steamOfferId?.value,
    error = error,
)

fun ReportDirectiveResponseDto.toDomain(): DirectiveAck =
    DirectiveAck(directiveId = DirectiveId(directiveId), accepted = accepted, reason = reason)

fun List<DirectiveOutcome>.toRequestDto(): ReportDirectivesRequestDto = ReportDirectivesRequestDto(reports = map { it.toDto() })

fun ReportDirectivesResponseDto.toDomain(): List<DirectiveAck> = results.map { it.toDomain() }

// ---- inventory (ReportInventory) ---------------------------------------------------------------

fun InventoryReport.toDto(): ReportInventoryRequestDto = ReportInventoryRequestDto(
    directiveId = directiveId.value,
    steamId = steamId.value,
    deviceId = deviceId.value,
    scanComplete = scanComplete,
    presentAssetIds = presentAssetIds.map { it.value },
    contextId = contextId,
)

fun ReportInventoryResponseDto.toDomain(): InventoryAck = InventoryAck(
    cancelledOfferIds = cancelledOfferIds.map(::OfferId),
    accepted = accepted,
    reason = reason,
)

// ---- deals (C2 reads) --------------------------------------------------------------------------

fun MoneyDto.toDomain(): Money = Money(currencyCode = currency, amountCents = amount.toLong())

fun Money.toDto(): MoneyDto = MoneyDto(currency = currencyCode, amount = amountCents.toString())

/** Unknown states degrade to [P2PDealState.UNKNOWN], never crash. */
fun DealDto.toDomain(): Deal = Deal(
    dealId = DealId(dealId),
    state = P2PDealState.fromWire(state),
    buyerAccountId = AccountId(buyerAccountId),
    sellerAccountId = AccountId(sellerAccountId),
    offerId = OfferId(offerId),
    assetId = AssetId(assetId),
    price = price.toDomain(),
    steamOfferId = steamOfferId?.takeIf { it.isNotBlank() }?.let(::OfferId),
    reasonCode = reasonCode,
    // Prefer the current spelling, fall back to the one the deal detail served before it was aligned onto
    // the proto field. Reading only one of the two blanks the buyer's accept link on one side of the deploy.
    trustedAcceptUri = (trustedAcceptUri ?: trustedAcceptUrlLegacy)?.takeIf { it.isNotBlank() },
    createdAt = Instant.parse(createTime),
    updatedAt = Instant.parse(updateTime),
)

fun DealActionResponseDto.toDomain(): DealActionResult = DealActionResult(
    state = P2PDealState.fromWire(state),
    applied = applied,
    reasonCode = reasonCode,
)
