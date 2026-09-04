package com.dmarket.p2p.tracker.debug

import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.DeviceId
import com.dmarket.p2p.tracker.model.DirectiveId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.marketplace.DirectiveAction
import com.dmarket.p2p.tracker.model.marketplace.DirectiveOutcome
import com.dmarket.p2p.tracker.model.marketplace.DirectiveStatus
import com.dmarket.p2p.tracker.model.marketplace.InventoryReport
import com.dmarket.p2p.tracker.model.marketplace.ProofSubmission
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusReport
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusSource
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.time.Instant

/**
 * The **client half of a C1 conformance check**: each of the four report/write requests of the
 * trade-tracker contract, issued one at a time through a real [MarketplaceClient].
 *
 * Point it at a backend and you learn whether that backend and this client agree on the wire — the
 * request is serialized by the production DTOs and the reply is read back by the production
 * deserializer, so a renamed or re-cased field surfaces as a missing value in the returned JSON
 * rather than as a green test. That is the one thing a `MockEngine` unit test cannot tell you, and it
 * is why these exist as callable probes instead of only as steps the loop takes on its own cadence:
 * the loop reaches a write endpoint only after the deal state that makes it decide to, which is not
 * something a conformance run can arrange.
 *
 * **Platform-free on purpose.** It takes the port, not a browser, so the same code is exercised by
 * `commonTest` against a Ktor `MockEngine` (see `C1ReportProbesTest`) and by the Chrome debug console
 * (see `DebugSession` in `jsMain`, which adapts these to `@JsExport`-able signatures). Nothing here
 * touches a credential, a vault or `chrome.*`.
 *
 * **Arguments are wire primitives, not domain types** — partly because `@JsExport` cannot export value
 * classes, but mainly because passing a deliberately malformed value is a case worth probing. The id
 * value classes reject it (`DealId("")` throws), and the caller sees the failure.
 *
 * **No input is ever coerced.** An unrecognised `action`/`status`/`source` throws
 * [IllegalArgumentException] naming the accepted set; it is never quietly replaced with a default.
 * A probe that silently rewrites a typo into a valid-but-different request is worse than no probe:
 * the request goes through, the backend accepts it, and the run is green about something nobody asked.
 * The two axes of `source` in particular share a numeric code space (see [TradeStatusSource]), so
 * guessing it would change the *meaning* of a reported status code.
 */
class C1ReportProbes(private val marketplace: MarketplaceClient) {

    /**
     * One `POST /trade-events` carrying a single status observation (C1 `ReportTradeStatus`).
     *
     * @param source the Steam axis the code came from — `offer` or `history`, exactly.
     * @param clientTimeIso RFC-3339 / ISO-8601 instant, e.g. `2026-01-01T00:00:00Z`.
     */
    suspend fun reportTradeStatus(dealId: String, source: String, steamStatusCode: Int, clientTimeIso: String): JsonObject =
        reportTradeStatusBatch(listOf(dealId), source, steamStatusCode, clientTimeIso)

    /**
     * The batched form of [reportTradeStatus]: one request carrying one report per entry of [dealIds].
     *
     * `/trade-events` is the only C1 endpoint whose body is a **collection**, so the multi-entry shape
     * (the container field name, and how repeated objects encode) is reachable only through this
     * method — a batch of one never exercises it.
     */
    suspend fun reportTradeStatusBatch(dealIds: List<String>, source: String, steamStatusCode: Int, clientTimeIso: String): JsonObject {
        require(dealIds.isNotEmpty()) { "reportTradeStatus needs at least one deal id" }
        val axis = tradeStatusSource(source)
        val clientTime = instant(clientTimeIso)
        val reports = dealIds.map {
            TradeStatusReport(dealId = DealId(it), source = axis, steamStatusCode = steamStatusCode, clientTime = clientTime)
        }
        val results = marketplace.reportTradeStatus(reports)
        return buildJsonObject {
            put("ok", true)
            put("sent", reports.size)
            putJsonArray("results") {
                for (r in results) {
                    addJsonObject {
                        put("dealId", r.dealId.value)
                        put("accepted", r.accepted)
                        put("reason", r.reason)
                    }
                }
            }
        }
    }

