package com.dmarket.p2p.tracker.loop

import com.dmarket.p2p.tracker.adapter.host.InMemoryDeviceKeyValueStore
import com.dmarket.p2p.tracker.credential.steam.DeviceVaultKeys
import com.dmarket.p2p.tracker.engine.ClaimVerdict
import com.dmarket.p2p.tracker.model.ClaimPhase
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.DealWriteClaim
import com.dmarket.p2p.tracker.model.DealWriteKey
import com.dmarket.p2p.tracker.model.DirectiveId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.marketplace.DirectiveAction
import com.dmarket.p2p.tracker.model.marketplace.DirectiveOutcome
import com.dmarket.p2p.tracker.model.marketplace.DirectiveStatus
import com.dmarket.p2p.tracker.port.host.DeviceKeyValueStore
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * A [DeviceKeyValueStore] that suspends inside every operation — the shape of every real backing store
 * (`storage.local`, `SharedPreferences`, `NSUserDefaults` are all async/off-thread). The suspension points
 * are exactly where a second caller could interleave, so these are the conditions under which
 * [PersistedDealWriteClaimStore]'s lock has to hold.
 */
private class SuspendingKeyValueStore(private val delegate: DeviceKeyValueStore = InMemoryDeviceKeyValueStore()) : DeviceKeyValueStore {
    override suspend fun get(key: String): String? {
        yield()
        return delegate.get(key)
    }

    override suspend fun set(key: String, value: String) {
        yield()
        delegate.set(key, value)
    }

    override suspend fun remove(key: String) {
        yield()
        delegate.remove(key)
    }
}

/** A store whose writes always fail — persistence is best-effort, so the guard must survive it. */
private class FailingWriteKeyValueStore(private val delegate: DeviceKeyValueStore = InMemoryDeviceKeyValueStore()) :
    DeviceKeyValueStore {
    override suspend fun get(key: String): String? = delegate.get(key)

    override suspend fun set(key: String, value: String): Unit = throw IllegalStateException("quota exceeded")

    override suspend fun remove(key: String) = delegate.remove(key)
}

class DealWriteClaimStoreTest {

    private val t0 = Instant.parse("2026-06-16T12:00:00Z")
    private val ttl = 15.minutes
    private val key = DealWriteKey(DealId("deal-1"), DirectiveAction.CREATE_OFFER)

    private fun claim(directiveId: String = "dir-1", dealId: String = "deal-1", at: Instant = t0) = DealWriteClaim(
        dealId = DealId(dealId),
        action = DirectiveAction.CREATE_OFFER,
        phase = ClaimPhase.IN_FLIGHT,
        claimedAt = at,
        directiveId = DirectiveId(directiveId),
    )

    private fun outcome(directiveId: String = "dir-1", offerId: String = "offer-1") = DirectiveOutcome(
        directiveId = DirectiveId(directiveId),
        action = DirectiveAction.CREATE_OFFER,
        status = DirectiveStatus.NEEDS_CONFIRMATION,
        dealId = DealId("deal-1"),
        steamOfferId = OfferId(offerId),
    )

    @Test
    fun first_claim_proceeds_and_a_second_one_is_blocked() = runTest {
        val store = PersistedDealWriteClaimStore()
        assertEquals(ClaimVerdict.Proceed, store.claim(claim(), t0, ttl))
        assertIs<ClaimVerdict.InFlight>(store.claim(claim(directiveId = "dir-2"), t0, ttl))
    }

    /**
     * The regression this store exists for: three callers racing for one deal, only one write.
     *
     * `runTest` is single-threaded, so this pins the *cooperative* interleave every target can produce (an
     * FE relay firing three times into one MV3 worker). The genuinely parallel case — the mobile drivers,
     * where the loop's entry points run on different threads — is covered by
     * `DealWriteClaimStoreThreadingTest` on the JVM.
     */
    @Test
    fun only_one_of_three_concurrent_claims_proceeds() = runTest {
        val store = PersistedDealWriteClaimStore(SuspendingKeyValueStore())
        val verdicts = listOf("dir-1", "dir-2", "dir-3")
            .map { id -> async { store.claim(claim(directiveId = id), t0, ttl) } }
            .map { it.await() }
        assertEquals(1, verdicts.count { it is ClaimVerdict.Proceed })
        assertEquals(2, verdicts.count { it is ClaimVerdict.InFlight })
    }

    @Test
    fun a_claim_for_a_different_deal_is_independent() = runTest {
        val store = PersistedDealWriteClaimStore()
        assertEquals(ClaimVerdict.Proceed, store.claim(claim(), t0, ttl))
        assertEquals(ClaimVerdict.Proceed, store.claim(claim(dealId = "deal-2"), t0, ttl))
    }

