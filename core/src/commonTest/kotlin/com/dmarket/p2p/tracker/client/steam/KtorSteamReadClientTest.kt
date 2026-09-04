package com.dmarket.p2p.tracker.client.steam

import com.dmarket.p2p.tracker.client.createHttpClient
import com.dmarket.p2p.tracker.config.SteamEndpointsConfig
import com.dmarket.p2p.tracker.engine.TransferCorrelation
import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.TradeId
import com.dmarket.p2p.tracker.support.fakeSteamCredential
import com.dmarket.p2p.tracker.support.fixture
import com.dmarket.p2p.tracker.support.offerStates
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class KtorSteamReadClientTest {

    private val credential = fakeSteamCredential()

    private fun steamClient(body: String, endpoints: SteamEndpointsConfig = SteamEndpointsConfig()): KtorSteamReadClient {
        val engine = MockEngine {
            respond(content = body, headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        return KtorSteamReadClient(httpClient = createHttpClient(engine), endpoints = endpoints)
    }

    /**
     * A URL-aware Steam stub: it serves distinct bodies per `IEconService` endpoint and records which
     * paths were hit (and which `tradeofferid`s were read singly), so we can assert the exact request
     * fan-out — one bulk list call, per-offer fallback only for ids the list omits.
     */
    private class RecordingSteam(
        val endpoints: SteamEndpointsConfig = SteamEndpointsConfig(),
        private val listBody: String = """{"response":{}}""",
        private val historyBody: String = """{"response":{}}""",
        private val singleBodies: Map<String, String> = emptyMap(),
    ) {
        val hits = mutableListOf<String>()

        /** Query parameters of each bulk-list request, so the flags it asks Steam for are assertable. */
        val listParams = mutableListOf<Map<String, String>>()

        /** The same, for the per-offer read — the flags there decide how much of Steam's answer is payload. */
        val singleParams = mutableListOf<Map<String, String>>()

        /** Derived, not recorded twice: the id IS one of the parameters above, and two lists could disagree. */
        val singleIds get() = singleParams.map { it[endpoints.paramTradeOfferId].orEmpty() }
        val client: KtorSteamReadClient

        init {
            val engine = MockEngine { request ->
                val path = request.url.encodedPath
                hits += path
                val params = request.url.parameters.entries().associate { it.key to it.value.first() }
                if (path == endpoints.getTradeOffersPath) listParams += params
                val body = when (path) {
                    endpoints.getTradeOfferPath -> {
                        singleParams += params
                        singleBodies[params[endpoints.paramTradeOfferId].orEmpty()] ?: """{"response":{}}"""
                    }
                    endpoints.getTradeOffersPath -> listBody
                    endpoints.getTradeHistoryPath -> historyBody
                    else -> "{}"
                }
                respond(content = body, headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            }
            client = KtorSteamReadClient(httpClient = createHttpClient(engine), endpoints = endpoints)
        }

        fun listCalls() = hits.count { it == endpoints.getTradeOffersPath }
        fun singleCalls() = hits.count { it == endpoints.getTradeOfferPath }
    }

    private fun sentOffer(id: String, state: Int) =
        """{"tradeofferid":"$id","accountid_other":1,"trade_offer_state":$state,"time_created":1,"time_updated":1}"""

    /** Same offer shape; Steam distinguishes the direction by which array carries it, not by any field. */
    private fun receivedOffer(id: String, state: Int) = sentOffer(id, state)

    private fun singleOffer(id: String, state: Int) = """{"response":{"offer":${sentOffer(id, state)}}}"""

    // ---- offer axis: targeted single-offer read (exactly one tracked offer) -------------------------

    @Test
    fun single_tracked_offer_read_via_get_trade_offer() = runTest {
        val body = singleOffer("3699870069", state = 2)
        val statuses = steamClient(body).offerStates(credential, setOf(OfferId("3699870069")))
        assertEquals(mapOf(OfferId("3699870069") to 2), statuses)
    }

    @Test
    fun offer_status_empty_when_steam_does_not_know_the_id() = runTest {
        val statuses = steamClient("""{"response":{}}""").offerStates(credential, setOf(OfferId("999")))
        assertEquals(emptyMap(), statuses)
    }

    @Test
    fun offer_statuses_empty_for_no_tracked_offers() = runTest {
        val statuses = steamClient("{}").offerStates(credential, emptySet())
        assertEquals(emptyMap(), statuses)
    }

    // ---- offer axis: bulk list + per-offer fallback when more than one offer is tracked -------------

    @Test
    fun multiple_offers_all_in_bulk_list_make_no_single_calls() = runTest {
        val recording = RecordingSteam(
            listBody = """{"response":{"trade_offers_sent":[${sentOffer("1", 2)},${sentOffer("2", 3)}]}}""",
        )
        val statuses = recording.client.offerStates(credential, setOf(OfferId("1"), OfferId("2")))
        assertEquals(mapOf(OfferId("1") to 2, OfferId("2") to 3), statuses)
        assertEquals(1, recording.listCalls())
        assertEquals(0, recording.singleCalls())
    }

    @Test
    fun multiple_offers_fall_back_to_single_get_for_ids_missing_from_the_list() = runTest {
        val recording = RecordingSteam(
            // the list returns only "1"; "2" must be recovered via a targeted GetTradeOffer.
            listBody = """{"response":{"trade_offers_sent":[${sentOffer("1", 2)}]}}""",
            singleBodies = mapOf("2" to singleOffer("2", state = 9)),
        )
        val statuses = recording.client.offerStates(credential, setOf(OfferId("1"), OfferId("2")))
        assertEquals(mapOf(OfferId("1") to 2, OfferId("2") to 9), statuses)
        assertEquals(1, recording.listCalls()) // one account-wide list call...
        assertEquals(listOf("2"), recording.singleIds) // ...then a single read only for the missing id
    }

    // ---- offer axis: both trade directions ---------------------------------------------------------
    //
    // A watched deal can be one this account is BUYING — the backend serves the deal-watch list to both
    // sides of a trade — and a purchase's offer is a *received* one. A sent-only bulk read omitted every
    // such offer, so each fell through to its own targeted call, every tick, forever.

    @Test
    fun bulk_list_asks_steam_for_both_directions() = runTest {
        val recording = RecordingSteam()
        recording.client.offerStates(credential, setOf(OfferId("1"), OfferId("2")))
        val params = recording.listParams.single()
        assertEquals("1", params[recording.endpoints.paramGetSentOffers])
        assertEquals("1", params[recording.endpoints.paramGetReceivedOffers])
    }

    /**
     * Both offer reads suppress item descriptions, and this pins the per-offer one — the read that shipped
     * without the flag while its two siblings had it. Descriptions are ~2.3 KB per item against a ~550 B
     * stub, none of it reaches `SteamOfferSnapshot`, and the same flag is what keeps this read fetching the
     * byte-identical document the offer-axis proven read has to decrypt.
     */
    @Test
    fun both_offer_reads_suppress_item_descriptions() = runTest {
        val recording = RecordingSteam()
        recording.client.offerStates(credential, setOf(OfferId("1"))) // one id — the per-offer read
        recording.client.offerStates(credential, setOf(OfferId("1"), OfferId("2"))) // two — the bulk list
        assertEquals("0", recording.singleParams.first()[recording.endpoints.paramGetDescriptions])
        assertEquals("0", recording.listParams.single()[recording.endpoints.paramGetDescriptions])
    }

    @Test
    fun a_received_offer_resolves_from_the_bulk_list_without_a_fallback_call() = runTest {
        val recording = RecordingSteam(
            listBody = """{"response":{"trade_offers_received":[${receivedOffer("11", 2)},${receivedOffer("12", 3)}]}}""",
        )
        val statuses = recording.client.offerStates(credential, setOf(OfferId("11"), OfferId("12")))
        assertEquals(mapOf(OfferId("11") to 2, OfferId("12") to 3), statuses)
        assertEquals(1, recording.listCalls())
        assertEquals(0, recording.singleCalls(), "received offers must come from the list, not N targeted reads")
    }

    @Test
    fun a_mixed_list_of_sales_and_purchases_resolves_in_one_call() = runTest {
        // The shape a heartbeat actually produces for an account that is selling and buying at once.
        val recording = RecordingSteam(
            listBody = """
                {"response":{
                  "trade_offers_sent":[${sentOffer("sale-offer", 9)}],
                  "trade_offers_received":[${receivedOffer("purchase-offer", 2)}]
                }}
            """.trimIndent(),
        )
        val statuses = recording.client.offerStates(credential, setOf(OfferId("sale-offer"), OfferId("purchase-offer")))
        assertEquals(mapOf(OfferId("sale-offer") to 9, OfferId("purchase-offer") to 2), statuses)
        assertEquals(1, recording.listCalls())
        assertEquals(0, recording.singleCalls())
    }

    @Test
    fun offers_unrelated_to_a_watched_deal_are_discarded_from_both_arrays() = runTest {
        // Reading the account's whole offer list is unavoidable for a batch read; keeping any of it is not.
        val recording = RecordingSteam(
            listBody = """
                {"response":{
                  "trade_offers_sent":[${sentOffer("watched", 2)},${sentOffer("someone-elses-trade", 3)}],
                  "trade_offers_received":[${receivedOffer("unrelated-gift", 2)}]
                }}
            """.trimIndent(),
        )
        val statuses = recording.client.offerStates(credential, setOf(OfferId("watched"), OfferId("also-watched")))
        assertEquals(mapOf(OfferId("watched") to 2), statuses)
    }

    // ---- history axis ------------------------------------------------------------------------------

    /** The captured live payload of a real trade-protection rollback (two records per reverted trade). */
    private suspend fun liveHistory() = steamClient(fixture("steam_trade_history.json")).recentTransfers(credential, maxTrades = 50)

    @Test
    fun recent_transfers_maps_each_trade_to_its_raw_status_in_payload_order() = runTest {
        // Order is load-bearing for the correlation's tiebreak, so assert the sequence, not just the set.
        assertEquals(listOf(3, 3, 12, 12, 3), liveHistory().map { it.status })
    }

    @Test
    fun both_asset_directions_are_folded_into_one_correlation_set() = runTest {
        // A reversal recorded on the receive side used to map to an EMPTY asset set, so it could never
        // correlate to its deal and that deal's history axis went silent for good.
        val received = liveHistory().single { it.tradeId == TradeId("744933614571240779") }
        assertEquals(setOf(AssetId("20725626846")), received.assetIds)
        val given = liveHistory().single { it.tradeId == TradeId("731422815690175777") }
        assertEquals(setOf(AssetId("51978272357")), given.assetIds)
    }

    @Test
    fun the_rollback_discriminators_survive_the_mapping() = runTest {
        val transfers = liveHistory()
        val compensating = transfers.single { it.tradeId == TradeId("594063027056175076") }
        assertEquals(TradeId("731422815690175777"), compensating.rollbackTradeId)
        assertNull(compensating.modifiedAt, "the compensating record carries no time_mod")

        val original = transfers.single { it.tradeId == TradeId("731422815690175777") }
        assertNull(original.rollbackTradeId, "the record that WAS rolled back names no rollback target")
        assertEquals(Instant.fromEpochSeconds(1_785_760_511), original.modifiedAt)
        assertEquals(Instant.fromEpochSeconds(1_785_760_349), original.initiatedAt)
        assertEquals(SteamId("76561198336610283"), original.partnerSteamId)
    }

    @Test
    fun the_trade_protection_window_is_read_off_the_row_that_still_has_one() = runTest {
        // `time_settlement` is the backend's only source for the real (per-item) protection window; without
        // this parse it settles on its own configured 7 days instead.
        val transfers = liveHistory()
        val settled = transfers.single { it.tradeId == TradeId("723541516341221347") }
        assertEquals(Instant.fromEpochSeconds(1_786_356_000), settled.settlementAt)
    }

    @Test
    fun a_rolled_back_row_carries_no_window_because_steam_clears_it() = runTest {
        // The negative half, and the reason the window has to ride EVERY history report: Steam clears
        // `time_settlement` on the same flip that sets `time_mod`, so a `12` structurally cannot carry it
        // and an earlier read is the only chance to capture it.
        val transfers = liveHistory()
        assertNull(transfers.single { it.tradeId == TradeId("731422815690175777") }.settlementAt)
        assertNull(transfers.single { it.tradeId == TradeId("744933614571240779") }.settlementAt)
    }

    @Test
    fun a_non_positive_settlement_time_is_absent_rather_than_epoch_zero() = runTest {
        // Promised to the backend explicitly: an omitted window means "not established", so an invented
        // 1970 window would read as a real one that expired 56 years ago.
        val body = """
            {"response":{"trades":[
              {"tradeid":"1","steamid_other":"76561198336610283","time_init":1785929600,
               "status":3,"time_settlement":0,
               "assets_given":[{"appid":730,"contextid":"2","assetid":"1"}]}
            ]}}
        """.trimIndent()
        assertNull(steamClient(body).recentTransfers(credential, maxTrades = 50).single().settlementAt)
    }

    @Test
    fun a_quoted_settlement_time_does_not_abort_the_whole_history_decode() = runTest {
        // Why the field is JsonPrimitive and not Long: a non-lenient decode of a quoted numeric would throw
        // and blind EVERY watched deal at once, not just this row's window.
        val body = """
            {"response":{"trades":[
              {"tradeid":"1","steamid_other":"76561198336610283","time_init":1785929600,
               "status":3,"time_settlement":"1786356000",
               "assets_given":[{"appid":730,"contextid":"2","assetid":"1"}]}
            ]}}
        """.trimIndent()
        val transfers = steamClient(body).recentTransfers(credential, maxTrades = 50)
        assertEquals(1, transfers.size, "a quoted numeric must not take the whole payload down")
        assertEquals(Instant.fromEpochSeconds(1_786_356_000), transfers.single().settlementAt)
    }

    @Test
    fun the_live_payload_correlates_to_the_reversal_not_to_its_compensating_twin() = runTest {
        // End-to-end over the real bytes: parse + select must agree that this asset was reversed.
        val selected = TransferCorrelation.select(liveHistory(), AssetId("51978272357"))
        assertEquals(12, selected?.status)
        assertEquals(TradeId("731422815690175777"), selected?.tradeId)
    }

    @Test
    fun a_compound_dmarket_asset_ref_correlates_over_the_real_bytes() = runTest {
        // The reported defect, over the wire: DMarket's `Deal.assetId` is a compound of Steam's identity
        // numbers, and matching it verbatim against `assetid` silenced every history-watched deal. Both the
        // row's ids and those identity fields come out of this one parse — the numbers below are the live
        // rollback row and the ref dev2 served for the same deal.
        val body = """
            {"response":{"trades":[
              {"tradeid":"739304749312013446","steamid_other":"76561198077327619","time_init":1785929600,
               "status":12,"time_mod":1785929822,
               "assets_received":[{"appid":730,"contextid":"2","assetid":"44977997680",
                 "classid":"1989275999","instanceid":"302028390","amount":"1"}]}
            ]}}
        """.trimIndent()
        val transfers = steamClient(body).recentTransfers(credential, maxTrades = 50)

        val row = transfers.single()
        assertEquals(setOf(AssetId("44977997680")), row.assetIds)
        assertEquals(setOf("44977997680", "730", "2", "1989275999", "302028390"), row.assetTokens)
        val selected = TransferCorrelation.select(transfers, AssetId("302028390:1989275999:44977997680:730"))
        assertEquals(12, selected?.status)
        assertEquals(TradeId("739304749312013446"), selected?.tradeId)
    }
}
