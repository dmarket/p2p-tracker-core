package com.dmarket.p2p.tracker.client.steam

import com.dmarket.p2p.tracker.model.OfferId

/**
 * Chooses the cheapest set of Steam `IEconService` calls to read the offer axis of a set of tracked offers,
 * then merges the results. Generic in what a read yields, so the strategy stays about *which calls to make*
 * and knows nothing about the shape they return. Pure orchestration and transport-agnostic — the caller
 * passes the two fetch primitives, so this decision surface is unit-testable with zero mocks
 * (`SteamOfferStatusStrategyTest`) and is shared verbatim by the
 * [com.dmarket.p2p.tracker.port.steam.SteamReadClient] actual (`KtorSteamReadClient`) on every platform.
 *
 * Strategy (default [bulkThreshold] = 1, i.e. "batch when more than one offer is tracked"):
 *  - `size <= bulkThreshold` → one targeted [fetchSingle] (`GetTradeOffer?tradeofferid=`) per id.
 *    Issuing N per-offer calls risks Steam rate limits, so this path is used only for small counts
 *    (the single-offer case by default).
 *  - `size > bulkThreshold` → one account-wide [fetchAllBulk] (`GetTradeOffers`, sent **and** received)
 *    list call matched to the tracked ids, then a targeted [fetchSingle] for **each** tracked id
 *    absent from that list (Steam need not return every offer in the list), merged in — the list
 *    wins on conflict. This collapses the common case to a single request while staying correct for
 *    ids the list omits.
 *
 * Both primitives are **direction-agnostic**: a tracked deal can be one this account is buying (the
 * backend serves the watch list to both sides of a trade), whose offer is a *received* one. The single
 * read resolves either direction from the id alone; the bulk read has to ask for both arrays, which is
 * why it does.
 *
 * An offer both calls report as unknown (either primitive returns `null` / omits the id) is left out
 * of the result — the caller treats a missing key as "Steam doesn't know this offer yet".
 */
internal object SteamOfferStatusStrategy {

    suspend fun <T> resolve(
        offerIds: Set<OfferId>,
        bulkThreshold: Int,
        fetchSingle: suspend (OfferId) -> T?,
        fetchAllBulk: suspend () -> Map<OfferId, T>,
    ): Map<OfferId, T> {
        if (offerIds.isEmpty()) return emptyMap()
        if (offerIds.size <= bulkThreshold) {
            return offerIds.readEachSingle(fetchSingle)
        }
        val fromList = fetchAllBulk().filterKeys { it in offerIds }
        val missing = offerIds - fromList.keys
        if (missing.isEmpty()) return fromList
        return fromList + missing.readEachSingle(fetchSingle)
    }

    /** One targeted `GetTradeOffer` per id; ids Steam doesn't know are dropped from the result. */
    private suspend fun <T> Set<OfferId>.readEachSingle(fetchSingle: suspend (OfferId) -> T?): Map<OfferId, T> =
        mapNotNull { id -> fetchSingle(id)?.let { id to it } }.toMap()
}
