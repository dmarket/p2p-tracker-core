package com.dmarket.p2p.tracker.engine

import com.dmarket.p2p.tracker.model.marketplace.DirectiveAck
import com.dmarket.p2p.tracker.model.marketplace.DirectiveOutcome

/**
 * One reported outcome paired with what the backend said about it. [reason] carries the backend's rejection
 * reason, or why no result could be matched.
 */
data class DirectiveOutcomeAck(val outcome: DirectiveOutcome, val accepted: Boolean, val reason: String? = null)

/**
 * Pairs a `POST /trade-actions` batch with its `results[]` — the directive-side counterpart of
 * [ReportAcknowledgement].
 *
 * Much simpler than that one, and deliberately so: `directive_id` is unique within a request, so a result
 * naming one is unambiguously that action's answer. There is no positional fallback and no elimination pass
 * because none is needed — which is exactly why this is a separate object rather than a reuse of
 * [ReportAcknowledgement]'s deal+axis machinery.
 *
 * The rule it shares with its sibling is the one that matters: **matched-or-not-accepted**. An action the
 * response says nothing about is reported as unaccepted, so its stored outcome survives to be re-sent when
 * the backend re-leases the directive. Reading silence as success would prune the outcome and leave the deal
 * parked with the backend never learning the write happened — the unrecoverable direction. A redundant
 * re-send is absorbed by the backend (it restates the same `steam_offer_id`); a dropped one is not.
 */
object DirectiveAcknowledgement {
    /**
     * [outcomes] paired with [acks], in outcome order.
     *
     * Each ack is claimed at most once, so a response that repeats a `directive_id` cannot acknowledge two
     * actions; acks naming an id that was never sent are ignored. Anything left unmatched is
     * `accepted = false` with [NO_RESULT].
     */
    fun match(outcomes: List<DirectiveOutcome>, acks: List<DirectiveAck>): List<DirectiveOutcomeAck> {
        if (outcomes.isEmpty()) return emptyList()
        val unclaimed = acks.groupBy { it.directiveId }.mapValues { (_, group) -> group.toMutableList() }
        return outcomes.map { outcome ->
            val ack = unclaimed[outcome.directiveId]?.removeFirstOrNull()
            when (ack) {
                null -> DirectiveOutcomeAck(outcome, accepted = false, reason = NO_RESULT)
                else -> DirectiveOutcomeAck(outcome, accepted = ack.accepted, reason = ack.reason)
            }
        }
    }

    /** Reason recorded for an action the response carried no result for. */
    const val NO_RESULT: String = "no result matched this action"
}
