package com.dmarket.p2p.tracker.engine

import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.PushSignal
import com.dmarket.p2p.tracker.model.Wake
import com.dmarket.p2p.tracker.model.marketplace.DealRole
import com.dmarket.p2p.tracker.model.marketplace.TrackedDeal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class ProofWakeTest {
    private fun wake(reason: String?, transactionId: String? = null) =
        Wake(PushSignal.WakeAll, "wake_deal", reason = reason, wakeId = "w-1", transactionId = transactionId)

    private fun deal(
        id: String,
        proofRequired: Boolean = true,
        role: DealRole = DealRole.SELLER,
        lastOfferCode: Int? = null,
        proveAfter: Instant? = null,
        transactionId: String? = null,
    ) = TrackedDeal(
        DealId(id),
        proofRequired = proofRequired,
        role = role,
        lastOfferCode = lastOfferCode,
        proveAfter = proveAfter,
        transactionId = transactionId,
    )

    @Test
    fun only_the_two_proof_reasons_are_proof_wakes() {
        assertTrue(wake("prove_offer").isProof)
        assertTrue(wake("prove_release").isProof)
        for (reason in listOf("directive", "cancel_ask", "watch", "frozen", null)) assertFalse(wake(reason).isProof, "$reason")
    }

    @Test
    fun a_prove_offer_wake_waits_on_a_proof_enforced_offer_of_ours_not_settled_on_a_decisive_code() {
        val rows = listOf(
            deal("unconfirmed", lastOfferCode = 9) to true,
            deal("no code yet") to true,
            deal("unknown role", role = DealRole.UNKNOWN, lastOfferCode = 9) to true,
            deal("proven active", lastOfferCode = 2) to false,
            deal("proven accepted", lastOfferCode = 3) to false,
            deal("cancelled", lastOfferCode = 6) to false,
            deal("client-reported", proofRequired = false, lastOfferCode = 9) to false,
            deal("buyer", role = DealRole.BUYER, lastOfferCode = 9) to false,
        )
        for ((tracked, waits) in rows) {
            assertEquals(waits, tracked.dealId in wake("prove_offer").dealsAwaitingProof(listOf(tracked)), tracked.dealId.value)
        }
    }

    @Test
    fun a_prove_release_wake_waits_on_a_deal_with_a_freshness_mark() {
        val marked = deal("marked", proofRequired = false, lastOfferCode = 3, proveAfter = Instant.fromEpochSeconds(1))
        val unmarked = deal("unmarked", lastOfferCode = 9)

        assertEquals(setOf(marked.dealId), wake("prove_release").dealsAwaitingProof(listOf(marked, unmarked)))
    }

    @Test
    fun any_other_wake_waits_on_nothing() {
        val tracking = listOf(deal("d1", lastOfferCode = 9, proveAfter = Instant.fromEpochSeconds(1)))
        for (reason in listOf("directive", "cancel_ask", "watch", null)) {
            assertEquals(emptySet(), wake(reason).dealsAwaitingProof(tracking), "$reason")
        }
    }

    @Test
    fun the_wake_names_its_deal_by_transaction_id_when_the_list_carries_them() {
        val tracking = listOf(deal("d1", lastOfferCode = 9, transactionId = "tx-1"), deal("d2", lastOfferCode = 9, transactionId = "tx-2"))

        assertEquals(setOf(DealId("d2")), wake("prove_offer", "tx-2").dealsAwaitingProof(tracking))
        assertEquals(emptySet(), wake("prove_offer", "tx-gone").dealsAwaitingProof(tracking), "the named deal is no longer tracked")
        assertEquals(setOf(DealId("d1"), DealId("d2")), wake("prove_offer").dealsAwaitingProof(tracking), "a wake without one: every deal")
    }

    @Test
    fun a_list_without_transaction_ids_leaves_every_deal_in() {
        val tracking = listOf(deal("d1", lastOfferCode = 9), deal("d2", lastOfferCode = 2))

        assertEquals(setOf(DealId("d1")), wake("prove_offer", "tx-1").dealsAwaitingProof(tracking))
    }
}
