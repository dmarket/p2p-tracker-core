package com.dmarket.p2p.tracker.client.steam

import com.dmarket.p2p.tracker.model.OfferId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure, transport-free coverage of the single-vs-list selection + per-offer fallback merge. The two
 * Steam primitives are fakes that record how they were exercised, so we can assert the exact request
 * shape (how many list calls, which ids fell back to a single read) with zero mocks.
 */
class SteamOfferStatusStrategyTest {

    private fun offer(id: String) = OfferId(id)

    private class Recorder(
        /** ids a single `GetTradeOffer` would resolve; anything else returns `null` (unknown). */
        val known: Map<OfferId, Int> = emptyMap(),
        /** the account-wide `GetTradeOffers` list response (both directions merged, as the client asks). */
        val listResult: Map<OfferId, Int> = emptyMap(),
    ) {
        val singleCalls = mutableListOf<OfferId>()
        var listCalls = 0
        val fetchSingle: suspend (OfferId) -> Int? = { id ->
            singleCalls += id
            known[id]
        }
        val fetchAllBulk: suspend () -> Map<OfferId, Int> = {
            listCalls++
            listResult
        }
    }

    private suspend fun resolve(rec: Recorder, ids: Set<OfferId>, threshold: Int = 1) =
        SteamOfferStatusStrategy.resolve(ids, threshold, rec.fetchSingle, rec.fetchAllBulk)

    @Test
    fun empty_set_makes_no_calls() = runTest {
        val rec = Recorder()
        assertEquals(emptyMap(), resolve(rec, emptySet()))
        assertEquals(0, rec.listCalls)
        assertEquals(emptyList(), rec.singleCalls)
    }

    @Test
    fun single_tracked_offer_uses_one_get_trade_offer() = runTest {
        val rec = Recorder(known = mapOf(offer("1") to 2))
        assertEquals(mapOf(offer("1") to 2), resolve(rec, setOf(offer("1"))))
        assertEquals(listOf(offer("1")), rec.singleCalls)
        assertEquals(0, rec.listCalls) // never touches the account-wide list for a single offer
    }

    @Test
    fun single_offer_unknown_to_steam_yields_empty() = runTest {
        val rec = Recorder() // fetchSingle returns null
        assertEquals(emptyMap(), resolve(rec, setOf(offer("1"))))
        assertEquals(listOf(offer("1")), rec.singleCalls)
    }

    @Test
    fun multiple_offers_all_in_list_use_one_batch_and_no_single_calls() = runTest {
        val rec = Recorder(listResult = mapOf(offer("1") to 2, offer("2") to 3, offer("99") to 6))
        assertEquals(mapOf(offer("1") to 2, offer("2") to 3), resolve(rec, setOf(offer("1"), offer("2"))))
        assertEquals(1, rec.listCalls)
        assertEquals(emptyList(), rec.singleCalls) // unrelated "99" filtered out; no fallbacks
    }

    @Test
    fun missing_offer_falls_back_to_single_and_merges() = runTest {
        val rec = Recorder(known = mapOf(offer("2") to 9), listResult = mapOf(offer("1") to 2))
        assertEquals(mapOf(offer("1") to 2, offer("2") to 9), resolve(rec, setOf(offer("1"), offer("2"))))
        assertEquals(1, rec.listCalls)
        assertEquals(listOf(offer("2")), rec.singleCalls) // only the id missing from the list
    }

    @Test
    fun none_in_list_falls_back_to_single_per_missing_id() = runTest {
        val rec = Recorder(known = mapOf(offer("1") to 2, offer("2") to 3))
        assertEquals(mapOf(offer("1") to 2, offer("2") to 3), resolve(rec, setOf(offer("1"), offer("2"))))
        assertEquals(1, rec.listCalls)
        assertEquals(setOf(offer("1"), offer("2")), rec.singleCalls.toSet())
        assertEquals(2, rec.singleCalls.size)
    }

    @Test
    fun missing_offer_unknown_to_single_is_omitted() = runTest {
        val rec = Recorder(listResult = mapOf(offer("1") to 2)) // "2" missing from list and unknown to single
        assertEquals(mapOf(offer("1") to 2), resolve(rec, setOf(offer("1"), offer("2"))))
        assertEquals(listOf(offer("2")), rec.singleCalls)
    }

    @Test
    fun raised_threshold_keeps_small_counts_on_per_offer_path() = runTest {
        val rec = Recorder(known = mapOf(offer("1") to 2, offer("2") to 3, offer("3") to 6))
        val result = resolve(rec, setOf(offer("1"), offer("2"), offer("3")), threshold = 5)
        assertEquals(mapOf(offer("1") to 2, offer("2") to 3, offer("3") to 6), result)
        assertEquals(0, rec.listCalls) // 3 <= 5 -> per-offer path
        assertEquals(3, rec.singleCalls.size)
    }
}
