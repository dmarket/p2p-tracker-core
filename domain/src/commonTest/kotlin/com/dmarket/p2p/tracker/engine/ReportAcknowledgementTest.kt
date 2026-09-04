package com.dmarket.p2p.tracker.engine

import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusReport
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusResult
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusSource
import com.dmarket.p2p.tracker.support.T0
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The batch this exercises is the one the live client actually sends: several watched deals, each with an
 * offer axis and a history axis, reported in one `POST /trade-events`. An accepted code enters the dedup
 * baseline and is never sent again, so mis-pairing a result is not a cosmetic bug — it is how a
 * trade-protection rollback stops being reported at all.
 */
class ReportAcknowledgementTest {

    private fun report(deal: String, source: TradeStatusSource, code: Int) = TradeStatusReport(DealId(deal), source, code, T0)

    private fun result(deal: String, accepted: Boolean, source: TradeStatusSource? = null, reason: String? = null) =
        TradeStatusResult(DealId(deal), accepted = accepted, reason = reason, source = source)

    @Test
    fun a_short_response_never_marks_the_dropped_reports_accepted() {
        // THE regression: `reports.zip(results)` truncated to the shorter list, so with 8 reports and 4
        // results four reports silently took another report's acknowledgement and four vanished.
        val reports = listOf(
            report("d1", TradeStatusSource.OFFER, 3),
            report("d1", TradeStatusSource.HISTORY, 12),
            report("d2", TradeStatusSource.OFFER, 3),
            report("d2", TradeStatusSource.HISTORY, 12),
        )
        val acks = ReportAcknowledgement.match(reports, listOf(result("d1", accepted = true)))
        assertEquals(4, acks.size)
        assertTrue(acks.none { it.accepted }, "one deal-level result cannot answer either of that deal's two axes")
        assertEquals("ambiguous deal-level result, axis unknown", acks[0].reason, "d1 had a result, just not a usable one")
        assertEquals("no result matched this report", acks[3].reason, "d2 had none at all")
    }

    @Test
    fun a_contract_shaped_response_is_read_positionally() {
        // The contract IS one result per report, and for that shape position is the only correspondence the
        // response carries — so it is honoured. Refusing it would re-report every accepted code forever.
        val reports = listOf(report("d1", TradeStatusSource.OFFER, 3), report("d1", TradeStatusSource.HISTORY, 12))
        val acks = ReportAcknowledgement.match(
            reports,
            listOf(result("d1", accepted = true), result("d1", accepted = false, reason = "already terminal")),
        )
        assertTrue(acks[0].accepted)
        assertFalse(acks[1].accepted)
        assertEquals("already terminal", acks[1].reason)
    }

    @Test
    fun a_deal_level_result_is_claimed_when_elimination_makes_it_unambiguous() {
        // Two reports for d1, two results: one explicitly HISTORY, one source-less. The source-less one can
        // only be the offer axis, so refusing it would be needlessly lossy.
        val reports = listOf(report("d1", TradeStatusSource.OFFER, 3), report("d1", TradeStatusSource.HISTORY, 12))
        val acks = ReportAcknowledgement.match(
            reports,
            listOf(result("d1", accepted = true, source = TradeStatusSource.HISTORY), result("d1", accepted = true)),
        )
        assertTrue(acks.all { it.accepted })
    }

    @Test
    fun a_reordered_response_is_matched_by_deal_not_by_position() {
        val reports = listOf(report("d1", TradeStatusSource.HISTORY, 12), report("d2", TradeStatusSource.HISTORY, 12))
        val acks = ReportAcknowledgement.match(
            reports,
            listOf(result("d2", accepted = true), result("d1", accepted = false, reason = "unknown deal")),
        )
        assertFalse(acks[0].accepted, "d1 was rejected, however the response was ordered")
        assertEquals("unknown deal", acks[0].reason)
        assertTrue(acks[1].accepted)
    }

    @Test
    fun a_source_bearing_result_answers_its_own_axis() {
        val reports = listOf(report("d1", TradeStatusSource.OFFER, 3), report("d1", TradeStatusSource.HISTORY, 12))
        val acks = ReportAcknowledgement.match(
            reports,
            listOf(
                result("d1", accepted = false, source = TradeStatusSource.OFFER, reason = "stale"),
                result("d1", accepted = true, source = TradeStatusSource.HISTORY),
            ),
        )
        assertFalse(acks[0].accepted)
        assertEquals("stale", acks[0].reason)
        assertTrue(acks[1].accepted, "the history axis must not inherit the offer axis's rejection")
    }

    @Test
    fun an_exact_source_match_is_not_consumed_as_an_earlier_reports_fallback() {
        // Why matching runs as two passes: with three reports for one deal and a HISTORY-tagged result, that
        // result must go to the history report — an interleaved pass would let the first report claim it.
        val reports = listOf(
            report("d1", TradeStatusSource.OFFER, 3),
            report("d1", TradeStatusSource.HISTORY, 12),
            report("d2", TradeStatusSource.OFFER, 3),
        )
        val acks = ReportAcknowledgement.match(
            reports,
            listOf(result("d1", accepted = true, source = TradeStatusSource.HISTORY), result("d2", accepted = true)),
        )
        assertFalse(acks[0].accepted, "the offer axis got no answer of its own")
        assertTrue(acks[1].accepted, "the HISTORY-tagged result answered the history axis")
        assertTrue(acks[2].accepted)
    }

    @Test
    fun one_result_is_never_shared_by_two_reports() {
        val reports = listOf(report("d1", TradeStatusSource.OFFER, 3), report("d1", TradeStatusSource.HISTORY, 12))
        val acks = ReportAcknowledgement.match(reports, listOf(result("d1", accepted = true)))
        assertEquals(0, acks.count { it.accepted }, "an ambiguous deal-level result answers neither axis")
    }

    @Test
    fun a_result_for_another_axis_only_does_not_match_at_all() {
        // Fails safe: better re-detected next tick than baselined on an acknowledgement of something else.
        val acks = ReportAcknowledgement.match(
            listOf(report("d1", TradeStatusSource.HISTORY, 12)),
            listOf(result("d1", accepted = true, source = TradeStatusSource.OFFER)),
        )
        assertFalse(acks.single().accepted)
    }

    @Test
    fun surplus_results_are_ignored_and_an_empty_batch_needs_no_pairing() {
        val acks = ReportAcknowledgement.match(
            listOf(report("d1", TradeStatusSource.HISTORY, 12)),
            listOf(result("d1", accepted = true), result("d9", accepted = true)),
        )
        assertEquals(1, acks.size)
        assertTrue(acks.single().accepted)
        assertTrue(ReportAcknowledgement.match(emptyList(), listOf(result("d1", accepted = true))).isEmpty())
    }

    @Test
    fun an_empty_result_set_accepts_nothing() {
        val acks = ReportAcknowledgement.match(listOf(report("d1", TradeStatusSource.HISTORY, 12)), emptyList())
        assertFalse(acks.single().accepted)
    }
}
