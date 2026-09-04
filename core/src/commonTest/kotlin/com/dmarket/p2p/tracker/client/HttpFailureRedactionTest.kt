package com.dmarket.p2p.tracker.client

import com.dmarket.p2p.tracker.net.NetworkRedaction
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The transport's redaction contract. Every assertion here is checked against **both** `message` and
 * `stackTraceToString()`: the latter is what the web host's crash reporter actually ships (the coroutine
 * machinery hands `globalThis.reportError` a `stackTraceToString`, which walks `cause` and suppressed
 * exceptions), so a clean `message` alone would not prove anything.
 */
class HttpFailureRedactionTest {
    private val secretJwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI3NjU2MTE5ODAwMDAwMDAwMSJ9.c2lnbmF0dXJl"
    private val steamReadUrl =
        "https://api.steampowered.com/IEconService/GetTradeOffers/v1/?access_token=$secretJwt&get_sent_offers=1"

    /** The Steam settoken/ajaxrefresh error shape: short enough that a body echo would carry all of it. */
    private val secretBody = """{"nonce":"NONCE-abc","auth":"AUTH-xyz","success":false}"""

    private fun engine(status: HttpStatusCode, body: String = "", retryAfter: String? = null) = MockEngine {
        respond(
            content = body,
            status = status,
            headers = if (retryAfter == null) {
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            } else {
                headersOf(
                    HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                    HttpHeaders.RetryAfter to listOf(retryAfter),
                )
            },
        )
    }

    @Test
    fun status_exception_carries_neither_the_body_nor_the_access_token() = runTest {
        val client = createHttpClient(engine = engine(HttpStatusCode.BadRequest, secretBody))

        val e = assertFailsWith<HttpStatusException> { client.get(steamReadUrl).bodyAsText() }

        assertEquals(400, e.statusCode)
        assertEquals("GET", e.method)
        assertNull(e.errorBody, "a request that did not opt in must carry no body at all")
        for (rendered in listOf(e.message!!, e.stackTraceToString())) {
            assertFalse(secretJwt in rendered, "access_token leaked into: $rendered")
            assertFalse("NONCE-abc" in rendered, "response body leaked into: $rendered")
            assertFalse("AUTH-xyz" in rendered, "response body leaked into: $rendered")
        }
        // Still diagnosable: status, method, host, path and the non-secret query params survive.
        assertTrue("access_token=${NetworkRedaction.REDACTED}" in e.message!!)
        assertTrue("get_sent_offers=1" in e.message!!)
        assertTrue("GetTradeOffers" in e.message!!)
    }

    @Test
    fun renamed_access_token_param_is_redacted_too() = runTest {
        // A config that renamed SteamEndpointsConfig.paramAccessToken must not desync the redactor.
        val client = createHttpClient(
            engine = engine(HttpStatusCode.Forbidden, secretBody),
            secretParamNames = NetworkRedaction.plusSecretParam("steam_tok"),
        )

        val e = assertFailsWith<HttpStatusException> {
            client.get("https://api.steampowered.com/x?steam_tok=$secretJwt").bodyAsText()
        }

        assertFalse(secretJwt in e.message!!)
        assertTrue("steam_tok=${NetworkRedaction.REDACTED}" in e.message!!)
    }

    @Test
    fun retry_after_is_preserved() = runTest {
        val client = createHttpClient(engine = engine(HttpStatusCode.TooManyRequests, retryAfter = "7"))

        val e = assertFailsWith<HttpStatusException> { client.get("https://h/p").bodyAsText() }

        assertEquals(7L, e.retryAfterSeconds)
        assertEquals(429, e.statusCode)
    }

    @Test
    fun error_body_is_captured_only_when_the_request_opts_in() = runTest {
        val body = """{"strError":"There was an error sending your trade offer. (26)","token":"$secretJwt"}"""

        val without = createHttpClient(engine = engine(HttpStatusCode.BadRequest, body))
        assertNull(assertFailsWith<HttpStatusException> { without.get("https://h/p").bodyAsText() }.errorBody)

        val with = createHttpClient(engine = engine(HttpStatusCode.BadRequest, body))
        val e = assertFailsWith<HttpStatusException> { with.get("https://h/p") { captureErrorBody(512) }.bodyAsText() }

        val captured = assertNotNull(e.errorBody)
        // The diagnosis survives; the credential does not.
        assertTrue("strError" in captured)
        assertTrue("(26)" in captured)
        assertFalse(secretJwt in captured)
        assertFalse(secretJwt in e.stackTraceToString())
    }

    @Test
    fun captured_error_body_is_capped() = runTest {
        val huge = """{"strError":"${"x".repeat(5_000)}"}"""
        val client = createHttpClient(engine = engine(HttpStatusCode.BadRequest, huge))

        val e = assertFailsWith<HttpStatusException> { client.get("https://h/p") { captureErrorBody(64) }.bodyAsText() }

        val captured = assertNotNull(e.errorBody)
        assertTrue(captured.length < 128, "expected a capped body, got ${captured.length} chars")
    }

    @Test
    fun timeout_exception_does_not_carry_the_access_token() = runTest {
        // Ktor's own HttpRequestTimeoutException message embeds the full request URL, query included.
        // NOTE: kept on the common source set on purpose, so it runs on the JS target too — the timeout's
        // delivery path (it cancels the execution context rather than throwing inline) is platform-specific.
        val client = createHttpClient(
            engine = MockEngine {
                delay(10_000) // far past the 1 ms budget below; virtual time under runTest
                respond(content = "", status = HttpStatusCode.OK)
            },
            requestTimeoutMs = 1,
        )

        val e = assertFailsWith<HttpTransportException> { client.get(steamReadUrl).bodyAsText() }

        assertEquals("timeout", e.kind)
        for (rendered in listOf(e.message!!, e.stackTraceToString())) {
            assertFalse(secretJwt in rendered, "access_token leaked into: $rendered")
        }
        assertTrue("access_token=${NetworkRedaction.REDACTED}" in e.message!!)
    }

    @Test
    fun a_successful_response_is_not_touched() = runTest {
        val client = createHttpClient(engine = engine(HttpStatusCode.OK, """{"ok":true}"""))

        assertEquals("""{"ok":true}""", client.get("https://h/p").bodyAsText())
    }
}
