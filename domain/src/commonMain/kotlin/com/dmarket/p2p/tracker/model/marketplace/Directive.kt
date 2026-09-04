package com.dmarket.p2p.tracker.model.marketplace

import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.DeviceId
import com.dmarket.p2p.tracker.model.DirectiveId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.SteamId

/**
 * The one-shot commands the backend hands the tracker in a `HeartbeatResponse` (golden `Directive`).
 * Each is leased to exactly one [DeviceId] (Redis, TTL ~2-5 min);
 * the tracker executes it and reports the outcome on `POST /trade-actions` ([DirectiveOutcome]).
 *
 * The two write actions are the **only** Steam writes the tracker performs — `create_offer` (POST the
 * trade offer, stop at `CreatedNeedsConfirmation`; MFA is delegated to the official Steam app) and
 * `cancel_offer`. `report_inventory` triggers an inventory scan (R6), is seller-scoped (no `dealId`),
 * and carries the on-sale `asset_ids` to verify.
 */
enum class DirectiveAction(val wireName: String) {
    CREATE_OFFER("create_offer"),
    CANCEL_OFFER("cancel_offer"),
    REPORT_INVENTORY("report_inventory"),

    /** An action this client version doesn't recognise — ignored (never executed). */
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWire(name: String?): DirectiveAction = entries.firstOrNull { it.wireName == name } ?: UNKNOWN
    }
}

/**
 * One leased directive. [dealId] is null for [DirectiveAction.REPORT_INVENTORY] (seller-scoped). The
 * `create_offer` fields ([partnerSteamId], [assetIds], [tradeToken], [contextId]) and the
 * `cancel_offer` field ([steamOfferId]) are populated per [action]; for `report_inventory`,
 * [assetIds] are the on-sale assets to verify present.
 */
data class Directive(
    val directiveId: DirectiveId,
    val action: DirectiveAction,
    val dealId: DealId? = null,
    val partnerSteamId: SteamId? = null,
    val assetIds: List<AssetId> = emptyList(),
    val tradeToken: String? = null,
    val contextId: Int = 0,
    val steamOfferId: OfferId? = null,
) {
    /** Redacted: [tradeToken] is a bearer capability — see [com.dmarket.p2p.tracker.model.steam.TradeDraft]. */
    override fun toString(): String = "Directive(directiveId=$directiveId, action=$action, dealId=$dealId, " +
        "partnerSteamId=$partnerSteamId, assetIds=$assetIds, " +
        "tradeToken=${if (tradeToken == null) "null" else "<redacted>"}, " +
        "contextId=$contextId, steamOfferId=$steamOfferId)"
}

/** The outcome status reported back for a directive (golden `ReportDirectiveRequest.status`). */
enum class DirectiveStatus(val wireName: String) {
    SUCCESS("success"),
    NEEDS_CONFIRMATION("needs_confirmation"),
    FAILED("failed"),
}

/**
 * The result of executing a [Directive], submitted to `POST /trade-actions` (golden
 * `ReportDirectiveRequest`). Reporting it releases the device lease.
 * [steamOfferId] is set on a successful `create_offer`; [error] on a failure.
 */
data class DirectiveOutcome(
    val directiveId: DirectiveId,
    val action: DirectiveAction,
    val status: DirectiveStatus,
    val dealId: DealId? = null,
    val steamOfferId: OfferId? = null,
    val error: String? = null,
)

/** The backend's `/trade-actions` ack. */
data class DirectiveAck(val directiveId: DirectiveId, val accepted: Boolean, val reason: String? = null)

/**
 * The inventory snapshot reported on `POST /inventory` to fulfil a [DirectiveAction.REPORT_INVENTORY]
 * directive (golden `ReportInventoryRequest`). The client reports
 * only the **observed present** assets (of the directive's on-sale set) plus [scanComplete]; the
 * **backend** computes `on-sale − present = stale` and cancels. [steamId] must equal the account's
 * Steam id (wrong-account guard); [scanComplete] is `false` on a failed/partial parse so the backend
 * skips cancelling (mass-cancel guard).
 */
data class InventoryReport(
    val directiveId: DirectiveId,
    val steamId: SteamId,
    val deviceId: DeviceId,
    val scanComplete: Boolean,
    val presentAssetIds: List<AssetId>,
    val contextId: Int,
)

/** The backend's `/inventory` ack: the offers it cancelled from the diff. */
data class InventoryAck(val cancelledOfferIds: List<OfferId>, val accepted: Boolean, val reason: String? = null)
