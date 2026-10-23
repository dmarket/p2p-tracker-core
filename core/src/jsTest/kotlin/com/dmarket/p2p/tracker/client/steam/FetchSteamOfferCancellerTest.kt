package com.dmarket.p2p.tracker.client.steam

import com.dmarket.p2p.tracker.client.createHttpClient
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.port.steam.CancelOfferResult
import com.dmarket.p2p.tracker.support.fakeSteamCredential
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Parity tests for [FetchSteamOfferCanceller] over a Ktor [MockEngine], with a stubbed
 * `globalThis.chrome.cookies` for the `sessionid` read. Asserts the fixed cancel URL, the sessionid
 * form field, and the envelope read on every status, which keeps the loop from reporting a still-live offer
 * as cancelled.
 */
class FetchSteamOfferCancellerTest {

    private fun setSessionId(value: String?) {
        val g: dynamic = js("globalThis")
        g["_testSessionId"] = value
        js(
            """
            globalThis.chrome = {
                cookies: { get: function() {
                    return Promise.resolve(globalThis._testSessionId ? { value: globalThis._testSessionId } : null);
                } }
            };
            """,
        )
    }

    @AfterTest
    fun cleanup() {
        js("delete globalThis.chrome; delete globalThis._testSessionId;")
    }

    private fun canceller(engine: MockEngine) = FetchSteamOfferCanceller(httpClient = createHttpClient(engine))

    @Test
    fun cancel_posts_to_fixed_url_with_sessionid() = runTest {
        setSessionId("sess123")
        var url: String? = null
        var body: String? = null
        val engine = MockEngine { request ->
            url = request.url.toString()
            body = (request.body as FormDataContent).bytes().decodeToString()
            respond(content = """{"success":1}""")
        }
        assertEquals(CancelOfferResult.Cancelled, canceller(engine).cancelOffer(fakeSteamCredential(), OfferId("789")))
        assertTrue(url!!.endsWith("/tradeoffer/789/cancel"), "unexpected cancel URL: $url")
        assertTrue(body!!.contains("sessionid=sess123"), "missing sessionid: $body")
    }

    @Test
    fun cancel_is_refused_without_posting_when_the_mint_sets_no_cookie() = runTest {
        setSessionId(null)
        val requests = mutableListOf<String>()
        val engine = MockEngine { request ->
            requests += "${request.method.value} ${request.url.encodedPath}"
            respond(content = "{}")
        }
        assertEquals(
            CancelOfferResult.Refused("no Steam session cookie"),
            canceller(engine).cancelOffer(fakeSteamCredential(), OfferId("1")),
        )
        assertEquals(listOf("GET /"), requests)
    }

    @Test
    fun cancel_mints_a_missing_session_cookie_before_posting() = runTest {
        setSessionId(null)
        val requests = mutableListOf<String>()
        var body: String? = null
        val engine = MockEngine { request ->
            requests += "${request.method.value} ${request.url.encodedPath}"
            if (request.url.encodedPath == "/") {
                js("globalThis._testSessionId = 'minted24'")
                respond(content = "<html></html>")
            } else {
                body = (request.body as FormDataContent).bytes().decodeToString()
                respond(content = """{"tradeofferid":"789"}""")
            }
        }
        assertEquals(CancelOfferResult.Cancelled, canceller(engine).cancelOffer(fakeSteamCredential(), OfferId("789")))
        assertEquals(listOf("GET /", "POST /tradeoffer/789/cancel"), requests)
        assertTrue(body.orEmpty().contains("sessionid=minted24"), "expected the minted sessionid: $body")
    }

    @Test
    fun cancel_without_an_envelope_under_a_non_ok_status_is_refused() = runTest {
        setSessionId("sess123")
        val engine = MockEngine { respond(content = "nope", status = HttpStatusCode.Forbidden) }
        assertEquals(
            CancelOfferResult.Refused("Steam cancel answer is not one of Steam's (HTTP 403)"),
            canceller(engine).cancelOffer(fakeSteamCredential(), OfferId("1")),
        )
    }

    @Test
    fun cancel_reads_eresult_11_under_a_500() = runTest {
        setSessionId("sess123")
        // Steam's answer for an offer no longer outstanding, as seen on dev: under a 500, not a 200.
        val engine = MockEngine { respond(content = """{"success":11}""", status = HttpStatusCode.InternalServerError) }
        assertEquals(CancelOfferResult.NotLive, canceller(engine).cancelOffer(fakeSteamCredential(), OfferId("1")))
    }

    @Test
    fun cancel_refused_inside_a_2xx_names_the_eresult() = runTest {
        setSessionId("sess123")
        val engine = MockEngine { respond(content = """{"success":15}""") }
        assertEquals(
            CancelOfferResult.Refused("Steam cancel refused with EResult 15"),
            canceller(engine).cancelOffer(fakeSteamCredential(), OfferId("1")),
        )
    }

    @Test
    fun cancel_accepts_a_body_without_an_eresult_envelope() = runTest {
        setSessionId("sess123")
        // A cancelled offer is answered `{"tradeofferid":…}` with no `success` key.
        val engine = MockEngine { respond(content = """{"tradeofferid":"789"}""") }
        assertEquals(CancelOfferResult.Cancelled, canceller(engine).cancelOffer(fakeSteamCredential(), OfferId("789")))
    }

    @Test
    fun cancel_answered_with_a_page_or_nothing_under_200_is_refused() = runTest {
        setSessionId("sess123")
        // A login page or an empty body is not a cancelled offer: reporting it SUCCESS would leave the offer live.
        for (body in listOf("", "<html>Sign In</html>")) {
            val engine = MockEngine { respond(content = body) }
            assertIs<CancelOfferResult.Refused>(canceller(engine).cancelOffer(fakeSteamCredential(), OfferId("789")), body)
        }
    }

    @Test
    fun cancel_accepts_a_boolean_success() = runTest {
        setSessionId("sess123")
        // Steam has been seen answering `true` where an int is documented (see SteamDtos.kt).
        val engine = MockEngine { respond(content = """{"success":true}""") }
        assertEquals(CancelOfferResult.Cancelled, canceller(engine).cancelOffer(fakeSteamCredential(), OfferId("789")))
    }
}
