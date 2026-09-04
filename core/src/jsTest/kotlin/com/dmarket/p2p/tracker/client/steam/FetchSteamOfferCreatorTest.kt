package com.dmarket.p2p.tracker.client.steam

import com.dmarket.p2p.tracker.client.createHttpClient
import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.steam.TradeDraft
import com.dmarket.p2p.tracker.port.steam.CreateOfferResult
import com.dmarket.p2p.tracker.support.fakeSteamCredential
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Parity tests for [FetchSteamOfferCreator] over a Ktor [MockEngine], with stubbed
 * `globalThis.chrome` (`cookies` for the `sessionid` read + a no-op `declarativeNetRequest` for the
 * anti-CSRF rule). Asserts the fixed `…/tradeoffer/new/send` URL, the create form fields, and the
 * confirmation mapping / failure semantics.
 */
class FetchSteamOfferCreatorTest {

    private fun setupChrome(sessionId: String?) {
        val g: dynamic = js("globalThis")
        g["_testSessionId"] = sessionId
        js(
            """
            globalThis.chrome = {
                cookies: { get: function() {
                    return Promise.resolve(globalThis._testSessionId ? { value: globalThis._testSessionId } : null);
                } },
                declarativeNetRequest: { updateSessionRules: function() { return Promise.resolve(); } }
            };
            """,
        )
    }

    @AfterTest
    fun cleanup() {
        js("delete globalThis.chrome; delete globalThis._testSessionId;")
    }

    private fun creator(engine: MockEngine) = FetchSteamOfferCreator(httpClient = createHttpClient(engine))

    private val draft = TradeDraft(
        partner = SteamId("76561198000000001"),
        assetsToGive = listOf(AssetId("a1")),
        tradeToken = "tok",
    )

    @Test
    fun create_posts_to_fixed_url_and_maps_needs_confirmation() = runTest {
        setupChrome("sess123")
        var url: String? = null
        var body: String? = null
        val engine = MockEngine { request ->
            url = request.url.toString()
            body = (request.body as FormDataContent).bytes().decodeToString()
            respond(content = """{"tradeofferid":"555","needs_mobile_confirmation":true}""")
        }
        val result = creator(engine).createOffer(fakeSteamCredential(), draft)

        assertEquals(CreateOfferResult.NeedsConfirmation(OfferId("555")), result)
        val capturedUrl = url ?: error("no request URL captured")
        val capturedBody = body ?: error("no request body captured")
        assertTrue(capturedUrl.endsWith("/tradeoffer/new/send"), "unexpected create URL: $capturedUrl")
        assertTrue(capturedBody.contains("sessionid=sess123"), "missing sessionid: $capturedBody")
        assertTrue(capturedBody.contains("partner=76561198000000001"), "missing partner: $capturedBody")
        assertTrue(capturedBody.contains("json_tradeoffer="), "missing json_tradeoffer: $capturedBody")
    }

    @Test
    fun create_maps_created_when_no_confirmation_flag() = runTest {
        setupChrome("sess123")
        val engine = MockEngine { respond(content = """{"tradeofferid":"600"}""") }
        assertEquals(CreateOfferResult.Created(OfferId("600")), creator(engine).createOffer(fakeSteamCredential(), draft))
    }

    @Test
    fun create_fails_when_session_cookie_missing() = runTest {
        setupChrome(null)
        val engine = MockEngine { respond(content = "{}") }
        val result = creator(engine).createOffer(fakeSteamCredential(), draft)
        assertTrue(result is CreateOfferResult.Failed, "expected Failed, got $result")
    }

    @Test
    fun create_fails_on_non_ok_status_and_surfaces_steams_own_error_text() = runTest {
        setupChrome("sess123")
        val engine = MockEngine { respond(content = """{"strError":"nope"}""", status = HttpStatusCode.Forbidden) }
        val result = creator(engine).createOffer(fakeSteamCredential(), draft)
        assertTrue(result is CreateOfferResult.Failed, "expected Failed, got $result")
        // Steam's own message is the diagnosis for a failed create — it must survive the redaction.
        assertTrue("strError" in result.error, "expected Steam's error text, got ${result.error}")
        assertTrue("403" in result.error)
    }

    @Test
    fun create_failure_body_is_capped_and_redacted() = runTest {
        // The three shapes a name-keyed scrubber alone would miss (`Bearer <jwt>`, escaped JSON, a named
        // key), plus a body far over the cap. This string does not stay local: it becomes the directive
        // outcome POSTed to DMarket, is persisted in extension storage, and is handed to the web page.
        setupChrome("sess123")
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI3NjU2MTE5ODAwMDAwMDAwMSJ9.c2ln"
        val body =
            """{"strError":"denied Bearer $jwt","detail":"{\"nonce\":\"$jwt\"}","token":"$jwt","pad":"${"x".repeat(5_000)}"}"""
        val engine = MockEngine { respond(content = body, status = HttpStatusCode.BadRequest) }

        val result = creator(engine).createOffer(fakeSteamCredential(), draft)

        assertTrue(result is CreateOfferResult.Failed, "expected Failed, got $result")
        assertFalse(jwt in result.error, "token leaked into the create outcome: ${result.error}")
        assertTrue(result.error.length < 700, "expected a capped outcome, got ${result.error.length} chars")
        assertTrue("strError" in result.error, "the diagnosis must survive")
    }

    @Test
    fun create_fails_when_response_missing_tradeofferid() = runTest {
        setupChrome("sess123")
        val engine = MockEngine { respond(content = """{"ok":true}""") }
        val result = creator(engine).createOffer(fakeSteamCredential(), draft)
        assertTrue(result is CreateOfferResult.Failed, "expected Failed, got $result")
    }
}
