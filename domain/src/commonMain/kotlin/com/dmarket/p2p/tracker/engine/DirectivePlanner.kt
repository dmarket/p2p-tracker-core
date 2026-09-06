package com.dmarket.p2p.tracker.engine

import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.DirectiveId
import com.dmarket.p2p.tracker.model.marketplace.Directive
import com.dmarket.p2p.tracker.model.marketplace.DirectiveAction
import com.dmarket.p2p.tracker.model.marketplace.DirectiveStatus
import com.dmarket.p2p.tracker.model.marketplace.HeartbeatResponse

/**
 * The directives this tick should execute, partitioned by action. The loop is a dumb executor of this
 * plan — all filtering decisions were made purely here.
 *
 * [alreadyHandled] are re-served directives this device has already executed: the backend re-leasing
 * one means our earlier `/trade-actions` outcome report never landed, so the loop re-*sends* the
 * stored outcome (never re-executes the Steam write).
 *
 * [dropped] are directives of a **known** action the planner refused to execute, each paired with the
 * reason and with the [DropKind] that decides whether the refusal is answerable — see there. [unsupported]
 * are directives whose action this build does not know at all.
 *
 * **Both are refusals the backend has to hear about.** A directive nobody answers keeps its Redis lease
 * until the TTL, is re-served on the next heartbeat, and the deal stands still — indefinitely, and
 * indistinguishably from a healthy idle client. The loop therefore reports them on `/trade-actions`
 * ([com.dmarket.p2p.tracker.model.marketplace.DirectiveStatus.MALFORMED] /
 * [com.dmarket.p2p.tracker.model.marketplace.DirectiveStatus.UNSUPPORTED]) as well as surfacing them as a
 * lifecycle event. An unknown action used to be dropped here in silence on forward-compatibility grounds,
 * which is right about not *executing* it and wrong about not *answering* it.
 */
data class DirectivePlan(
    val creates: List<Directive> = emptyList(),
    val cancels: List<Directive> = emptyList(),
    val inventoryScans: List<Directive> = emptyList(),
    val alreadyHandled: List<Directive> = emptyList(),
    val dropped: List<DroppedDirective> = emptyList(),
    val unsupported: List<Directive> = emptyList(),
) {
    /**
     * Nothing to *execute* — [alreadyHandled] may still carry outcomes for the loop to re-report, and
     * [dropped]/[unsupported] may still carry refusals for the loop to report and surface as events.
     * Those two are answered **before** the loop consults this, so widening it would only hide them.
     */
    val isEmpty: Boolean get() = creates.isEmpty() && cancels.isEmpty() && inventoryScans.isEmpty()

    /**
     * The refusals to answer on `/trade-actions`, already carrying the status each is reported under —
     * so the loop reports what the planner decided instead of re-deriving it from a reason string.
     */
    val refusals: List<DirectiveRefusal>
        get() = unsupported.map { DirectiveRefusal(it, DirectiveStatus.UNSUPPORTED, reason = null) } +
            dropped.filter { it.kind == DropKind.PAYLOAD_INVALID }
                .map { DirectiveRefusal(it.directive, DirectiveStatus.MALFORMED, it.reason) }

    companion object {
        val EMPTY: DirectivePlan = DirectivePlan()
    }
}

/** Why a directive of a known action was not executed — and whether the client may answer for it. */
enum class DropKind {
    /**
     * The payload cannot satisfy its own action, so nothing valid could be attempted. Answerable, and
     * worth answering: a systematically malformed directive is re-leased forever.
     */
    PAYLOAD_INVALID,

    /**
     * A second non-idempotent write for a deal already claimed in this same batch (two `create_offer`s
     * for one `deal_id` would mean two live Steam offers, whatever their `directive_id`s).
     *
     * **Never reported.** This directive is well-formed and the backend may well still want it done —
     * dropping it is safe precisely *because* it gets re-leased, and answering it would end that lease
     * while telling the backend its payload was broken when it was not. It also has to stay re-servable
     * for [DealWriteGuard]'s stored-outcome replay, which is what restates the real `steam_offer_id`
     * for a deal this device has already written.
     */
    DUPLICATE_WRITE,
}

/** A directive the planner refused, with the reason it failed and the [DropKind] that classifies it. */
data class DroppedDirective(val directive: Directive, val reason: String, val kind: DropKind = DropKind.PAYLOAD_INVALID)

/**
 * A refusal the client answers for: the directive, the wire status it is answered under, and the
 * [reason] to put on the report's `error` field.
 *
 * [reason] is `null` for an unrecognised action, deliberately: the status and the echoed action already
 * say the whole of it, and any sentence this build could add would be about a command it cannot name.
 */
data class DirectiveRefusal(val directive: Directive, val status: DirectiveStatus, val reason: String?)

