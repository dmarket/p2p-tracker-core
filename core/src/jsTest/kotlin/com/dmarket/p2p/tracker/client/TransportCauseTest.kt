package com.dmarket.p2p.tracker.client

import com.dmarket.p2p.tracker.config.SteamWriteConfig
import com.dmarket.p2p.tracker.net.redactedSummary
import com.dmarket.p2p.tracker.policy.SteamCreateFailureCause
import com.dmarket.p2p.tracker.policy.SteamWriteThrottle
import io.ktor.client.engine.js.JsError
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A failed fetch as Ktor's JS engine reports it — `Error("Fail to fetch", JsError(<rejection>))` — with the rejection
 * Node's fetch makes: a `TypeError: fetch failed` whose `cause` says what happened.
 */
class TransportCauseTest {
    private val secretJwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI3NjU2MTE5ODAwMDAwMDAwMSJ9.c2lnbmF0dXJl"
    private val steamReadUrl = "https://api.steampowered.com/IEconService/GetTradeHistory/v1/?access_token=$secretJwt&max_trades=50"

    private fun failedFetch(rejection: dynamic) = Error("Fail to fetch", JsError(rejection))

    private fun nodeRejection(cause: dynamic): dynamic {
        val error: dynamic = js("new TypeError('fetch failed')")
        error.cause = cause
        return error
    }

    private fun systemError(code: String, message: String): dynamic {
        val error: dynamic = js("new Error()")
        error.code = code
        error.message = message
        return error
    }

    private fun client(rejection: dynamic, describe: Boolean = true) =
        createSteamHttpClient(engine = MockEngine { throw failedFetch(rejection) }, describeTransportCauses = describe)

    @Test
    fun a_failed_fetch_on_node_carries_its_cause() = runTest {
        val e = assertFailsWith<HttpTransportException> {
            client(nodeRejection(systemError("ECONNRESET", "read ECONNRESET"))).get(steamReadUrl).bodyAsText()
        }
        assertEquals("Fail to fetch", e.kind)
        assertEquals("read ECONNRESET", e.detail)
        assertTrue(e.message!!.startsWith("Fail to fetch (read ECONNRESET) on GET https://api.steampowered.com/"), e.message)
    }

    @Test
    fun an_undici_cause_is_named_by_its_code_and_a_nested_one_follows() = runTest {
        val socket = systemError("UND_ERR_SOCKET", "other side closed")
        socket.cause = systemError("ECONNRESET", "read ECONNRESET")
        val e = assertFailsWith<HttpTransportException> { client(nodeRejection(socket)).get(steamReadUrl).bodyAsText() }
        assertEquals("UND_ERR_SOCKET: other side closed <- read ECONNRESET", e.detail)
    }

    @Test
    fun the_summary_still_reads_as_a_transport_failure() = runTest {
        val e = assertFailsWith<HttpTransportException> {
            client(nodeRejection(systemError("ETIMEDOUT", "connect ETIMEDOUT 23.1.2.3:443"))).get(steamReadUrl).bodyAsText()
        }
        val summary = e.redactedSummary()
        assertTrue(summary.startsWith("HttpTransportException: Fail to fetch (connect ETIMEDOUT 23.1.2.3:443)"), summary)
        assertEquals(SteamCreateFailureCause.TRANSPORT, SteamWriteThrottle.classifyCause(summary, SteamWriteConfig()))
    }

    @Test
    fun neither_the_url_nor_the_cause_carries_a_secret() = runTest {
        val cause = systemError("UND_ERR", "request to $steamReadUrl failed, token $secretJwt")
        val e = assertFailsWith<HttpTransportException> { client(nodeRejection(cause)).get(steamReadUrl).bodyAsText() }
        for (rendered in listOf(e.message!!, e.stackTraceToString(), e.redactedSummary())) {
            assertFalse(secretJwt in rendered, "the token leaked into: $rendered")
        }
    }

    @Test
    fun a_browser_failure_with_nothing_under_it_is_left_as_it_was() = runTest {
        val e = assertFailsWith<Error> { client(js("new TypeError('Failed to fetch')")).get(steamReadUrl).bodyAsText() }
        assertEquals("Fail to fetch", e.message)
    }

    @Test
    fun without_the_option_the_failure_is_left_as_the_engine_threw_it() = runTest {
        val e = assertFailsWith<Error> {
            client(nodeRejection(systemError("ECONNRESET", "read ECONNRESET")), describe = false).get(steamReadUrl).bodyAsText()
        }
        assertEquals("Error: Fail to fetch", e.redactedSummary())
    }

    @Test
    fun a_failure_that_is_not_the_js_engines_has_no_cause_to_add() {
        assertNull(IllegalStateException("boom", IllegalStateException("inner")).transportCause())
    }
}
