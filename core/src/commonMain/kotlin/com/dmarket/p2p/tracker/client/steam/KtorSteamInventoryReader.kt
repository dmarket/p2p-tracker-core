package com.dmarket.p2p.tracker.client.steam

import com.dmarket.p2p.tracker.game.Cs2GameAdapter
import com.dmarket.p2p.tracker.game.GameAdapter
import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.steam.InventoryScan
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.port.steam.SteamInventoryReader
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText

/**
 * Ktor-backed [SteamInventoryReader]: reads the seller's **own** inventory for the active game from
 * `{communityBaseUrl}/inventory/{steamid}/{appid}/{ctx}` and returns the present asset ids for the
 * `report_inventory` directive's `POST /inventory` snapshot (R6; the backend computes the stale diff).
 *
 * Read-only and on the Steam side of the audit boundary: it uses the device-only [SteamCredential]
 * (its `subjectSteamId`) + the logged-in Steam cookie session (attached by the credentialed browser
 * engine, see [com.dmarket.p2p.tracker.client.createSteamHttpClient]); it never touches the marketplace. On the browser, requires
 * `host_permissions` for `https://steamcommunity.com`.
 *
 * **Paging.** Steam truncates the response at [pageCount] items and signals it with `more_items = 1` +
 * `last_assetid`; the next page is requested with `start_assetid = last_assetid`. This follows that
 * cursor and reports [InventoryScan.complete] = `true` only once the cursor is exhausted. Every early
 * exit — an unusable body, a missing or non-advancing cursor, an exhausted [maxPages] budget, or a
 * failed request on a later page — returns the ids gathered so far with `complete = false`, which the
 * loop forwards as `scan_complete=false` (the mass-cancel guard). A partial list with an honest flag is
 * strictly safer than a complete-looking empty one.
 *
 * [maxPages] is deliberately small. Each page is a sequential request under
 * [com.dmarket.p2p.tracker.config.HttpConfig.requestTimeoutMs], and on the browser this runs inside an
 * MV3 service worker that can be torn down; a large budget would risk spending the whole activation
 * here. `start_assetid` is perfectly resumable, so a budget overrun costs one deferred rescan rather
 * than correctness. It also bounds the burst against `/inventory/`, Steam's most throttled surface.
 *
 * A non-2xx response on the **first** page throws (`HttpStatusException`), which the loop maps to
 * `scan_complete=false` — never an empty set, which would read as a complete scan with every asset
 * stale. On a later page a failure (e.g. a 429 part-way through) keeps the pages already read instead
 * of discarding them.
 *
 * @param adapter supplies the game's `appid` + inventory context id (CS2 at v1).
 * @param pageCount items requested per page (Steam's `count`).
 * @param maxPages hard ceiling on requests per scan; exhausting it reports an incomplete scan.
 */
class KtorSteamInventoryReader(
    private val httpClient: HttpClient,
    private val communityBaseUrl: String = "https://steamcommunity.com",
    private val adapter: GameAdapter = Cs2GameAdapter(),
    private val pageCount: Int = 2000,
    private val maxPages: Int = 5,
) : SteamInventoryReader {

    override suspend fun scanOwnInventory(credential: SteamCredential): InventoryScan {
        val steamId = credential.subjectSteamId.value
        val appId = adapter.game.appId
        val contextId = adapter.inventoryContextId
        val url = "$communityBaseUrl/inventory/$steamId/$appId/$contextId"

        val collected = mutableSetOf<AssetId>()
        var cursor: String? = null

        repeat(maxPages) { index ->
            val body = if (index == 0) {
                fetchPage(url, cursor)
            } else {
                // A later page failing is not a reason to throw away the pages we already have.
                runCatching { fetchPage(url, cursor) }.getOrNull()
                    ?: return InventoryScan(collected, complete = false)
            }
            val page = runCatching { SteamReadResponses.inventoryPage(body) }.getOrNull()
                ?: return InventoryScan(collected, complete = false)
            if (!page.usable) return InventoryScan(collected, complete = false)
            collected += page.assetIds
            if (!page.moreItems) return InventoryScan(collected, complete = true)
            // Truncated, so we need a cursor that actually advances; otherwise we would re-request the
            // same page forever.
            val next = page.lastAssetId
            if (next == null || next == cursor) return InventoryScan(collected, complete = false)
            cursor = next
        }
        // Budget exhausted with `more_items` still set: honestly incomplete.
        return InventoryScan(collected, complete = false)
    }

    private suspend fun fetchPage(url: String, startAssetId: String?): String = httpClient.get(url) {
        parameter("l", "english")
        parameter("count", pageCount)
        if (startAssetId != null) parameter("start_assetid", startAssetId)
    }.bodyAsText()
}