/**
 * Pure planning over a `HeartbeatResponse`'s `directives[]`. The backend already leases each directive
 * to this device (the Redis lease); the client's remaining job is **single-flight** — never execute a
 * `directive_id` it has already handled this session, and never execute two non-idempotent writes for
 * one deal out of a single batch — and to classify the directives it will not execute so the loop can
 * answer for them. No IO, no clock; the loop gathers the heartbeat + the handled set and executes the
 * returned plan.
 *
 * Cross-batch / cross-caller duplicate protection is *not* here: it needs stored state, and lives in
 * [DealWriteGuard] plus the loop's claim store.
 *
 * A directive must be well-formed for its action (e.g. `create_offer` needs a deal + partner + at least
 * one asset; `cancel_offer` needs a steam offer id; `report_inventory` needs assets to verify);
 * malformed directives are dropped rather than executed.
 */
object DirectivePlanner {
    fun plan(heartbeat: HeartbeatResponse, handled: Set<DirectiveId>): DirectivePlan {
        if (heartbeat.directives.isEmpty()) return DirectivePlan.EMPTY

        val creates = mutableListOf<Directive>()
        val cancels = mutableListOf<Directive>()
        val inventoryScans = mutableListOf<Directive>()
        val alreadyHandled = mutableListOf<Directive>()
        val dropped = mutableListOf<DroppedDirective>()
        val unsupported = mutableListOf<Directive>()
        // One non-idempotent Steam write per (deal, action) per batch — see the same-deal drop below.
        val claimedWrites = mutableSetOf<Pair<DealId, DirectiveAction>>()

        for (directive in heartbeat.directives) {
            if (directive.directiveId in handled) {
                alreadyHandled += directive
                continue
            }
            // A second create/cancel for the SAME deal in ONE heartbeat is a duplicate whatever its
            // directive_id, and executing both means two live Steam offers (which the backend cannot
            // dedupe). Keep the first executable one, drop the rest visibly — dropping is safe because
            // the backend re-leases anything it still wants done on the next heartbeat.
            val writeKey = directive.writeKey()
            if (writeKey != null && writeKey in claimedWrites) {
                dropped += DroppedDirective(
                    directive,
                    "duplicate ${directive.action.wireName} for deal ${writeKey.first.value} in one heartbeat",
                    DropKind.DUPLICATE_WRITE,
                )
                continue
            }
            if (directive.action == DirectiveAction.UNKNOWN) {
                // Still never executed — that part of forward-compatibility was always right. It is now
                // ANSWERED, though: an unrecognised action left unreported holds its lease until the TTL
                // and comes back on every heartbeat, so the deal parks for as long as the version skew
                // lasts. Its own bucket rather than a `dropped` entry, because the wire status must be a
                // function of which bucket a directive landed in and not of a parsed reason string.
                unsupported += directive
                continue
            }
            // Each validity check returns the reason it failed (null = valid), so the drop decision and
            // its explanation stay in one place — the loop just forwards the reason to a lifecycle event.
            val (bucket, invalidReason) = when (directive.action) {
                DirectiveAction.CREATE_OFFER -> creates to directive.invalidCreateReason()
                DirectiveAction.CANCEL_OFFER -> cancels to directive.invalidCancelReason()
                DirectiveAction.REPORT_INVENTORY -> inventoryScans to directive.invalidInventoryReason()
                DirectiveAction.UNKNOWN -> null to null // unreachable: handled above
            }
            when {
                bucket == null -> Unit
                // Claim the (deal, action) only once the directive is actually executable, so a malformed
                // create can't drop the well-formed one that follows it for the same deal.
                invalidReason == null -> {
                    bucket += directive
                    writeKey?.let(claimedWrites::add)
                }
                else -> dropped += DroppedDirective(directive, invalidReason)
            }
        }

        val plan = DirectivePlan(creates, cancels, inventoryScans, alreadyHandled, dropped, unsupported)
        return if (plan == DirectivePlan.EMPTY) DirectivePlan.EMPTY else plan
    }

    /**
     * The `(deal, action)` identity of a **non-idempotent** Steam write, or `null` for anything that is
     * safe to repeat (`report_inventory`, an unknown action, a deal-less directive).
     */
    private fun Directive.writeKey(): Pair<DealId, DirectiveAction>? {
        val deal = dealId ?: return null
        return if (action == DirectiveAction.CREATE_OFFER || action == DirectiveAction.CANCEL_OFFER) deal to action else null
    }

    /** The reason a `create_offer` payload is malformed, or `null` when valid. */
    private fun Directive.invalidCreateReason(): String? = when {
        dealId == null -> "create_offer missing deal_id"
        partnerSteamId == null -> "create_offer missing partner_steam_id"
        assetIds.isEmpty() -> "create_offer has no asset_ids"
        tradeToken.isNullOrBlank() -> "create_offer missing trade_token"
        else -> null
    }

    /** The reason a `cancel_offer` payload is malformed, or `null` when valid. */
    private fun Directive.invalidCancelReason(): String? = if (steamOfferId == null) "cancel_offer missing steam_offer_id" else null

    /** The reason a `report_inventory` payload is malformed, or `null` when valid. */
    private fun Directive.invalidInventoryReason(): String? = if (assetIds.isEmpty()) "report_inventory has no asset_ids" else null
}
