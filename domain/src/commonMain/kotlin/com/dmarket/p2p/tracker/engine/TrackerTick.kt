package com.dmarket.p2p.tracker.engine

import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.TradeId
import com.dmarket.p2p.tracker.model.marketplace.TrackedDeal
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusReport
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusSource
import com.dmarket.p2p.tracker.model.marketplace.mayProveOn
import kotlin.time.Instant

/**
 * Both Steam axes polled this tick for one watched deal; either may be absent if not polled.
 *
 * [reversalInitiator] is attribution for a history rollback, resolved by the loop at observation time (it
 * needs an extra Steam read, so it cannot be derived here). It never creates an observation on its own, but
 * it is **not** inert against dedup: a rollback the backend holds as actor-undecided is unfinished, so a
 * freshly resolved actor re-asserts an already-reported code exactly once. The loop only populates it while
 * no actor is on record ([ReportedStatus.historyInitiatorReported]), which is what makes that terminate.
 *
 * [settlementAt] is the correlated transfer's Trade-Protection window, and behaves the same way for the same
 * reason: Steam clears `time_settlement` on the row it flips to `12`, so a window first seen on a code that
 * is already in the baseline has no later chance to be sent. It too re-asserts an unchanged code exactly
 * once, bounded by [ReportedStatus.historySettlementReported] on the loop side.
 */
data class ObservedTrade(
    val offerState: Int? = null,
    val historyStatus: Int? = null,
    val reversalInitiator: SteamId? = null,
    val settlementAt: Instant? = null,
    /**
     * Steam's own `tradeid`, which it sets on the offer once the offer is accepted. Inert for the report —
     * the backend never sees it — and carried purely so the loop can bind a **proof** to a single trade:
     * the history axis's proven read is `GetTradeStatus?tradeid=…`, and this is the only place that id is
     * known (see `ProvenReadBinding.tradeId`).
     */
    val tradeId: TradeId? = null,
)

/**
 * The last raw code reported per axis for a deal, persisted across ticks so the tracker only reports a
 * code when it **changes** (dedup; the backend applies LWW within valid forward transitions). Set by
 * the executor after a successful `POST /trade-events`.
 *
 * [historyInitiatorReported] records that an accepted history report actually carried a reversal actor. It
 * exists because the actor rides **only** a report: without it, a rollback reported while attribution was
 * unresolved is deduped away by [lastHistoryCode] and the actor can never be back-filled — the deal parks
 * with escrow untouched and the client is permanently silent about it.
 *
 * [historySettlementReported] is the same idea for the Trade-Protection window: it records that an accepted
 * history report actually carried a `settlementTime`. Without it, a window that only becomes readable after
 * its code was reported is deduped away for good — and since Steam clears `time_settlement` on the rollback
 * flip, there is no later read that could recover it.
 */
data class ReportedStatus(
    val lastOfferCode: Int? = null,
    val lastHistoryCode: Int? = null,
    val historyInitiatorReported: Boolean = false,
    val historySettlementReported: Boolean = false,
)

/**
 * A decisive transition that needs a TLSN proof on `POST /notary` (only emitted for `proof_required`
 * deals). The loop calls [com.dmarket.p2p.tracker.port.notary.NotaryProver] for the [source] axis and submits
 * the resulting proof.
 */
data class ProofIntent(val dealId: DealId, val source: TradeStatusSource, val steamStatusCode: Int)

/**
 * The output of one tracker tick: raw status reports to batch, plus any decisive proofs to generate.
 *
 * [suppressed] counts the observed axes whose raw code **equalled** the stored baseline — i.e. ordinary
 * dedup. It is bookkeeping for the loop's watch verdict and deliberately does NOT affect [isEmpty]: a plan
 * that only suppressed is still an empty plan to send, but a cycle must be able to say "nothing changed"
 * rather than being indistinguishable from "we never saw the axis".
 */
data class ReportPlan(
    val reports: List<TradeStatusReport> = emptyList(),
    val proofIntents: List<ProofIntent> = emptyList(),
    val suppressed: Int = 0,
) {
    val isEmpty: Boolean get() = reports.isEmpty() && proofIntents.isEmpty()

    companion object {
        val EMPTY: ReportPlan = ReportPlan()
    }
}