    /**
     * One `POST /trade-actions` reporting how a directive ended (C1 `ReportDirective`) — the call that
     * releases the device lease.
     *
     * @param action / @param status exact wire names; anything else is rejected, not defaulted.
     * @param error the failure detail the backend reads on `status=failed`. Omitted from the body when null.
     */
    suspend fun reportDirective(
        directiveId: String,
        dealId: String?,
        action: String,
        status: String,
        steamOfferId: String?,
        error: String? = null,
    ): JsonObject {
        val outcome = DirectiveOutcome(
            directiveId = DirectiveId(directiveId),
            action = directiveAction(action),
            status = directiveStatus(status),
            dealId = dealId?.let { DealId(it) },
            steamOfferId = steamOfferId?.let { OfferId(it) },
            error = error,
        )
        // `/trade-actions` takes a batch; this probe deliberately sends a one-element one so a human can poke a
        // single directive, and reads the single result back out.
        val ack = marketplace.reportDirectives(listOf(outcome)).firstOrNull()
        return buildJsonObject {
            put("ok", ack != null)
            put("directiveId", ack?.directiveId?.value ?: directiveId)
            put("accepted", ack?.accepted == true)
            put("reason", ack?.reason)
        }
    }

    /**
     * One `POST /inventory` carrying an inventory snapshot (C1 `ReportInventory`).
     *
     * [deviceId] is taken from the caller rather than read from a device-id store: a conformance run
     * needs a deterministic value, and the store is a browser actual. In the real loop it is always
     * the device that holds the directive's lease.
     */
    suspend fun reportInventory(
        directiveId: String,
        steamId: String,
        deviceId: String,
        scanComplete: Boolean,
        presentAssetIds: List<String>,
        contextId: Int,
    ): JsonObject {
        val report = InventoryReport(
            directiveId = DirectiveId(directiveId),
            steamId = SteamId(steamId),
            deviceId = DeviceId(deviceId),
            scanComplete = scanComplete,
            presentAssetIds = presentAssetIds.map { AssetId(it) },
            contextId = contextId,
        )
        val ack = marketplace.reportInventory(report)
        return buildJsonObject {
            put("ok", true)
            put("accepted", ack.accepted)
            put("reason", ack.reason)
            putJsonArray("cancelledOfferIds") { for (o in ack.cancelledOfferIds) add(o.value) }
        }
    }

    /** One `POST /notary` submitting a proof for a deal (C1 `SubmitProof`). */
    suspend fun submitProof(dealId: String, proofPayload: String): JsonObject {
        val result = marketplace.submitProof(ProofSubmission(dealId = DealId(dealId), proofPayload = proofPayload))
        return buildJsonObject {
            put("ok", true)
            put("dealId", result.dealId.value)
            put("verified", result.verified)
            put("reason", result.reason)
        }
    }

    // ---- strict wire-name lookups ------------------------------------------------------------------
    //
    // Deliberately local to this dev-only class rather than added to :domain. The production inbound
    // path wants LENIENT parsing (DirectiveAction.fromWire maps an unknown action to UNKNOWN so a
    // newer backend cannot break an older client); a probe wants the opposite. Keeping the strict form
    // here means the published :domain surface does not grow an API that only a debug tool needs.

    private fun tradeStatusSource(wire: String): TradeStatusSource = TradeStatusSource.entries.firstOrNull { it.wireName == wire }
        ?: rejectUnknown("trade-status source", wire, TradeStatusSource.entries.map { it.wireName })

    private fun directiveAction(wire: String): DirectiveAction {
        // UNKNOWN is an inbound-only sentinel — reporting it back as an outcome would be meaningless.
        val reportable = DirectiveAction.entries.filter { it != DirectiveAction.UNKNOWN }
        return reportable.firstOrNull { it.wireName == wire }
            ?: rejectUnknown("directive action", wire, reportable.map { it.wireName })
    }

    private fun directiveStatus(wire: String): DirectiveStatus = DirectiveStatus.entries.firstOrNull { it.wireName == wire }
        ?: rejectUnknown("directive status", wire, DirectiveStatus.entries.map { it.wireName })

    private fun rejectUnknown(kind: String, wire: String, accepted: List<String>): Nothing =
        throw IllegalArgumentException("unknown $kind '$wire'; expected one of ${accepted.joinToString(", ")}")

    private fun instant(iso: String): Instant = try {
        Instant.parse(iso)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("clientTime '$iso' is not an ISO-8601 instant (e.g. 2026-01-01T00:00:00Z)", e)
    }
}
