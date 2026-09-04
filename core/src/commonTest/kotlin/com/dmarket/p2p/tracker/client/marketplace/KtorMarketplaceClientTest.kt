package com.dmarket.p2p.tracker.client.marketplace

import com.dmarket.p2p.tracker.client.HttpStatusException
import com.dmarket.p2p.tracker.client.createHttpClient
import com.dmarket.p2p.tracker.config.MarketplaceRetryConfig
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.DeviceId
import com.dmarket.p2p.tracker.model.DirectiveId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.marketplace.DirectiveAction
import com.dmarket.p2p.tracker.model.marketplace.DirectiveOutcome
import com.dmarket.p2p.tracker.model.marketplace.DirectiveStatus
import com.dmarket.p2p.tracker.model.marketplace.HeartbeatRequest
import com.dmarket.p2p.tracker.model.marketplace.P2PDealState
import com.dmarket.p2p.tracker.model.marketplace.ProofSubmission
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusReport
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusSource
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceServerErrorException
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceUnauthorizedException
import com.dmarket.p2p.tracker.support.FakeMarketplaceAuthenticator
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class KtorMarketplaceClientTest {

    private val authenticator = FakeMarketplaceAuthenticator(token = "test-bearer-token")
    private val baseUrl = "https://gateway.dmarket.com"
    private val extBase = "$baseUrl/exchange/v1/p2p/ext"
    private val p2pBase = "$baseUrl/exchange/v1/p2p"

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun aHeartbeat() = HeartbeatRequest(
        clientVersion = "1.0.0",
        platform = "web_chrome",
        foreground = true,
        steamId = SteamId("76561198000000001"),
        deviceId = DeviceId("test-device-1"),
    )

    private fun aTradeStatusReport() = TradeStatusReport(
        dealId = DealId("d-1"),
        source = TradeStatusSource.OFFER,
        steamStatusCode = 3,
        clientTime = Instant.fromEpochMilliseconds(0L),
    )

    private fun aProofSubmission() = ProofSubmission(dealId = DealId("d-1"), proofPayload = "base64proof")

    private fun aDirectiveOutcome() = DirectiveOutcome(
        directiveId = DirectiveId("dir-1"),
        action = DirectiveAction.CREATE_OFFER,
        status = DirectiveStatus.NEEDS_CONFIRMATION,
        dealId = DealId("d-1"),
        steamOfferId = OfferId("offer-created"),
        error = null,
    )

    /** Deterministic: always draws the top of the `[0, until)` range, so jitter equals the ceiling. */
    private object MaxRandom : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextLong(until: Long): Long = until - 1
    }

    // Fast, deterministic retries by default so the 401-loop tests stay instant under runTest.
    private fun clientWith(
        engine: MockEngine,
        auth: FakeMarketplaceAuthenticator,
        retry: MarketplaceRetryConfig = MarketplaceRetryConfig(retryBaseDelayMs = 1, retryMaxDelayMs = 1),
        random: Random = Random(0),
    ) = KtorMarketplaceClient(createHttpClient(engine), baseUrl, auth, retry, random)

    // ---- heartbeat -------------------------------------------------------------------------

    @Test
    fun heartbeat_targets_the_ext_path_and_parses_ttl() = runTest {
        val engine = MockEngine { request ->
            assertEquals("$extBase/heartbeat", request.url.toString())
            // Raw token, no `Bearer ` scheme — the live exchange-gateway parses the header value as the JWT.
            assertEquals("test-bearer-token", request.headers["Authorization"])
            respond(
                content = """{"activeTracking":[],"directives":[],"ttlSeconds":120}""",
                headers = jsonHeaders(),
            )
        }
        val client = KtorMarketplaceClient(createHttpClient(engine), baseUrl, authenticator)
        assertEquals(120, client.heartbeat(aHeartbeat()).ttlSeconds)
    }

    @Test
    fun heartbeat_parses_active_tracking_and_directives() = runTest {
        val engine = MockEngine {
            respond(
                content = """
                    {"activeTracking":[{"dealId":"d1","proofRequired":true,"watch":["GetTradeOffer"]}],
                     "directives":[{"directiveId":"dir-1","action":"create_offer","dealId":"d1",
                       "partnerSteamId":"76561198000000002","assetIds":["a1"],"tradeToken":"tok","contextId":2}],
                     "ttlSeconds":60}
                """.trimIndent(),
                headers = jsonHeaders(),
            )
        }
        val client = KtorMarketplaceClient(createHttpClient(engine), baseUrl, authenticator)
        val response = client.heartbeat(aHeartbeat())
        assertEquals(1, response.activeTracking.size)
        assertEquals(DealId("d1"), response.activeTracking[0].dealId)
        assertTrue(response.activeTracking[0].proofRequired)
        assertEquals(1, response.directives.size)
        assertEquals(DealId("d1"), response.directives[0].dealId)
    }

    // ---- reportTradeStatus -----------------------------------------------------------------

    @Test
    fun report_trade_status_targets_the_ext_path_and_parses_result() = runTest {
        val engine = MockEngine { request ->
            assertEquals("$extBase/trade-events", request.url.toString())
            respond(
                content = """{"results":[{"dealId":"d-1","accepted":true}]}""",
                headers = jsonHeaders(),
            )
        }
        val client = KtorMarketplaceClient(createHttpClient(engine), baseUrl, authenticator)
        val results = client.reportTradeStatus(listOf(aTradeStatusReport()))
        assertEquals(1, results.size)
        assertTrue(results[0].accepted)
        assertEquals(DealId("d-1"), results[0].dealId)
    }

    // ---- submitProof -----------------------------------------------------------------------

    @Test
    fun submit_proof_targets_the_notary_ext_path() = runTest {
        val engine = MockEngine { request ->
            assertEquals("$extBase/notary", request.url.toString())
            respond(
                content = """{"dealId":"d-1","verified":true}""",
                headers = jsonHeaders(),
            )
        }
        val client = KtorMarketplaceClient(createHttpClient(engine), baseUrl, authenticator)
        val result = client.submitProof(aProofSubmission())
        assertTrue(result.verified)
        assertEquals(DealId("d-1"), result.dealId)
    }

    // ---- reportDirectives ------------------------------------------------------------------

    @Test
    fun report_directives_posts_a_batch_to_the_trade_actions_ext_path() = runTest {
        val engine = MockEngine { request ->
            assertEquals("$extBase/trade-actions", request.url.toString())
            val body = (request.body as TextContent).text
            // The wire shape is `{reports:[…]}` — the same envelope field /trade-events uses for its batch.
            assertTrue(body.contains("\"reports\""), body)
            assertTrue(body.contains("\"dir-1\"") && body.contains("\"dir-2\""), body)
            respond(
                content = """
                    {"results":[{"directiveId":"dir-1","accepted":true},
                                {"directiveId":"dir-2","accepted":false,"reason":"stale lease"}]}
                """.trimIndent(),
                headers = jsonHeaders(),
            )
        }
        val client = KtorMarketplaceClient(createHttpClient(engine), baseUrl, authenticator)

        val acks = client.reportDirectives(
            listOf(aDirectiveOutcome(), aDirectiveOutcome().copy(directiveId = DirectiveId("dir-2"))),
        )

        assertEquals(listOf("dir-1", "dir-2"), acks.map { it.directiveId.value })
        assertTrue(acks[0].accepted)
        assertFalse(acks[1].accepted)
        assertEquals("stale lease", acks[1].reason)
    }

    @Test
    fun report_directives_makes_no_request_for_an_empty_batch() = runTest {
        var requests = 0
        val engine = MockEngine {
            requests++
            respond(content = """{"results":[]}""", headers = jsonHeaders())
        }
        val client = KtorMarketplaceClient(createHttpClient(engine), baseUrl, authenticator)

        assertEquals(emptyList(), client.reportDirectives(emptyList()))
        assertEquals(0, requests)
    }

    // ---- acceptDeal (C2 endpoint) ----------------------------------------------------------

    @Test
    fun accept_deal_posts_to_the_p2p_accept_path_and_parses_applied() = runTest {
        val engine = MockEngine { request ->
            assertEquals("$p2pBase/deals/d-1/accept", request.url.toString())
            respond(
                content = """{"state":"P2P_DEAL_STATE_COMMITTED","applied":true}""",
                headers = jsonHeaders(),
            )
        }
        val client = KtorMarketplaceClient(createHttpClient(engine), baseUrl, authenticator)
        val result = client.acceptDeal(DealId("d-1"))
        assertTrue(result.applied)
        assertEquals(P2PDealState.COMMITTED, result.state)
    }

    // ---- getDeal (C2 endpoint) -------------------------------------------------------------

    @Test
    fun get_deal_fetches_from_the_p2p_path_and_parses_state() = runTest {
        val engine = MockEngine { request ->
            assertEquals("$p2pBase/deals/d-1", request.url.toString())
            respond(
                content = """{"dealId":"d-1","state":"P2P_DEAL_STATE_AWAITING_TRADE",
                    "buyerAccountId":"b","sellerAccountId":"s","offerId":"of","assetId":"a",
                    "price":{"currency":"USD","amount":"1000"},
                    "createTime":"2026-06-16T12:00:00Z","updateTime":"2026-06-16T13:00:00Z"}""",
                headers = jsonHeaders(),
            )
        }
        val client = KtorMarketplaceClient(createHttpClient(engine), baseUrl, authenticator)
        val deal = client.getDeal(DealId("d-1"))
        assertEquals(DealId("d-1"), deal.dealId)
        assertEquals(P2PDealState.AWAITING_TRADE, deal.state)
    }

    // ---- auth strategy --------------------------------------------------------------------------

    @Test
    fun no_authorization_header_when_authenticator_yields_null_token() = runTest {
        var authHeader: String? = "sentinel"
        val engine = MockEngine { request ->
            authHeader = request.headers["Authorization"]
            respond(
                content = """{"activeTracking":[],"directives":[],"ttlSeconds":60}""",
                headers = jsonHeaders(),
            )
        }
        val client = KtorMarketplaceClient(createHttpClient(engine), baseUrl, FakeMarketplaceAuthenticator(token = null))
        client.heartbeat(aHeartbeat())
        assertNull(authHeader, "no Authorization header should be sent when tokenOrNull() is null")
    }

    @Test
    fun unauthorized_recovers_on_the_first_retry_when_authenticator_refreshes() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls == 1) {
                respondError(HttpStatusCode.Unauthorized)
            } else {
                respond(content = """{"state":"P2P_DEAL_STATE_COMMITTED","applied":true}""", headers = jsonHeaders())
            }
        }
        val auth = FakeMarketplaceAuthenticator(token = "tok", refreshResult = true)
        val client = clientWith(engine, auth)
        assertTrue(client.acceptDeal(DealId("d-1")).applied)
        assertEquals(2, calls, "recovers on the first retry after a 401 — no further attempts")
        assertEquals(1, auth.refreshCalls)
    }

    @Test
    fun unauthorized_recovers_on_a_later_retry_within_the_budget() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            // 401, 401, then success on the second retry.
            if (calls <=
                2
            ) {
                respondError(HttpStatusCode.Unauthorized)
            } else {
                respond(content = """{"state":"P2P_DEAL_STATE_COMMITTED","applied":true}""", headers = jsonHeaders())
            }
        }
        val auth = FakeMarketplaceAuthenticator(token = "tok", refreshResult = true)
        val client = clientWith(engine, auth)
        assertTrue(client.acceptDeal(DealId("d-1")).applied)
        assertEquals(3, calls, "initial + two retries, recovering on the second")
        assertEquals(2, auth.refreshCalls)
    }

    @Test
    fun unauthorized_surfaces_missing_connection_when_authenticator_declines_retry() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respondError(HttpStatusCode.Unauthorized)
        }
        val auth = FakeMarketplaceAuthenticator(token = null, refreshResult = false)
        val client = clientWith(engine, auth)
        assertFailsWith<MarketplaceUnauthorizedException> { client.acceptDeal(DealId("d-1")) }
        assertEquals(1, calls, "must not retry when refreshOnUnauthorized() is false")
        assertEquals(1, auth.refreshCalls)
    }

    @Test
    fun unauthorized_surfaces_missing_connection_after_exhausting_retries() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respondError(HttpStatusCode.Unauthorized)
        }
        // Refresh "succeeds" (a token was re-minted) each time but every retry is still rejected — the
        // token is invalid and couldn't be refreshed to a usable one: the missing-connection case.
        val auth = FakeMarketplaceAuthenticator(token = "stale", refreshResult = true)
        val client = clientWith(engine, auth, retry = MarketplaceRetryConfig(maxRetries = 3, retryBaseDelayMs = 1, retryMaxDelayMs = 1))
        assertFailsWith<MarketplaceUnauthorizedException> { client.heartbeat(aHeartbeat()) }
        assertEquals(4, calls, "initial + maxRetries (3) attempts, then surface the missing connection")
        assertEquals(3, auth.refreshCalls)
    }

    // ---- the 401-retry path's own status mapping ----------------------------------------------------
    // A non-401 arriving on a RETRY used to rethrow the raw Ktor exception straight out of
    // retryOrUnauthorized: it skipped the 429/5xx classification the first-attempt path applies, and its
    // message carried the full request URL plus the whole response body.

    @Test
    fun a_5xx_on_a_401_retry_surfaces_as_a_marketplace_server_error() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls == 1) respondError(HttpStatusCode.Unauthorized) else respondError(HttpStatusCode.ServiceUnavailable)
        }
        val auth = FakeMarketplaceAuthenticator(token = "tok", refreshResult = true)
        val client = clientWith(engine, auth)

        val e = assertFailsWith<MarketplaceServerErrorException> { client.heartbeat(aHeartbeat()) }

        assertEquals(503, e.statusCode)
        assertEquals(2, calls)
    }

    @Test
    fun a_429_on_a_401_retry_stays_rate_limited() = runTest {
        // Must NOT become a server error: the loop treats a non-5xx server error as non-transient, so a
        // single burst of backpressure would flip the UI to DM_CONNECTION_ERROR instead of idling the tick.
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls == 1) {
                respondError(HttpStatusCode.Unauthorized)
            } else {
                respond(
                    content = "",
                    status = HttpStatusCode.TooManyRequests,
                    headers = headersOf(HttpHeaders.RetryAfter, "11"),
                )
            }
        }
        val auth = FakeMarketplaceAuthenticator(token = "tok", refreshResult = true)
        val client = clientWith(engine, auth)

        val e = assertFailsWith<RateLimitedException> { client.heartbeat(aHeartbeat()) }

        assertEquals(11L, e.retryAfterSeconds)
    }

    @Test
    fun a_3xx_propagates_unmapped_so_the_loop_debounces_it() = runTest {
        // A gateway answering a POST with a redirect (Ktor does not follow one on a POST) must stay in the
        // loop's catch-all statusCode-0 path, which is DEBOUNCED — not become an immediate sticky error.
        val engine = MockEngine { respondError(HttpStatusCode.Found) }
        val client = clientWith(engine, FakeMarketplaceAuthenticator(token = "tok", refreshResult = true))

        val e = assertFailsWith<HttpStatusException> { client.heartbeat(aHeartbeat()) }

        assertEquals(302, e.statusCode)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun unauthorized_retry_waits_between_attempts() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls == 1) {
                respondError(HttpStatusCode.Unauthorized)
            } else {
                respond(content = """{"activeTracking":[],"directives":[],"ttlSeconds":60}""", headers = jsonHeaders())
            }
        }
        val auth = FakeMarketplaceAuthenticator(token = "tok", refreshResult = true)
        // MaxRandom draws the top of the jitter range, so the retry delay is exactly retryBaseDelayMs.
        val client =
            clientWith(
                engine,
                auth,
                retry = MarketplaceRetryConfig(maxRetries = 1, retryBaseDelayMs = 1_000, retryMaxDelayMs = 8_000),
                random = MaxRandom,
            )
        val before = testScheduler.currentTime
        client.heartbeat(aHeartbeat())
        assertEquals(1_000L, testScheduler.currentTime - before, "the retry must be spaced by the backoff, not fired immediately")
    }

    @Test
    fun rate_limited_surfaces_retry_after() = runTest {
        val engine = MockEngine {
            respondError(HttpStatusCode.TooManyRequests, headers = headersOf(HttpHeaders.RetryAfter, "30"))
        }
        val client = KtorMarketplaceClient(createHttpClient(engine), baseUrl, authenticator)
        val ex = assertFailsWith<RateLimitedException> { client.heartbeat(aHeartbeat()) }
        assertEquals(30L, ex.retryAfterSeconds)
    }
}
