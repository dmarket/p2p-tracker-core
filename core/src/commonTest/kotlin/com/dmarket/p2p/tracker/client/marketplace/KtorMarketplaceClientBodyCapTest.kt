package com.dmarket.p2p.tracker.client.marketplace

import com.dmarket.p2p.tracker.client.createHttpClient
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.DirectiveId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.marketplace.DirectiveAction
import com.dmarket.p2p.tracker.model.marketplace.DirectiveOutcome
import com.dmarket.p2p.tracker.model.marketplace.DirectiveStatus
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusReport
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusSource
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceServerErrorException
import com.dmarket.p2p.tracker.port.marketplace.TradeStatusBatchInterruptedException
import com.dmarket.p2p.tracker.support.FakeMarketplaceAuthenticator
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The report body cap: `/trade-events` and `/trade-actions` batches split into requests under a byte limit. */
class KtorMarketplaceClientBodyCapTest {

    private val baseUrl = "https://gateway.dmarket.com"
    private val cap = 1_024

    private fun reports(n: Int) = List(n) { i ->
        TradeStatusReport(
            dealId = DealId("deal-$i"),
            source = if (i % 2 == 0) TradeStatusSource.OFFER else TradeStatusSource.HISTORY,
            steamStatusCode = 3,
            clientTime = Instant.fromEpochMilliseconds(1_700_000_000_000L + i),
        )
    }

    private fun outcomes(n: Int) = List(n) { i ->
        DirectiveOutcome(
            directiveId = DirectiveId("dir-$i"),
            action = DirectiveAction.CREATE_OFFER,
            status = DirectiveStatus.NEEDS_CONFIRMATION,
            dealId = DealId("deal-$i"),
            steamOfferId = OfferId("offer-$i"),
        )
    }

    private fun HttpRequestData.text() = (body as TextContent).text

    private fun HttpRequestData.reportsIn() = Json.parseToJsonElement(text()).jsonObject.getValue("reports").jsonArray.map { it.jsonObject }

    /** Answers every report of the request, accepted, in [order]. */
    private fun MockRequestHandleScope.acceptAll(
        request: HttpRequestData,
        order: (List<String>) -> List<String> = { it },
    ): HttpResponseData {
        val entries = request.reportsIn().map { report ->
            val directiveId = report["directiveId"]?.jsonPrimitive?.content
            if (directiveId != null) {
                """{"directiveId":"$directiveId","accepted":true}"""
            } else {
                """{"dealId":"${report.getValue("dealId").jsonPrimitive.content}","accepted":true}"""
            }
        }
        return respond(
            content = """{"results":[${order(entries).joinToString(",")}]}""",
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
    }

    private fun client(engine: MockEngine, maxBytes: Int = cap) = KtorMarketplaceClient(
        httpClient = createHttpClient(engine),
        baseUrl = baseUrl,
        authenticator = FakeMarketplaceAuthenticator(token = "test-bearer-token"),
        maxReportBodyBytes = maxBytes,
    )

    @Test
    fun trade_events_bodies_stay_under_the_cap_and_keep_report_order() = runTest {
        val sent = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            sent += request
            acceptAll(request)
        }
        val batch = reports(40)

        val results = client(engine).reportTradeStatus(batch)

        assertTrue(sent.size > 1, "a 40-report batch must not fit one $cap-byte body")
        sent.forEach { assertTrue(it.text().encodeToByteArray().size <= cap, "body of ${it.text().length} bytes") }
        assertEquals(batch.map { it.dealId.value }, sent.flatMap { r -> r.reportsIn().map { it.getValue("dealId").jsonPrimitive.content } })
        assertEquals(batch.map { it.dealId to it.source }, results.map { it.dealId to it.source })
        assertTrue(results.all { it.accepted })
    }

