package com.dmarket.p2p.tracker.loop

import com.dmarket.p2p.tracker.engine.ClaimVerdict
import com.dmarket.p2p.tracker.model.ClaimPhase
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.DealWriteClaim
import com.dmarket.p2p.tracker.model.DirectiveId
import com.dmarket.p2p.tracker.model.marketplace.DirectiveAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The write-claim guard under **genuine parallelism**, on a real multi-threaded dispatcher.
 *
 * The web target is a single-threaded service worker, so the common-source concurrency test can only
 * produce a cooperative interleave. Android and iOS drivers are not single-threaded — and
 * `TradeTrackerLoop.createTrade` is a host entry point that runs outside the cycle mutex — so the claim
 * store's own lock is the only thing standing between two threads and two live Steam offers for one deal.
 * This is the closest proxy for that available before those targets are enabled.
 */
class DealWriteClaimStoreThreadingTest {

    private val t0 = Instant.parse("2026-06-16T12:00:00Z")
    private val ttl = 15.minutes

    private fun claim(directiveId: String, dealId: String = "deal-1") = DealWriteClaim(
        dealId = DealId(dealId),
        action = DirectiveAction.CREATE_OFFER,
        phase = ClaimPhase.IN_FLIGHT,
        claimedAt = t0,
        directiveId = DirectiveId(directiveId),
    )

    @Test
    fun exactly_one_of_many_parallel_claims_for_one_deal_proceeds() = runBlocking {
        val store = PersistedDealWriteClaimStore()
        val verdicts = withContext(Dispatchers.Default) {
            (1..64).map { i -> async { store.claim(claim("dir-$i"), t0, ttl) } }.awaitAll()
        }
        assertEquals(1, verdicts.count { it is ClaimVerdict.Proceed })
        assertEquals(64, verdicts.size)
        assertEquals(1, store.all().size)
    }

    @Test
    fun parallel_claims_across_distinct_deals_all_proceed() = runBlocking {
        val store = PersistedDealWriteClaimStore()
        val verdicts = withContext(Dispatchers.Default) {
            (1..32).map { i -> async { store.claim(claim("dir-$i", dealId = "deal-$i"), t0, ttl) } }.awaitAll()
        }
        assertEquals(32, verdicts.count { it is ClaimVerdict.Proceed })
        assertEquals(32, store.all().size)
    }
}
