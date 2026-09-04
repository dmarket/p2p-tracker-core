package com.dmarket.p2p.tracker.debug

import com.dmarket.p2p.tracker.client.createHttpClient
import com.dmarket.p2p.tracker.client.marketplace.KtorMarketplaceClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives [C1ReportProbes] against a Ktor `MockEngine`, which is what the extraction from the
 * browser-only `DebugSession` bought: the probe code is now reachable without Chrome, so the wire
 * bodies it emits and the input it refuses are asserted here rather than only observed by eye in a
 * console. `reportInventory` in particular had no test anywhere in this repo before.
 *
 * The assertions are on **field names**, not just values — that is the half a live conformance run
 * cannot check for itself (the backend answers what it answers; whether we spelled the request the way
 * the contract says is on us).
 */
class C1ReportProbesTest {

    private val baseUrl = "https://gateway.dmarket.com"
    private val extBase = "$baseUrl/exchange/v1/p2p/ext"

    /** The last request body the engine saw, or null when the probe refused before reaching transport. */
    private var sentBody: String? = null

    private fun probes(expectedUrl: String, response: String): C1ReportProbes {
        val engine = MockEngine { request ->
            assertEquals(expectedUrl, request.url.toString())
            sentBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            respond(content = response, headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        return C1ReportProbes(KtorMarketplaceClient(createHttpClient(engine), baseUrl))
    }

    /** A probe wired to an engine that FAILS the test if anything reaches it. */
    private fun probesThatMustNotSend(): C1ReportProbes = probes("", "")

    private fun sentJson(): JsonObject = Json.parseToJsonElement(sentBody ?: error("no request body captured")).jsonObject

    /**
     * The single action out of a `/trade-actions` body. The endpoint takes a batch (`{reports:[…]}`), and the
     * field-name assertions below are about the *action* object inside it, not the envelope.
     */
    private fun sentAction(): JsonObject = sentJson()["reports"]!!.jsonArray.single().jsonObject

    /** A `/trade-actions` response carrying one accepted result for [directiveId]. */
    private fun acceptedAction(directiveId: String = "dir-1") = """{"results":[{"directiveId":"$directiveId","accepted":true}]}"""

    // ---- POST /trade-events ------------------------------------------------------------------------

    @Test
    fun report_trade_status_sends_the_contract_field_names_and_parses_the_result() = runTest {
        val out = probes("$extBase/trade-events", """{"results":[{"dealId":"d-1","accepted":true,"reason":"ok"}]}""")
            .reportTradeStatus(dealId = "d-1", source = "offer", steamStatusCode = 3, clientTimeIso = "2026-01-01T00:00:00Z")

        val reports = sentJson()["reports"]!!.jsonArray
        assertEquals(1, reports.size)
        val report = reports[0].jsonObject
        assertEquals("d-1", report["dealId"]!!.jsonPrimitive.content)
        assertEquals("offer", report["source"]!!.jsonPrimitive.content)
        assertEquals(3, report["steamStatusCode"]!!.jsonPrimitive.int)
        assertEquals("2026-01-01T00:00:00Z", report["clientTime"]!!.jsonPrimitive.content)

        assertTrue(out["ok"]!!.jsonPrimitive.boolean)
        assertEquals(1, out["sent"]!!.jsonPrimitive.int)
        val result = out["results"]!!.jsonArray[0].jsonObject
        assertEquals("d-1", result["dealId"]!!.jsonPrimitive.content)
        assertTrue(result["accepted"]!!.jsonPrimitive.boolean)
        assertEquals("ok", result["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun report_trade_status_batch_sends_one_entry_per_deal_in_a_single_request() = runTest {
        val response = """{"results":[{"dealId":"d-1","accepted":true},{"dealId":"d-2","accepted":false}]}"""
        val out = probes("$extBase/trade-events", response).reportTradeStatusBatch(
            dealIds = listOf("d-1", "d-2"),
            source = "history",
            steamStatusCode = 12,
            clientTimeIso = "2026-01-01T00:00:00Z",
        )

        val reports = sentJson()["reports"]!!.jsonArray
        assertEquals(2, reports.size)
        assertEquals(listOf("d-1", "d-2"), reports.map { it.jsonObject["dealId"]!!.jsonPrimitive.content })
        // Every entry carries the history axis: on this axis 12 means "reversed", on the offer axis it does not.
        assertTrue(reports.all { it.jsonObject["source"]!!.jsonPrimitive.content == "history" })
        assertEquals(2, out["sent"]!!.jsonPrimitive.int)
        assertEquals(2, out["results"]!!.jsonArray.size)
    }

    @Test
    fun report_trade_status_refuses_an_unknown_source_rather_than_reporting_on_the_offer_axis() = runTest {
        val error = assertFailsWith<IllegalArgumentException> {
            probesThatMustNotSend().reportTradeStatus(
                "d-1",
                source = "histroy",
                steamStatusCode = 12,
                clientTimeIso = "2026-01-01T00:00:00Z",
            )
        }
        assertTrue("histroy" in error.message!!, "the message must quote the rejected value: ${error.message}")
        assertTrue("offer" in error.message!! && "history" in error.message!!, "and name the accepted set: ${error.message}")
        assertNull(sentBody, "a report with an unresolved axis must never reach the wire")
    }

    @Test
    fun report_trade_status_refuses_a_client_time_that_is_not_an_instant() = runTest {
        val error = assertFailsWith<IllegalArgumentException> {
            probesThatMustNotSend().reportTradeStatus("d-1", source = "offer", steamStatusCode = 3, clientTimeIso = "yesterday")
        }
        assertTrue("yesterday" in error.message!!, error.message!!)
        assertNull(sentBody)
    }

    @Test
    fun report_trade_status_refuses_an_empty_batch() = runTest {
        assertFailsWith<IllegalArgumentException> {
            probesThatMustNotSend().reportTradeStatusBatch(emptyList(), "offer", 3, "2026-01-01T00:00:00Z")
        }
        assertNull(sentBody)
    }

    // ---- POST /trade-actions ----------------------------------------------------------------------

    @Test
    fun report_directive_sends_the_contract_field_names_and_parses_the_ack() = runTest {
        val out = probes("$extBase/trade-actions", acceptedAction())
            .reportDirective(
                directiveId = "dir-1",
                dealId = "d-1",
                action = "create_offer",
                status = "needs_confirmation",
                steamOfferId = "4567890123",
            )

        val action = sentAction()
        assertEquals("dir-1", action["directiveId"]!!.jsonPrimitive.content)
        assertEquals("d-1", action["dealId"]!!.jsonPrimitive.content)
        assertEquals("create_offer", action["action"]!!.jsonPrimitive.content)
        assertEquals("needs_confirmation", action["status"]!!.jsonPrimitive.content)
        assertEquals("4567890123", action["steamOfferId"]!!.jsonPrimitive.content)

        assertEquals("dir-1", out["directiveId"]!!.jsonPrimitive.content)
        assertTrue(out["accepted"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun report_directive_refuses_an_unknown_action_rather_than_defaulting_to_create_offer() = runTest {
        val error = assertFailsWith<IllegalArgumentException> {
            probesThatMustNotSend().reportDirective("dir-1", "d-1", action = "cancel_offr", status = "success", steamOfferId = null)
        }
        assertTrue("cancel_offr" in error.message!!, error.message!!)
        assertTrue("cancel_offer" in error.message!!, "the message must name the accepted set: ${error.message}")
        // The reason this is a rejection and not a fallback: a create_offer outcome for a cancel directive
        // is well-formed, so the backend would accept it and release the lease for the wrong action.
        assertNull(sentBody, "a typo must not be reported as a different, valid action")
    }

    @Test
    fun report_directive_refuses_an_unknown_status() = runTest {
        val error = assertFailsWith<IllegalArgumentException> {
            probesThatMustNotSend().reportDirective("dir-1", "d-1", action = "create_offer", status = "ok", steamOfferId = null)
        }
        assertTrue("success" in error.message!!, error.message!!)
        assertNull(sentBody)
    }

    @Test
    fun report_directive_refuses_the_inbound_only_unknown_action() = runTest {
        // DirectiveAction.UNKNOWN exists so a newer backend cannot break an older client on the way IN;
        // reporting it back as an outcome would say nothing, so it is not a reportable action.
        assertFailsWith<IllegalArgumentException> {
            probesThatMustNotSend().reportDirective("dir-1", "d-1", action = "unknown", status = "success", steamOfferId = null)
        }
        assertNull(sentBody)
    }

    @Test
    fun report_directive_carries_the_error_detail_on_a_failed_outcome() = runTest {
        probes("$extBase/trade-actions", acceptedAction()).reportDirective(
            directiveId = "dir-1",
            dealId = "d-1",
            action = "cancel_offer",
            status = "failed",
            steamOfferId = null,
            error = "steam returned 429",
        )
        val action = sentAction()
        assertEquals("failed", action["status"]!!.jsonPrimitive.content)
        assertEquals("steam returned 429", action["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun report_directive_omits_the_error_field_when_there_is_none() = runTest {
        probes("$extBase/trade-actions", acceptedAction())
            .reportDirective("dir-1", "d-1", "create_offer", "success", null)
        // Key ABSENCE, not a null value: `"error": null` would also satisfy a contentOrNull check,
        // and it is the omission (TrackerJson's explicitNulls = false) this pins down.
        assertNull(sentAction()["error"], "error must be omitted from the action, not sent as null")
    }

    @Test
    fun report_directive_refuses_a_blank_directive_id_client_side() = runTest {
        // The guard downstream conformance runs rely on: a malformed id never
        // reaches the wire, which is a stronger guarantee than a server-side reject.
        val error = assertFailsWith<IllegalArgumentException> {
            probesThatMustNotSend().reportDirective("", "d-1", "create_offer", "success", null)
        }
        assertTrue("blank" in error.message!!.lowercase(), error.message!!)
        assertNull(sentBody)
    }

    // ---- POST /inventory --------------------------------------------------------------------------

    @Test
    fun report_inventory_sends_the_snapshot_and_parses_the_cancelled_offers() = runTest {
        val out = probes("$extBase/inventory", """{"cancelledOfferIds":["of-1","of-2"],"accepted":true}""")
            .reportInventory(
                directiveId = "dir-inv-1",
                steamId = "76561198000000001",
                deviceId = "device-1",
                scanComplete = true,
                presentAssetIds = listOf("ASSET-1", "ASSET-2"),
                contextId = 2,
            )

        val body = sentJson()
        assertEquals("dir-inv-1", body["directiveId"]!!.jsonPrimitive.content)
        assertEquals("76561198000000001", body["steamId"]!!.jsonPrimitive.content)
        assertEquals("device-1", body["deviceId"]!!.jsonPrimitive.content)
        assertTrue(body["scanComplete"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("ASSET-1", "ASSET-2"), body["presentAssetIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(2, body["contextId"]!!.jsonPrimitive.int)

        assertTrue(out["accepted"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("of-1", "of-2"), out["cancelledOfferIds"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun report_inventory_reports_an_incomplete_scan_honestly() = runTest {
        // scan_complete=false is the mass-cancel guard: the backend must not read a partial snapshot as
        // licence to cancel every on-sale asset missing from it.
        probes("$extBase/inventory", """{"accepted":true}""").reportInventory(
            directiveId = "dir-inv-1",
            steamId = "76561198000000001",
            deviceId = "device-1",
            scanComplete = false,
            presentAssetIds = emptyList(),
            contextId = 2,
        )
        val body = sentJson()
        assertEquals(false, body["scanComplete"]!!.jsonPrimitive.boolean)
        assertEquals(0, body["presentAssetIds"]!!.jsonArray.size)
    }

    // ---- POST /notary -----------------------------------------------------------------------------

    @Test
    fun submit_proof_posts_the_payload_and_parses_the_verdict() = runTest {
        val out = probes("$extBase/notary", """{"dealId":"d-1","verified":false,"reason":"notary deferred"}""")
            .submitProof(dealId = "d-1", proofPayload = "cGF5bG9hZA==")

        val body = sentJson()
        assertEquals("d-1", body["dealId"]!!.jsonPrimitive.content)
        assertEquals("cGF5bG9hZA==", body["proofPayload"]!!.jsonPrimitive.content)

        assertEquals("d-1", out["dealId"]!!.jsonPrimitive.content)
        assertEquals(false, out["verified"]!!.jsonPrimitive.boolean)
        assertEquals("notary deferred", out["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun submit_proof_refuses_a_blank_deal_id_client_side() = runTest {
        assertFailsWith<IllegalArgumentException> { probesThatMustNotSend().submitProof("", "cGF5bG9hZA==") }
        assertNull(sentBody)
    }
}