/**
 * The pure change-detector for the watch loop (the v2 `pingTradeStatus`/`pingUpdates` reduced to one
 * function). For each watched deal it compares the freshly-[observed] raw Steam codes against the
 * [reported] baseline and emits a [TradeStatusReport] per **changed** axis (raw code, no verdict — the
 * backend maps it). When the deal is `proof_required` and the changed code is decisive
 * ([DecisiveTransitions]), it also emits a [ProofIntent]. No IO, no clock — [now] is supplied.
 *
 * One report is emitted for an **unchanged** history code: when the observation carries a reversal actor
 * the baseline does not yet have (see [ObservedTrade.reversalInitiator]). Everything else that matched the
 * baseline is counted in [ReportPlan.suppressed] instead.
 */
object TrackerTick {
    fun reduce(
        now: Instant,
        activeTracking: List<TrackedDeal>,
        observed: Map<DealId, ObservedTrade>,
        reported: Map<DealId, ReportedStatus>,
    ): ReportPlan {
        if (activeTracking.isEmpty()) return ReportPlan.EMPTY

        val reports = mutableListOf<TradeStatusReport>()
        val proofIntents = mutableListOf<ProofIntent>()
        var suppressed = 0

        for (deal in activeTracking) {
            val ob = observed[deal.dealId] ?: continue
            val rep = reported[deal.dealId] ?: ReportedStatus()

            ob.offerState?.let { code ->
                if (code != rep.lastOfferCode) {
                    reports += TradeStatusReport(deal.dealId, TradeStatusSource.OFFER, code, now)
                    // `mayProveOn` is the axis gate, and the offer axis is the one that needs it: the
                    // account-wide list read observes an offer code for EVERY tracked deal, so without it a
                    // deal watched on the transfer axis raised an offer proof the backend rejects as the
                    // wrong read — and proof-before-report then withheld this very report behind it. The
                    // report above is deliberately outside the gate; only the proof is axis-bound.
                    if (deal.proofRequired &&
                        deal.mayProveOn(TradeStatusSource.OFFER) &&
                        DecisiveTransitions.isDecisive(TradeStatusSource.OFFER, code)
                    ) {
                        proofIntents += ProofIntent(deal.dealId, TradeStatusSource.OFFER, code)
                    }
                } else {
                    suppressed++
                }
            }

            ob.historyStatus?.let { code ->
                if (code != rep.lastHistoryCode) {
                    // Attribution and the settlement window ride the report as inert data — a pure
                    // pass-through, no new branch. Attribution is only ever populated for a rollback and the
                    // window is only ever populated for a row that still had one, so no code check is needed.
                    reports += TradeStatusReport(deal.dealId, TradeStatusSource.HISTORY, code, now, ob.reversalInitiator, ob.settlementAt)
                    // Same gate for symmetry, though this axis cannot currently trip it: the history read is
                    // itself watch-gated on the polling path, so an unwatched deal has no `historyStatus` to
                    // observe. Stating it here means the two axes carry one rule rather than two, and a later
                    // change to how transfers are correlated cannot reintroduce the offer axis's bug here.
                    if (deal.proofRequired &&
                        deal.mayProveOn(TradeStatusSource.HISTORY) &&
                        DecisiveTransitions.isDecisive(TradeStatusSource.HISTORY, code)
                    ) {
                        proofIntents += ProofIntent(deal.dealId, TradeStatusSource.HISTORY, code)
                    }
                } else if (ob.reversalInitiator != null || ob.settlementAt != null) {
                    // An unchanged code that nevertheless carries something the baseline does not have is NOT
                    // dedup, for either payload — and both ride only a report:
                    //  · a reversal actor, because the backend parks a rollback whose actor is undecided;
                    //  · a settlement window, because Steam clears `time_settlement` on the rollback flip, so
                    //    a window not sent now can never be read again.
                    // Re-assert the same code with whatever is attached, once each — the loop stops populating
                    // either as soon as it is on record (see ObservedTrade).
                    reports += TradeStatusReport(deal.dealId, TradeStatusSource.HISTORY, code, now, ob.reversalInitiator, ob.settlementAt)
                } else {
                    suppressed++
                }
            }
        }

        return if (reports.isEmpty() && proofIntents.isEmpty() && suppressed == 0) {
            ReportPlan.EMPTY
        } else {
            ReportPlan(reports, proofIntents, suppressed)
        }
    }
}
