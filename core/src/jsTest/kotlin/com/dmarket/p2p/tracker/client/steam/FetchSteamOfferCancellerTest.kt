package com.dmarket.p2p.tracker.client.steam

import com.dmarket.p2p.tracker.client.HttpStatusException
import com.dmarket.p2p.tracker.client.createHttpClient
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.support.fakeSteamCredential
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Parity tests for [FetchSteamOfferCanceller] over a Ktor [MockEngine], with a stubbed
 * `globalThis.chrome.cookies` for the `sessionid` read. Asserts the fixed cancel URL, the sessionid
 * form field, and the throw-on-failure contract (missing cookie / non-OK status / a 2xx carrying a non-OK
 * `EResult`) that keeps the loop from reporting a still-live offer as cancelled.
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
        canceller(engine).cancelOffer(fakeSteamCredential(), OfferId("789"))
        assertTrue(url!!.endsWith("/tradeoffer/789/cancel"), "unexpected cancel URL: $url")
        assertTrue(body!!.contains("sessionid=sess123"), "missing sessionid: $body")
    }

    @Test
    fun cancel_throws_when_session_cookie_missing() = runTest {
        setSessionId(null)
        val engine = MockEngine { respond(content = "{}") }
        assertFailsWith<IllegalStateException> { canceller(engine).cancelOffer(fakeSteamCredential(), OfferId("1")) }
    }

    @Test
    fun cancel_throws_on_non_ok() = runTest {
        setSessionId("sess123")
        val engine = MockEngine { respond(content = "nope", status = HttpStatusCode.Forbidden) }
        assertFailsWith<HttpStatusException> { canceller(engine).cancelOffer(fakeSteamCredential(), OfferId("1")) }
    }

    @Test
    fun cancel_throws_when_a_2xx_body_carries_a_non_ok_eresult() = runTest {
        setSessionId("sess123")
        // Steam answers some refusals 200 with its EResult envelope. Accepting the status alone would report
        // the directive SUCCESS — handled, create claim released — with the offer still live.
        val engine = MockEngine { respond(content = """{"success":11}""") }
        val failure = assertFailsWith<IllegalStateException> {
            canceller(engine).cancelOffer(fakeSteamCredential(), OfferId("1"))
        }
        assertTrue("11" in failure.message.orEmpty(), "EResult missing from message: ${failure.message}")
    }

    @Test
    fun cancel_accepts_a_body_without_an_eresult_envelope() = runTest {
        setSessionId("sess123")
        // A cancelled offer is answered `{"tradeofferid":…}` with no `success` key — that must stay a success,
        // or every cancel would be re-POSTed on the backend's next re-lease.
        val engine = MockEngine { respond(content = """{"tradeofferid":"789"}""") }
        canceller(engine).cancelOffer(fakeSteamCredential(), OfferId("789"))
    }

    @Test
    fun cancel_accepts_a_non_json_body() = runTest {
        setSessionId("sess123")
        // Unparseable is not a refusal: only a PRESENT, non-OK EResult is.
        val engine = MockEngine { respond(content = "") }
        canceller(engine).cancelOffer(fakeSteamCredential(), OfferId("789"))
    }

    @Test
    fun cancel_accepts_a_boolean_success() = runTest {
        setSessionId("sess123")
        // Steam has been seen answering `true` where an int is documented (see SteamDtos.kt).
        val engine = MockEngine { respond(content = """{"success":true}""") }
        canceller(engine).cancelOffer(fakeSteamCredential(), OfferId("789"))
    }
}
