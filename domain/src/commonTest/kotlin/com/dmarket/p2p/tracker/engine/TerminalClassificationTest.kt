package com.dmarket.p2p.tracker.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalClassificationTest {
    private fun classify(offer: Int, status: Int = 0) = TerminalClassification.classify(offer, status)

    @Test
    fun accepted_and_complete_is_the_happy_terminal() {
        assertEquals(TerminalOutcome.COMPLETE, classify(offer = 3, status = 3))
        assertTrue(classify(3, 3).isTerminal)
    }

    @Test
    fun accepted_but_transfer_not_yet_final_keeps_watching() {
        // Accepted on the offer axis, transfer neither Complete(3) nor Failed(4) yet → still settling.
        assertEquals(TerminalOutcome.PENDING, classify(offer = 3, status = 0))
        assertEquals(TerminalOutcome.PENDING, classify(offer = 3, status = 13)) // 13 = unconfirmed → non-decisive
        assertFalse(classify(3, 0).isTerminal)
    }

    @Test
    fun accepted_but_transfer_failed_is_terminal_failure() {
        assertEquals(TerminalOutcome.FAILED, classify(offer = 3, status = 4))
        assertTrue(classify(3, 4).isTerminal)
    }

    @Test
    fun active_and_needs_confirmation_are_non_terminal() {
        assertEquals(TerminalOutcome.PENDING, classify(offer = 2))
        assertEquals(TerminalOutcome.PENDING, classify(offer = 9))
        assertFalse(classify(2).isTerminal)
        assertFalse(classify(9).isTerminal)
    }

    @Test
    fun in_escrow_hold_is_non_terminal() {
        assertEquals(TerminalOutcome.IN_ESCROW_HOLD, classify(offer = 11))
        assertFalse(classify(11).isTerminal)
    }

    @Test
    fun invalid_items_is_terminal_seller_fault() {
        assertEquals(TerminalOutcome.INVALID_ITEMS, classify(offer = 8))
        assertTrue(classify(8).isTerminal)
    }

    @Test
    fun buyer_no_accept_states_are_expired_or_declined() {
        for (offer in listOf(4, 5, 6, 7, 10)) {
            assertEquals(TerminalOutcome.EXPIRED_OR_DECLINED, classify(offer), "offer state $offer")
            assertTrue(classify(offer).isTerminal, "offer state $offer terminal")
        }
    }

    @Test
    fun offer_invalid_is_a_hard_failure() {
        assertEquals(TerminalOutcome.FAILED, classify(offer = 1))
        assertTrue(classify(1).isTerminal)
    }

    @Test
    fun unrecognised_offer_state_is_non_terminal_so_the_client_keeps_watching() {
        assertEquals(TerminalOutcome.UNKNOWN, classify(offer = 99))
        assertEquals(TerminalOutcome.UNKNOWN, classify(offer = 0))
        assertFalse(classify(99).isTerminal)
    }
}
