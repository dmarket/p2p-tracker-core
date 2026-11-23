package com.dmarket.p2p.tracker.loop

import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class PersistedProofArchiveStoreTest {
    private val finalAt = Instant.fromEpochMilliseconds(1_800_000_000_000)
    private val deal = DealId("deal-1")

    @Test
    fun a_proof_lives_thirty_days_from_the_final_status() = runTest {
        val store = PersistedProofArchiveStore()
        store.keep(deal, TradeStatusSource.HISTORY, 3, "proof-3", finalAt)

        store.prune(finalAt + 30.days - 1.milliseconds)
        assertEquals(setOf(deal), store.dealIds())
        store.prune(finalAt + 30.days)
        assertEquals(emptySet(), store.dealIds())
    }

    @Test
    fun a_later_final_status_moves_the_deals_expiry_and_keeps_both_proofs() = runTest {
        val store = PersistedProofArchiveStore()
        store.keep(deal, TradeStatusSource.HISTORY, 3, "proof-3", finalAt)
        store.keep(deal, TradeStatusSource.HISTORY, 12, "proof-12", finalAt + 5.days)

        store.prune(finalAt + 31.days)
        assertEquals(setOf(deal), store.dealIds())
        store.prune(finalAt + 35.days)
        assertEquals(emptySet(), store.dealIds())
    }

    @Test
    fun keeping_a_proof_drops_the_expired_ones_and_clear_drops_everything() = runTest {
        val store = PersistedProofArchiveStore()
        store.keep(deal, TradeStatusSource.OFFER, 7, "proof-7", finalAt)
        store.keep(DealId("deal-2"), TradeStatusSource.OFFER, 6, "proof-6", finalAt + 30.days)
        assertEquals(setOf(DealId("deal-2")), store.dealIds())

        store.clear()
        assertEquals(emptySet(), store.dealIds())
    }

    @Test
    fun end_of_deal_proofs_are_the_terminal_sites_of_d1() {
        for (code in listOf(4, 6, 7, 8, 10)) assertTrue(ProofArchiveStore.endsDeal(TradeStatusSource.OFFER, code), "offer $code")
        for (code in listOf(2, 3, 9, 11)) assertFalse(ProofArchiveStore.endsDeal(TradeStatusSource.OFFER, code), "offer $code")
        for (code in listOf(
            3,
            9,
            4,
            5,
            6,
            7,
            11,
            12,
        )) {
            assertTrue(ProofArchiveStore.endsDeal(TradeStatusSource.HISTORY, code), "history $code")
        }
        assertFalse(ProofArchiveStore.endsDeal(TradeStatusSource.HISTORY, 13))
    }
}
