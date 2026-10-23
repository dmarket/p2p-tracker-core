package com.dmarket.p2p.tracker.engine

import com.dmarket.p2p.tracker.model.marketplace.DirectiveStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FailedCancelCheckTest {
    private val failure = "HttpStatusException: HTTP 500"

    @Test
    fun every_offer_state_falls_in_one_group() {
        val groups = mapOf(
            FailedCancelKind.ALREADY_INACTIVE to listOf(4, 5, 6, 7, 8, 10),
            FailedCancelKind.ALREADY_TRADED to listOf(3, 11),
            FailedCancelKind.STILL_OPEN to listOf(2, 9, 1, 12, 0),
        )
        for ((kind, states) in groups) {
            for (state in states) assertEquals(kind, FailedCancelCheck.verdict(state).kind, "state $state")
        }
        assertEquals(FailedCancelKind.UNREAD, FailedCancelCheck.verdict(null).kind)
    }

    @Test
    fun an_offer_already_inactive_settles_the_cancel_and_reports_its_state() {
        val verdict = FailedCancelCheck.verdict(6)
        assertTrue(verdict.settled)
        assertEquals(FailedCancelCheck.ALREADY_INACTIVE_STATUS, verdict.status)
        assertEquals(DirectiveStatus.FAILED, verdict.status, "FAILED until DMarket says otherwise")
        assertEquals("offer already canceled (state 6)", verdict.error(failure))
        assertEquals("offer already expired (state 5)", FailedCancelCheck.verdict(5).error(failure))
        assertEquals("offer already canceled by second factor (state 10)", FailedCancelCheck.verdict(10).error(failure))
    }

    @Test
    fun a_traded_offer_is_a_real_failure_named_by_its_state() {
        val accepted = FailedCancelCheck.verdict(3)
        assertFalse(accepted.settled)
        assertEquals(DirectiveStatus.FAILED, accepted.status)
        assertEquals("offer already accepted (state 3)", accepted.error(failure))
        assertEquals("offer in escrow (state 11)", FailedCancelCheck.verdict(11).error(failure))
    }

    @Test
    fun an_open_or_unread_offer_keeps_steams_own_error() {
        for (verdict in listOf(FailedCancelCheck.verdict(2), FailedCancelCheck.verdict(9), FailedCancelCheck.verdict(null))) {
            assertFalse(verdict.settled, "$verdict")
            assertEquals(DirectiveStatus.FAILED, verdict.status)
            assertEquals(failure, verdict.error(failure))
        }
        assertNull(FailedCancelCheck.verdict(null).error(null))
    }
    // ---- decide ---------------------------------------------------------------------------------

    private fun read(state: Int?) = FailedCancelCheck.verdict(state)

    private fun on(write: CancelWrite, verdict: FailedCancelVerdict?) = FailedCancelCheck.decide(write, failure, verdict, true)

    private fun off(write: CancelWrite, verdict: FailedCancelVerdict?) = FailedCancelCheck.decide(write, failure, verdict, false)

    @Test
    fun with_not_actionable_on_every_terminal_state_spends_the_cancel() {
        for (write in CancelWrite.entries) {
            for (state in listOf(3, 4, 5, 7, 8, 10, 11)) {
                assertEquals(
                    CancelDecision.Report(DirectiveStatus.NOT_ACTIONABLE, FailedCancelCheck.describe(state), settled = true),
                    on(write, read(state)),
                    "$write, state $state",
                )
            }
        }
        for (write in listOf(CancelWrite.NOT_LIVE, CancelWrite.REFUSED)) {
            assertEquals(
                CancelDecision.Report(DirectiveStatus.NOT_ACTIONABLE, "offer already canceled (state 6)", settled = true),
                on(write, read(6)),
            )
        }
    }

    @Test
    fun an_unanswered_cancel_whose_offer_reads_cancelled_is_a_success() {
        assertEquals(CancelDecision.Report(DirectiveStatus.SUCCESS, null, settled = true), on(CancelWrite.UNANSWERED, read(6)))
    }

    @Test
    fun with_not_actionable_on_a_live_offer_is_a_failure_that_runs_again() {
        for (write in CancelWrite.entries) {
            for (state in listOf(2, 9)) {
                assertEquals(
                    CancelDecision.Report(DirectiveStatus.FAILED, failure, settled = false),
                    on(write, read(state)),
                    "$write $state",
                )
            }
        }
    }

    @Test
    fun with_not_actionable_on_an_unread_offer_leaves_the_write_to_speak() {
        for (verdict in listOf(read(null), read(1), read(12), null)) {
            assertEquals(
                CancelDecision.Report(DirectiveStatus.NOT_ACTIONABLE, failure, settled = true),
                on(CancelWrite.NOT_LIVE, verdict),
                "$verdict",
            )
            assertEquals(CancelDecision.Report(DirectiveStatus.FAILED, failure, settled = false), on(CancelWrite.REFUSED, verdict))
        }
        for (verdict in listOf(read(null), read(1), read(12))) assertEquals(CancelDecision.Hold, on(CancelWrite.UNANSWERED, verdict))
        assertEquals(
            CancelDecision.Report(DirectiveStatus.FAILED, failure, settled = false),
            on(CancelWrite.UNANSWERED, null),
            "no read was made: as before",
        )
    }

    @Test
    fun with_not_actionable_off_the_report_is_what_it_was_but_an_unanswered_unread_cancel_holds() {
        for (write in CancelWrite.entries) {
            for (state in listOf(2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 1)) {
                val verdict = read(state)
                assertEquals(
                    CancelDecision.Report(verdict.status, verdict.error(failure), verdict.settled),
                    off(write, verdict),
                    "$write $state",
                )
            }
            assertEquals(CancelDecision.Report(DirectiveStatus.FAILED, failure, settled = false), off(write, null), "$write, no read")
        }
        assertEquals(CancelDecision.Hold, off(CancelWrite.UNANSWERED, read(null)))
        for (write in listOf(CancelWrite.NOT_LIVE, CancelWrite.REFUSED)) {
            assertEquals(CancelDecision.Report(DirectiveStatus.FAILED, failure, settled = false), off(write, read(null)))
        }
    }
}
