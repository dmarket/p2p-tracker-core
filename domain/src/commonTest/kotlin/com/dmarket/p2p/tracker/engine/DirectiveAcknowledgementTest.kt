package com.dmarket.p2p.tracker.engine

import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.DirectiveId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.marketplace.DirectiveAck
import com.dmarket.p2p.tracker.model.marketplace.DirectiveAction
import com.dmarket.p2p.tracker.model.marketplace.DirectiveOutcome
import com.dmarket.p2p.tracker.model.marketplace.DirectiveStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DirectiveAcknowledgementTest {

    private fun outcome(id: String, status: DirectiveStatus = DirectiveStatus.NEEDS_CONFIRMATION) = DirectiveOutcome(
        directiveId = DirectiveId(id),
        action = DirectiveAction.CREATE_OFFER,
        status = status,
        dealId = DealId("deal-$id"),
        steamOfferId = OfferId("offer-$id"),
    )

    private fun ack(id: String, accepted: Boolean = true, reason: String? = null) =
        DirectiveAck(directiveId = DirectiveId(id), accepted = accepted, reason = reason)

    private fun match(outcomes: List<DirectiveOutcome>, acks: List<DirectiveAck>) = DirectiveAcknowledgement.match(outcomes, acks)

    @Test
    fun an_empty_batch_pairs_to_nothing() {
        assertTrue(match(emptyList(), listOf(ack("dir-1"))).isEmpty())
    }

    @Test
    fun matches_by_directive_id_regardless_of_response_order() {
        val outcomes = listOf(outcome("dir-1"), outcome("dir-2"), outcome("dir-3"))
        val paired = match(outcomes, listOf(ack("dir-3"), ack("dir-1"), ack("dir-2", accepted = false, reason = "stale")))

        // Result order is the OUTCOME order, so a caller can zip it against what it sent.
        assertEquals(listOf("dir-1", "dir-2", "dir-3"), paired.map { it.outcome.directiveId.value })
        assertEquals(listOf(true, false, true), paired.map { it.accepted })
        assertEquals("stale", paired[1].reason)
    }

    @Test
    fun an_action_with_no_result_is_unaccepted_and_says_why() {
        // The rule that protects a real Steam write: silence must not prune the stored outcome.
        val paired = match(listOf(outcome("dir-1"), outcome("dir-2")), listOf(ack("dir-1")))

        assertEquals(listOf(true, false), paired.map { it.accepted })
        assertEquals(DirectiveAcknowledgement.NO_RESULT, paired[1].reason)
    }

    @Test
    fun an_empty_response_leaves_every_action_unaccepted() {
        val paired = match(listOf(outcome("dir-1"), outcome("dir-2")), emptyList())
        assertTrue(paired.none { it.accepted })
        assertTrue(paired.all { it.reason == DirectiveAcknowledgement.NO_RESULT })
    }

    @Test
    fun a_repeated_directive_id_acknowledges_only_one_action() {
        // Two acks for one id cannot answer two separate actions; the duplicate is consumed once and the
        // second same-id outcome falls through to unaccepted.
        val paired = match(listOf(outcome("dir-1"), outcome("dir-1")), listOf(ack("dir-1")))
        assertEquals(listOf(true, false), paired.map { it.accepted })
    }

    @Test
    fun results_for_ids_we_never_sent_are_ignored() {
        val paired = match(listOf(outcome("dir-1")), listOf(ack("dir-99"), ack("dir-1")))
        assertEquals(1, paired.size)
        assertTrue(paired.single().accepted)
    }

    @Test
    fun a_rejection_carries_the_backends_own_reason() {
        val paired = match(listOf(outcome("dir-1")), listOf(ack("dir-1", accepted = false, reason = "deal_id is required")))
        assertEquals("deal_id is required", paired.single().reason)
    }

    @Test
    fun a_failed_outcome_can_still_be_accepted() {
        // "Accepted" is about the report landing, not about the Steam write succeeding.
        val paired = match(listOf(outcome("dir-1", DirectiveStatus.FAILED)), listOf(ack("dir-1")))
        assertTrue(paired.single().accepted)
    }
}
