package com.dmarket.p2p.tracker.wire

import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.DirectiveId
import com.dmarket.p2p.tracker.model.marketplace.DirectiveAction
import com.dmarket.p2p.tracker.model.marketplace.DirectiveOutcome
import com.dmarket.p2p.tracker.model.marketplace.DirectiveStatus
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusReport
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusSource
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class BodySizeChunkingTest {

    private val encodeReports: (List<TradeStatusReport>) -> String = { TrackerJson.encodeToString(it.toRequestDto()) }
    private val encodeOutcomes: (List<DirectiveOutcome>) -> String = { TrackerJson.encodeToString(it.toRequestDto()) }

    private fun reports(n: Int) = List(n) { i ->
        TradeStatusReport(
            dealId = DealId("deal-$i"),
            source = if (i % 2 == 0) TradeStatusSource.OFFER else TradeStatusSource.HISTORY,
            steamStatusCode = i % 13,
            clientTime = Instant.fromEpochMilliseconds(1_700_000_000_000L + i),
        )
    }

    private fun outcome(i: Int, error: String? = null) = DirectiveOutcome(
        directiveId = DirectiveId("dir-$i"),
        action = DirectiveAction.CANCEL_OFFER,
        status = if (error == null) DirectiveStatus.SUCCESS else DirectiveStatus.FAILED,
        dealId = DealId("deal-$i"),
        error = error,
    )

    private fun String.bytes() = encodeToByteArray().size

    @Test
    fun every_run_fits_the_cap_and_the_runs_rejoin_into_the_input() {
        val items = reports(57)
        for (cap in listOf(200, 512, 1_000, 7 * 1024)) {
            val chunks = chunkByEncodedSize(items, cap, encodeReports)
            assertEquals(items, chunks.flatten(), "cap $cap")
            chunks.forEach { chunk ->
                assertTrue(chunk.isNotEmpty())
                assertTrue(encodeReports(chunk).bytes() <= cap, "cap $cap: ${encodeReports(chunk).bytes()} bytes")
            }
        }
    }

    @Test
    fun runs_are_filled_greedily_so_the_next_item_would_not_have_fitted() {
        val items = reports(57)
        val cap = 1_000
        val chunks = chunkByEncodedSize(items, cap, encodeReports)
        chunks.zipWithNext().forEach { (run, next) ->
            assertTrue(encodeReports(run + next.first()).bytes() > cap)
        }
    }

    @Test
    fun the_computed_size_is_the_encoded_size() {
        // Exact arithmetic is what lets a run sit right at the cap: size the whole batch as one run.
        val items = reports(20)
        val exact = encodeReports(items).bytes()
        assertEquals(1, chunkByEncodedSize(items, exact, encodeReports).size)
        assertEquals(2, chunkByEncodedSize(items, exact - 1, encodeReports).size)
    }

    @Test
    fun multibyte_text_is_measured_in_utf8_bytes() {
        val items = List(10) { outcome(it, error = "помилка ".repeat(10)) }
        val cap = 1_000
        chunkByEncodedSize(items, cap, encodeOutcomes).forEach { assertTrue(encodeOutcomes(it).bytes() <= cap) }
    }

    @Test
    fun an_item_larger_than_the_cap_goes_alone() {
        val items = listOf(outcome(0), outcome(1, error = "x".repeat(2_000)), outcome(2), outcome(3))
        val chunks = chunkByEncodedSize(items, 1_000, encodeOutcomes)
        assertEquals(listOf(listOf(items[0]), listOf(items[1]), listOf(items[2], items[3])), chunks)
    }

    @Test
    fun no_cap_is_one_run_and_no_items_is_no_run() {
        val items = reports(57)
        assertEquals(listOf(items), chunkByEncodedSize(items, 0, encodeReports))
        assertEquals(listOf(items), chunkByEncodedSize(items, -1, encodeReports))
        assertEquals(emptyList(), chunkByEncodedSize(emptyList(), 1_000, encodeReports))
    }
}