    @Test
    fun completing_a_claim_makes_the_next_caller_see_the_outcome_to_replay() = runTest {
        val store = PersistedDealWriteClaimStore()
        store.claim(claim(), t0, ttl)
        store.complete(key, outcome())
        val verdict = assertIs<ClaimVerdict.AlreadyCompleted>(store.claim(claim(directiveId = "dir-2"), t0, ttl))
        assertEquals(OfferId("offer-1"), verdict.claim.outcome?.steamOfferId)
    }

    @Test
    fun releasing_a_claim_lets_a_genuine_retry_through() = runTest {
        val store = PersistedDealWriteClaimStore()
        store.claim(claim(), t0, ttl)
        store.release(setOf(key))
        assertTrue(store.all().isEmpty())
        assertEquals(ClaimVerdict.Proceed, store.claim(claim(directiveId = "dir-2"), t0, ttl))
    }

    @Test
    fun an_expired_claim_no_longer_blocks() = runTest {
        val store = PersistedDealWriteClaimStore()
        store.claim(claim(), t0, ttl)
        store.complete(key, outcome())
        assertEquals(ClaimVerdict.Proceed, store.claim(claim(directiveId = "dir-2", at = t0 + ttl), t0 + ttl, ttl))
    }

    /** Completing a key nothing holds must not resurrect a released claim. */
    @Test
    fun completing_an_absent_key_is_a_no_op() = runTest {
        val store = PersistedDealWriteClaimStore()
        store.complete(key, outcome())
        assertTrue(store.all().isEmpty())
    }

    // ---- persistence ---------------------------------------------------------------------------

    @Test
    fun a_completed_claim_survives_a_new_store_over_the_same_storage() = runTest {
        val storage = InMemoryDeviceKeyValueStore()
        PersistedDealWriteClaimStore(storage).apply {
            claim(claim(), t0, ttl)
            complete(key, outcome())
        }
        // A fresh instance is what an MV3 respawn / Android process restart produces.
        val respawned = PersistedDealWriteClaimStore(storage)
        val verdict = assertIs<ClaimVerdict.AlreadyCompleted>(respawned.claim(claim(directiveId = "dir-2"), t0, ttl))
        assertEquals(OfferId("offer-1"), verdict.claim.outcome?.steamOfferId)
    }

    @Test
    fun a_released_claim_does_not_come_back_from_storage() = runTest {
        val storage = InMemoryDeviceKeyValueStore()
        PersistedDealWriteClaimStore(storage).apply {
            claim(claim(), t0, ttl)
            complete(key, outcome())
            release(setOf(key))
        }
        assertEquals(ClaimVerdict.Proceed, PersistedDealWriteClaimStore(storage).claim(claim(), t0, ttl))
    }

    @Test
    fun an_unparseable_stored_blob_is_ignored_rather_than_fatal() = runTest {
        val storage = InMemoryDeviceKeyValueStore()
        storage.set(DeviceVaultKeys.DEAL_WRITE_CLAIMS, "{not json")
        val store = PersistedDealWriteClaimStore(storage)
        assertEquals(ClaimVerdict.Proceed, store.claim(claim(), t0, ttl))
    }

    /** Durability is best-effort; blocking the duplicate is not. */
    @Test
    fun a_failing_storage_write_still_blocks_the_duplicate() = runTest {
        val store = PersistedDealWriteClaimStore(FailingWriteKeyValueStore())
        assertEquals(ClaimVerdict.Proceed, store.claim(claim(), t0, ttl))
        assertIs<ClaimVerdict.InFlight>(store.claim(claim(directiveId = "dir-2"), t0, ttl))
    }

    @Test
    fun all_exposes_the_stored_claims_for_reconciliation() = runTest {
        val store = PersistedDealWriteClaimStore()
        store.claim(claim(), t0, ttl)
        store.complete(key, outcome())
        val stored = store.all().single()
        assertEquals(ClaimPhase.COMPLETED, stored.phase)
        assertEquals(key, stored.key)
    }

    /** A claim is taken IN_FLIGHT even if the caller hands in a pre-completed record. */
    @Test
    fun a_taken_claim_never_starts_out_completed() = runTest {
        val store = PersistedDealWriteClaimStore()
        store.claim(claim().copy(phase = ClaimPhase.COMPLETED, outcome = outcome()), t0, ttl)
        val stored = store.all().single()
        assertEquals(ClaimPhase.IN_FLIGHT, stored.phase)
        assertNull(stored.outcome)
    }
}
