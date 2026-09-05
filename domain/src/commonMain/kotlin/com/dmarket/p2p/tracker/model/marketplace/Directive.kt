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

    /**
     * An action this client version doesn't recognise — never executed, and **reported** as
     * [DirectiveStatus.UNSUPPORTED] so the lease is released rather than re-served forever. The wire
     * string it arrived as is kept on [Directive.rawAction], because [wireName] here is this client's
     * placeholder and naming it in the report would tell the backend nothing about what it asked for.
     */
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
    /**
     * The `action` exactly as it arrived on the wire, or `null` for a directive this build did not
     * receive from a mapper (hand-built, in tests).
     *
     * Kept only so an unrecognised action can be echoed back verbatim in its
     * [DirectiveStatus.UNSUPPORTED] report: [action] has already collapsed to
     * [DirectiveAction.UNKNOWN] by then, and a report naming `"unknown"` would hand the backend this
     * client's own placeholder instead of the command it leased — which is the one fact that makes
     * the report actionable on their side.
     */
    val rawAction: String? = null,
) {
    /** Redacted: [tradeToken] is a bearer capability — see [com.dmarket.p2p.tracker.model.steam.TradeDraft]. */
    override fun toString(): String = "Directive(directiveId=$directiveId, action=$action, dealId=$dealId, " +
        "partnerSteamId=$partnerSteamId, assetIds=$assetIds, " +
        "tradeToken=${if (tradeToken == null) "null" else "<redacted>"}, " +
        "contextId=$contextId, steamOfferId=$steamOfferId, rawAction=$rawAction)"
}

/**
 * The outcome status reported back for a directive (golden `ReportDirectiveRequest.status`).
 *
 * The wire field is a plain string with a `min_len 1` validator, not a proto enum, so the vocabulary
 * extends without a schema change on either side. This enum is nevertheless **closed** — there is no
 * `UNKNOWN` fallback as there is on [DirectiveAction] — and that asymmetry is deliberate: the client
 * only ever *produces* a status, so a value it cannot name is a value it cannot have sent. The one
 * cost is on a **downgrade**: a build without an entry cannot parse a persisted outcome a newer build
 * wrote, and drops the stored result it would have replayed on a re-lease.
 *
 * [UNSUPPORTED] and [MALFORMED] carry no Steam write and never will. They exist because a directive
 * this client refuses is otherwise invisible to the backend, which re-leases it on every heartbeat
 * while the deal stands still. Reporting is the only thing that ends that, and the two are kept
 * apart rather than folded into one token because they say whose bug it is: a rollout ordering
 * problem on our side, or a payload we cannot use on theirs.
 *
 * Neither may be reported as [FAILED]. The backend reads that as "understood, tried, Steam refused",
 * which deliberately holds the seller at fault — so a version skew arriving under it would blame a
 * seller for our own build.
 */
enum class DirectiveStatus(val wireName: String) {
    SUCCESS("success"),
    NEEDS_CONFIRMATION("needs_confirmation"),
    FAILED("failed"),

    /** The action is not one this build knows — see [DirectiveAction.UNKNOWN]. Nothing was attempted. */
    UNSUPPORTED("unsupported"),

    /**
     * The action is known but its payload is unusable for it (a `create_offer` with no partner, a
     * `cancel_offer` with no offer id, …), so there was nothing valid to attempt.
     *
     * Reported **only** for that case. A directive the planner refused because another write for the
     * same deal was already claimed in the same batch is well-formed, and answering it here would
     * end a lease the client still needs re-served.
     */
    MALFORMED("malformed"),
}

/**
 * The result of executing a [Directive], submitted to `POST /trade-actions` (golden
 * `ReportDirectiveRequest`). Reporting it releases the device lease.
 * [steamOfferId] is set on a successful `create_offer`; [error] on a failure.
 *
 * Not every outcome is an execution: [DirectiveStatus.UNSUPPORTED] and [DirectiveStatus.MALFORMED]
 * report a directive that was *refused*, where the report itself is the whole of the client's answer.
 */
data class DirectiveOutcome(
    val directiveId: DirectiveId,
    val action: DirectiveAction,
    val status: DirectiveStatus,
    val dealId: DealId? = null,
    val steamOfferId: OfferId? = null,
    val error: String? = null,
    /** [Directive.rawAction], carried through so the report echoes the action the backend named. */
    val rawAction: String? = null,
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
