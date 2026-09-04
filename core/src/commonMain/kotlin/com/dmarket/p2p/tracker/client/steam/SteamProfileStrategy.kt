package com.dmarket.p2p.tracker.client.steam

import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.steam.SteamProfile
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Pure orchestration for a batched profile read, factored out of [KtorSteamProfileReader] so the
 * batching / fan-out / merge / ordering logic is unit-testable with fake suspend lambdas and zero
 * mocks (the [SteamOfferStatusStrategy] pattern).
 *
 * Composition:
 *  1. dedupe [ids], preserving first-seen order;
 *  2. chunk into ≤[batchSize] groups and fetch summaries for all chunks concurrently ([fetchSummaries]);
 *  3. for each id Steam actually returned, fetch its level concurrently but capped at [maxConcurrency]
 *     permits ([fetchLevel]); a level fetch that throws degrades to `null` (level is secondary data —
 *     a private profile already legitimately yields `null`);
 *  4. assemble one [SteamProfile] per returned id, in input order.
 *
 * A summaries chunk that throws propagates (via [awaitAll]) — the primary payload must not be
 * silently truncated. Ids Steam omits are simply absent from the result.
 */
internal object SteamProfileStrategy {

    suspend fun resolve(
        ids: List<SteamId>,
        batchSize: Int,
        maxConcurrency: Int,
        fetchSummaries: suspend (List<SteamId>) -> Map<SteamId, PlayerSummaryDto>,
        fetchLevel: suspend (SteamId) -> Int?,
    ): List<SteamProfile> {
        val orderedIds = ids.distinct()
        if (orderedIds.isEmpty()) return emptyList()

        // 2. Batched summaries, all chunks concurrently; merge into one id → summary map.
        val summaries: Map<SteamId, PlayerSummaryDto> = coroutineScope {
            orderedIds.chunked(batchSize)
                .map { chunk -> async { fetchSummaries(chunk) } }
                .awaitAll()
        }.fold(mutableMapOf()) { acc, chunk -> acc.apply { putAll(chunk) } }

        // 3. Level fan-out for known ids only, capped by a semaphore; failures degrade to null.
        val foundIds = orderedIds.filter { it in summaries }
        val gate = Semaphore(maxConcurrency)
        val levels: Map<SteamId, Int?> = coroutineScope {
            foundIds.map { id ->
                async { id to gate.withPermit { runCatching { fetchLevel(id) }.getOrNull() } }
            }.awaitAll()
        }.toMap()

        // 4. Assemble, preserving input order.
        return foundIds.map { id ->
            val summary = summaries.getValue(id)
            SteamProfile(
                steamId64 = id,
                nickname = summary.personaName,
                avatarSmallUrl = summary.avatar,
                avatarMediumUrl = summary.avatarMedium,
                avatarFullUrl = summary.avatarFull,
                level = levels[id],
            )
        }
    }
}