    @Test
    fun trade_events_results_are_paired_within_each_request_before_they_are_joined() = runTest {
        // Each request is answered in reverse. Paired per request, every report still gets its own result back,
        // in report order — a raw concatenation would hand the caller a reordered list spanning requests.
        val engine = MockEngine { request -> acceptAll(request) { it.reversed() } }
        val batch = reports(40)

        val results = client(engine).reportTradeStatus(batch)

        assertEquals(batch.map { it.dealId to it.source }, results.map { it.dealId to it.source })
        assertTrue(results.all { it.accepted })
    }

    @Test
    fun trade_actions_bodies_stay_under_the_cap_and_keep_outcome_order() = runTest {
        val sent = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            sent += request
            acceptAll(request)
        }
        val batch = outcomes(30)

        val acks = client(engine).reportDirectives(batch)

        assertTrue(sent.size > 1)
        sent.forEach { assertTrue(it.text().encodeToByteArray().size <= cap, "body of ${it.text().length} bytes") }
        assertEquals(
            batch.map { it.directiveId.value },
            sent.flatMap { r -> r.reportsIn().map { it.getValue("directiveId").jsonPrimitive.content } },
        )
        assertEquals(batch.map { it.directiveId }, acks.map { it.directiveId })
    }

    @Test
    fun no_cap_sends_the_whole_batch_in_one_request() = runTest {
        var requests = 0
        val engine = MockEngine { request ->
            requests++
            acceptAll(request)
        }
        val client = client(engine, maxBytes = 0)

        assertEquals(40, client.reportTradeStatus(reports(40)).size)
        assertEquals(30, client.reportDirectives(outcomes(30)).size)
        assertEquals(2, requests)
    }

    @Test
    fun a_report_larger_than_the_cap_goes_alone() = runTest {
        val sent = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            sent += request
            acceptAll(request)
        }
        val batch = outcomes(6).toMutableList()
        batch[3] = batch[3].copy(status = DirectiveStatus.FAILED, steamOfferId = null, error = "x".repeat(2 * cap))

        val acks = client(engine).reportDirectives(batch)

        val oversized = sent.single { it.text().encodeToByteArray().size > cap }
        assertEquals(listOf("dir-3"), oversized.reportsIn().map { it.getValue("directiveId").jsonPrimitive.content })
        assertEquals(batch.map { it.directiveId }, acks.map { it.directiveId })
    }

    @Test
    fun a_failed_second_trade_events_request_returns_what_the_first_delivered() = runTest {
        val sent = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            sent += request
            if (sent.size == 2) respondError(HttpStatusCode.InternalServerError) else acceptAll(request)
        }
        val batch = reports(40)

        val failure = assertFailsWith<TradeStatusBatchInterruptedException> { client(engine).reportTradeStatus(batch) }

        val firstSize = sent[0].reportsIn().size
        assertEquals(firstSize, failure.deliveredCount)
        assertEquals(batch.take(firstSize).map { it.dealId to it.source }, failure.results.map { it.dealId to it.source })
        assertIs<MarketplaceServerErrorException>(failure.cause)
        assertEquals(2, sent.size, "nothing is sent after a failed request")
    }

    @Test
    fun a_failed_second_trade_actions_request_returns_the_acks_of_the_first() = runTest {
        val sent = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            sent += request
            if (sent.size == 2) respondError(HttpStatusCode.InternalServerError) else acceptAll(request)
        }
        val batch = outcomes(30)

        val acks = client(engine).reportDirectives(batch)

        assertEquals(batch.take(sent[0].reportsIn().size).map { it.directiveId }, acks.map { it.directiveId })
        assertEquals(2, sent.size, "nothing is sent after a failed request")
    }

    @Test
    fun a_failed_first_request_propagates_unwrapped() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.InternalServerError) }
        val client = client(engine)

        assertFailsWith<MarketplaceServerErrorException> { client.reportTradeStatus(reports(40)) }
        assertFailsWith<MarketplaceServerErrorException> { client.reportDirectives(outcomes(30)) }
    }
}
