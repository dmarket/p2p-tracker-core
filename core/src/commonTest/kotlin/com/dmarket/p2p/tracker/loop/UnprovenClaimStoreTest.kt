package com.dmarket.p2p.tracker.loop

import com.dmarket.p2p.tracker.adapter.host.InMemoryDeviceKeyValueStore
import com.dmarket.p2p.tracker.engine.ProofIntent
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnprovenClaimStoreTest {

    private val cancelled = ProofIntent(DealId("d1"), TradeStatusSource.OFFER, 6)
    private val accepted = ProofIntent(DealId("d1"), TradeStatusSource.OFFER, 3)

    @Test
    fun a_claim_survives_a_process_death() = runTest {
        // The reason this is persisted at all: the heartbeat cadence is shorter than the MV3 idle timeout, so
        // a worker respawns between most cycles. An in-memory-only claim would be forgotten on nearly every
        // wake, turning "tell them once" into "tell them forever".
        val storage = InMemoryDeviceKeyValueStore()
        PersistedUnprovenClaimStore(storage).claim(cancelled)

        assertEquals(setOf(cancelled), PersistedUnprovenClaimStore(storage).load())
    }

    @Test
    fun each_transition_is_claimed_on_its_own() = runTest {
        // Keyed on the whole intent — deal, axis and code — because a later decisive code on the same axis is
        // a different closure and must be reported in its own right.
        val store = PersistedUnprovenClaimStore()
        store.claim(cancelled)

        assertEquals(setOf(cancelled), store.load())
        store.claim(accepted)
        assertEquals(setOf(cancelled, accepted), store.load())
    }

    @Test
    fun claiming_twice_is_the_same_as_claiming_once() = runTest {
        val store = PersistedUnprovenClaimStore()
        store.claim(cancelled)
        store.claim(cancelled)

        assertEquals(setOf(cancelled), store.load())
    }

    @Test
    fun releasing_forgets_only_what_was_named() = runTest {
        val store = PersistedUnprovenClaimStore()
        store.claim(cancelled)
        store.claim(accepted)

        store.release(setOf(accepted))
        assertEquals(setOf(cancelled), store.load())
    }

    @Test
    fun an_unreadable_ledger_reads_as_nothing_claimed() = runTest {
        // Fail-OPEN, and the direction matters: reading a storage hiccup as "already told them" would silence
        // a closure the backend has never heard about, which is the failure this whole path exists to remove.
        // Reading it as "nothing claimed" costs one extra refused report.
        val storage = InMemoryDeviceKeyValueStore().apply { set(UNPROVEN_CLAIMS_KEY, "not json") }

        assertTrue(PersistedUnprovenClaimStore(storage).load().isEmpty())
    }

    private companion object {
        /** Spelled out rather than imported so the test breaks if the key is renamed under it. */
        const val UNPROVEN_CLAIMS_KEY = "tracker_unproven_claims"
    }
}
