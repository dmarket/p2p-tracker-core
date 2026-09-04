package com.dmarket.p2p.tracker.engine

import com.dmarket.p2p.tracker.model.marketplace.TradeStatusReport
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusResult

/**
 * One report paired with what the backend said about it. [reason] carries the backend's rejection reason,
 * or why no result could be matched.
 */
data class ReportAck(val report: TradeStatusReport, val accepted: Boolean, val reason: String? = null)

/**
 * Pairs a `POST /trade-events` batch with its `results[]`.
 *
 * The contract is **one result per report**, and for a response of that shape correspondence is positional —
 * there is no other information in it, so that is what is trusted (see [isOneToOne]). The danger was
 * trusting position when the response is *not* that shape: the old `reports.zip(results)` silently truncated
 * to the shorter list and paired by index regardless, so a backend that answered short, or collapsed to one
 * result per **deal** for a batch carrying both axes of that deal, marked reports accepted off a *different*
 * report's acknowledgement. An accepted code enters the dedup baseline and is never sent again, so a history
 * `12` accepted that way silently stops existing.
 *
 * Off that path the rule is **matched-or-not-accepted**: a result is claimed only when it is unambiguously
 * this report's, and anything else is a failure whose code stays out of the baseline and is re-detected next
 * tick. That is the recoverable direction — a duplicate re-report is absorbed by the backend's LWW, a lost
 * one is not.
 *
 * `TradeStatusResult.source` is what removes the positional assumption entirely; it is not in the frozen
 * contract yet, so until the backend sends it a deal-level result can only be matched when that deal has a
 * single report in the batch.
 */
object ReportAcknowledgement {

    /**
     * [reports] paired with [results], in report order.
     *
     * 1. **Contract-shaped response** ([isOneToOne]) — paired by index.
     * 2. Otherwise, per report: a result with the same `dealId` **and** the same `source`; failing that, a
     *    `source`-less result for that deal, but **only when that deal has a single report still unmatched**
     *    — i.e. when elimination makes the answer unambiguous. Each result is claimed at most once (two axes
     *    of one deal cannot share one acknowledgement), and everything unclaimed is `accepted = false`.
     */
    fun match(reports: List<TradeStatusReport>, results: List<TradeStatusResult>): List<ReportAck> {
        if (reports.isEmpty()) return emptyList()
        if (isOneToOne(reports, results)) {
            return reports.mapIndexed { index, report -> results[index].let { ReportAck(report, it.accepted, it.reason) } }
        }

        val consumed = BooleanArray(results.size)

        fun claim(report: TradeStatusReport, requireSource: Boolean): TradeStatusResult? {
            for (i in results.indices) {
                if (consumed[i]) continue
                val result = results[i]
                if (result.dealId != report.dealId) continue
                val sourceMatches = if (requireSource) result.source == report.source else result.source == null
                if (!sourceMatches) continue
                consumed[i] = true
                return result
            }
            return null
        }

        // Two full passes, not one interleaved pass: an exact match for a LATER report must not be consumed
        // as an earlier report's fallback.
        val exact = reports.map { claim(it, requireSource = true) }
        // How many of a deal's reports the exact pass left open. A deal-level result cannot say WHICH axis it
        // answers, so it is usable only when exactly one report of that deal is still open — then elimination
        // makes it unambiguous. With two open, handing it to the first-listed report is precisely the
        // mis-pairing this object exists to prevent, so both are reported as unanswered instead.
        val stillOpen = reports.filterIndexed { index, _ -> exact[index] == null }
            .groupingBy { it.dealId }
            .eachCount()
        return reports.mapIndexed { index, report ->
            val open = stillOpen[report.dealId] ?: 0
            // Do not even *claim* when the deal is ambiguous: consuming a result we then discard would starve
            // another report of that deal of a result it might have been able to use.
            val result = exact[index] ?: if (open == 1) claim(report, requireSource = false) else null
            val ambiguous = open > 1 && results.any { it.dealId == report.dealId && it.source == null }
            when {
                result != null -> ReportAck(report, accepted = result.accepted, reason = result.reason)
                ambiguous -> ReportAck(report, accepted = false, reason = "ambiguous deal-level result, axis unknown")
                else -> ReportAck(report, accepted = false, reason = "no result matched this report")
            }
        }
    }

    /**
     * Whether [results] is the shape the contract promises — one result per report, each naming the same deal
     * as the report in its position (and, where the backend sends `source`, the same axis).
     *
     * The `dealId` check is what makes this safe to read positionally: a reordered or collapsed response fails
     * it and falls through to unique matching. It cannot detect the two axes of ONE deal being swapped
     * against each other, because with no `source` the two results are indistinguishable — position is then
     * the only information the response carries, and the contract says position is the correspondence.
     */
    private fun isOneToOne(reports: List<TradeStatusReport>, results: List<TradeStatusResult>): Boolean = results.size == reports.size &&
        reports.indices.all { i ->
            results[i].dealId == reports[i].dealId && (results[i].source == null || results[i].source == reports[i].source)
        }
}
