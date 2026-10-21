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
}
