package com.dmarket.p2p.tracker.client.steam

import com.dmarket.p2p.tracker.model.SteamId
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SteamProfileStrategyTest {

    private fun id(n: Int): SteamId = SteamId("7656119800000${(1000 + n)}") // 17-digit, unique per n
    private fun summary(id: SteamId) = PlayerSummaryDto(steamId = id.value, personaName = "user-${id.value.takeLast(4)}")

    private suspend fun allKnown(chunk: List<SteamId>): Map<SteamId, PlayerSummaryDto> = chunk.associateWith(::summary)

    @Test
    fun batches_summaries_into_chunks_of_at_most_batch_size() = runTest {
        val ids = (1..5).map(::id)
        val chunkSizes = mutableListOf<Int>()
        SteamProfileStrategy.resolve(
            ids = ids,
            batchSize = 2,
            maxConcurrency = 5,
            fetchSummaries = { chunk ->
                chunkSizes += chunk.size
                allKnown(chunk)
            },
            fetchLevel = { 1 },
        )
        assertEquals(listOf(2, 2, 1), chunkSizes.sortedDescending())
    }

    @Test
    fun preserves_input_order_and_dedupes() = runTest {
        val ids = listOf(id(3), id(1), id(3), id(2)) // duplicate id(3)
        val profiles = SteamProfileStrategy.resolve(
            ids = ids,
            batchSize = 100,
            maxConcurrency = 5,
            fetchSummaries = ::allKnown,
            fetchLevel = { 5 },
        )
        assertEquals(listOf(id(3), id(1), id(2)), profiles.map { it.steamId64 })
    }

    @Test
    fun omits_ids_steam_does_not_return() = runTest {
        val ids = listOf(id(1), id(2), id(3))
        val profiles = SteamProfileStrategy.resolve(
            ids = ids,
            batchSize = 100,
            maxConcurrency = 5,
            // only id(1) and id(3) are known to Steam
            fetchSummaries = { chunk -> chunk.filter { it != id(2) }.associateWith(::summary) },
            fetchLevel = { 7 },
        )
        assertEquals(listOf(id(1), id(3)), profiles.map { it.steamId64 })
    }

    @Test
    fun a_failing_level_fetch_degrades_to_null_without_failing_the_batch() = runTest {
        val ids = listOf(id(1), id(2))
        val profiles = SteamProfileStrategy.resolve(
            ids = ids,
            batchSize = 100,
            maxConcurrency = 5,
            fetchSummaries = ::allKnown,
            fetchLevel = { steamId -> if (steamId == id(2)) error("boom") else 42 },
        )
        assertEquals(mapOf(id(1) to 42, id(2) to null), profiles.associate { it.steamId64 to it.level })
    }

    @Test
    fun a_failing_summaries_chunk_propagates() = runTest {
        assertFailsWith<IllegalStateException> {
            SteamProfileStrategy.resolve(
                ids = (1..3).map(::id),
                batchSize = 1,
                maxConcurrency = 5,
                fetchSummaries = { chunk -> if (chunk.single() == id(2)) error("chunk down") else allKnown(chunk) },
                fetchLevel = { 1 },
            )
        }
    }

    @Test
    fun level_fan_out_never_exceeds_max_concurrency() = runTest {
        var inFlight = 0
        var peak = 0
        val ids = (1..8).map(::id)
        SteamProfileStrategy.resolve(
            ids = ids,
            batchSize = 100,
            maxConcurrency = 3,
            fetchSummaries = ::allKnown,
            fetchLevel = {
                inFlight++
                peak = maxOf(peak, inFlight)
                delay(100) // hold the permit so concurrent fetches overlap (virtual time under runTest)
                inFlight--
                1
            },
        )
        assertTrue(peak <= 3, "peak concurrency was $peak, expected <= 3")
        assertEquals(3, peak, "expected the semaphore to saturate at the cap with 8 ids")
    }
}
