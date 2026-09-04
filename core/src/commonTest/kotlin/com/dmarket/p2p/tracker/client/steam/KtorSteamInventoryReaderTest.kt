package com.dmarket.p2p.tracker.client.steam

import com.dmarket.p2p.tracker.client.HttpStatusException
import com.dmarket.p2p.tracker.client.createHttpClient
import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.support.fakeSteamCredential
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KtorSteamInventoryReaderTest {

    private val credential = fakeSteamCredential()

    /** Serves [bodies] in request order (the last one repeats), recording each request URL. */
    private fun reader(
        vararg bodies: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        record: MutableList<String>? = null,
        maxPages: Int = 5,
    ): KtorSteamInventoryReader {
        var index = 0
        val engine = MockEngine { request ->
            record?.add(request.url.toString())
            val body = bodies[minOf(index, bodies.lastIndex)]
            index++
            respond(content = body, status = status, headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        return KtorSteamInventoryReader(httpClient = createHttpClient(engine), maxPages = maxPages)
    }

    private fun page(vararg assetIds: String, moreItems: Boolean = false, lastAssetId: String? = null): String {
        val assets = assetIds.joinToString(",") { """{"assetid":"$it"}""" }
        val more = if (moreItems) ""","more_items":1""" else ""
        val last = lastAssetId?.let { ""","last_assetid":"$it"""" } ?: ""
        return """{"success":1,"assets":[$assets]$more$last}"""
    }

    @Test
    fun parses_present_asset_ids_from_a_single_complete_page() = runTest {
        val scan = reader(page("111", "222")).scanOwnInventory(credential)
        assertEquals(setOf(AssetId("111"), AssetId("222")), scan.assetIds)
        assertTrue(scan.complete, "a single untruncated page is a complete scan")
    }

    @Test
    fun follows_more_items_cursor_and_unions_all_pages() = runTest {
        val urls = mutableListOf<String>()
        val scan = reader(
            page("111", "222", moreItems = true, lastAssetId = "222"),
            page("333"),
            record = urls,
        ).scanOwnInventory(credential)

        assertEquals(setOf(AssetId("111"), AssetId("222"), AssetId("333")), scan.assetIds)
        assertTrue(scan.complete, "the cursor was exhausted, so the scan is complete")
        assertEquals(2, urls.size, "expected exactly one request per page: $urls")
        assertFalse(urls[0].contains("start_assetid"), "the first page must not send a cursor: ${urls[0]}")
        assertTrue(urls[1].contains("start_assetid=222"), "the second page must resume at last_assetid: ${urls[1]}")
    }

    @Test
    fun body_without_assets_is_not_a_complete_scan() = runTest {
        // Steam answers some rate-limit / private-inventory shapes with a 200 carrying no `assets` key.
        // Reporting that as a COMPLETE empty scan would tell the backend every on-sale asset is stale and
        // invite a mass cancel — the exact bug this replaces.
        val scan = reader("""{"success":1}""").scanOwnInventory(credential)
        assertTrue(scan.assetIds.isEmpty())
        assertFalse(scan.complete, "an assets-less body says nothing dependable about the inventory")
    }

    @Test
    fun genuinely_empty_inventory_is_a_complete_scan() = runTest {
        val scan = reader("""{"success":1,"assets":[],"total_inventory_count":0}""").scanOwnInventory(credential)
        assertTrue(scan.assetIds.isEmpty())
        assertTrue(scan.complete, "an explicit zero count is a trustworthy empty inventory")
    }

    @Test
    fun boolean_success_flag_still_decodes() = runTest {
        // `success` is typed JsonPrimitive precisely so a bool here cannot explode the whole decode and
        // silently disable stale-listing cancellation forever.
        val scan = reader("""{"success":true,"assets":[{"assetid":"111"}]}""").scanOwnInventory(credential)
        assertEquals(setOf(AssetId("111")), scan.assetIds)
        assertTrue(scan.complete)
    }

    @Test
    fun missing_success_flag_is_an_incomplete_scan() = runTest {
        val scan = reader("""{"assets":[{"assetid":"111"}]}""").scanOwnInventory(credential)
        assertFalse(scan.complete, "without a positive success signal the page is not trustworthy")
    }

    @Test
    fun truncated_page_without_last_assetid_is_an_incomplete_scan() = runTest {
        val urls = mutableListOf<String>()
        val scan = reader(page("111", moreItems = true), record = urls).scanOwnInventory(credential)
        assertEquals(setOf(AssetId("111")), scan.assetIds, "the ids we did read are kept")
        assertFalse(scan.complete)
        assertEquals(1, urls.size, "no blind re-fetch without a cursor")
    }

    @Test
    fun non_advancing_cursor_stops_and_reports_incomplete() = runTest {
        val urls = mutableListOf<String>()
        val scan = reader(page("111", moreItems = true, lastAssetId = "222"), record = urls).scanOwnInventory(credential)
        // Every page returns the same last_assetid, so the cursor never advances.
        assertFalse(scan.complete)
        assertEquals(2, urls.size, "must stop instead of looping forever: $urls")
    }

    @Test
    fun page_budget_exhaustion_yields_incomplete_scan() = runTest {
        val urls = mutableListOf<String>()
        var n = 0
        val engine = MockEngine {
            urls.add(it.url.toString())
            n++
            respond(
                content = page("id$n", moreItems = true, lastAssetId = "id$n"),
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val scan = KtorSteamInventoryReader(httpClient = createHttpClient(engine), maxPages = 2).scanOwnInventory(credential)
        assertFalse(scan.complete, "more_items still set when the budget ran out")
        assertEquals(2, urls.size, "the page budget is a hard ceiling")
        assertEquals(setOf(AssetId("id1"), AssetId("id2")), scan.assetIds, "pages read before the ceiling are kept")
    }

    @Test
    fun first_page_failure_throws_rather_than_returning_an_empty_complete_scan() = runTest {
        // A failed read must THROW so the loop maps it to scan_complete=false (the mass-cancel guard);
        // an empty set would read as a complete scan with every asset stale.
        assertFailsWith<HttpStatusException> {
            reader("error", status = HttpStatusCode.InternalServerError).scanOwnInventory(credential)
        }
    }

    @Test
    fun later_page_failure_keeps_the_pages_already_read() = runTest {
        var first = true
        val engine = MockEngine {
            if (first) {
                first = false
                respond(
                    content = page("111", moreItems = true, lastAssetId = "111"),
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            } else {
                // e.g. a 429 part-way through a large inventory.
                respond(content = "rate limited", status = HttpStatusCode.TooManyRequests)
            }
        }
        val scan = KtorSteamInventoryReader(httpClient = createHttpClient(engine)).scanOwnInventory(credential)
        assertEquals(setOf(AssetId("111")), scan.assetIds, "discarding page 1 because page 2 failed gains nothing")
        assertFalse(scan.complete)
    }

    @Test
    fun blank_asset_ids_are_skipped() = runTest {
        val body = """{"success":1,"assets":[{"assetid":""},{"assetid":"333"}]}"""
        assertEquals(setOf(AssetId("333")), reader(body).scanOwnInventory(credential).assetIds)
    }

    @Test
    fun requests_the_community_inventory_path_with_count() = runTest {
        val urls = mutableListOf<String>()
        reader(page(), record = urls).scanOwnInventory(credential)
        val url = urls.single()
        assertTrue(url.contains("/inventory/${credential.subjectSteamId.value}/730/2"), "unexpected inventory URL: $url")
        assertTrue(url.contains("count=2000"), "missing count param: $url")
    }
}
