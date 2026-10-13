package com.dmarket.p2p.tracker.client.steam

import com.dmarket.p2p.tracker.client.createHttpClient
import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.model.steam.TradeDraft
import com.dmarket.p2p.tracker.port.steam.CreateOfferResult
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

class HeaderSessionSteamWritersTest {

    private val credential = SteamCredential(
        token = "eyJ.header-session.token",
        subjectSteamId = SteamId("76561198000000001"),
        expiresAt = Instant.fromEpochSeconds(1_786_356_000),
    )
    private val draft = TradeDraft(
        partner = SteamId("76561198000000002"),
        assetsToGive = listOf(AssetId("asset-77")),
        tradeToken = "tok-9",
    )

    private val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun engine(seen: MutableList<HttpRequestData>, answer: MockRequestHandleScope.() -> HttpResponseData) = MockEngine { request ->
        seen += request
        answer()
    }

    private fun HttpRequestData.form(): Map<String, String> {
        val text = (body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        return parseQueryString(text).entries().associate { (k, v) -> k to v.single() }
    }

    @Test
    fun a_create_carries_the_session_and_the_anti_csrf_headers_itself() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val creator = HeaderSessionSteamOfferCreator(
            httpClient = createHttpClient(
                engine(seen) { respond("""{"tradeofferid":"555","needs_mobile_confirmation":true}""", headers = json) },
            ),
            sessionId = "0123456789abcdef01234567",
        )

        assertEquals(CreateOfferResult.NeedsConfirmation(OfferId("555")), creator.createOffer(credential, draft))

        val request = seen.single()
        assertEquals("https://steamcommunity.com/tradeoffer/new/send", request.url.toString())
        assertEquals(
            "steamLoginSecure=76561198000000001%7C%7CeyJ.header-session.token; sessionid=0123456789abcdef01234567",
            request.headers[HttpHeaders.Cookie],
        )
        // Steam matches the Referer's 32-bit partner against the body's steamid64.
        assertEquals("https://steamcommunity.com/tradeoffer/new/?partner=39734274&token=tok-9", request.headers[HttpHeaders.Referrer])
        assertEquals("https://steamcommunity.com", request.headers[HttpHeaders.Origin])

        val form = request.form()
        assertEquals("0123456789abcdef01234567", form["sessionid"], "the same value as the cookie: a double submit")
        assertEquals("76561198000000002", form["partner"])
        val offer = Json.parseToJsonElement(form.getValue("json_tradeoffer")).jsonObject
        val give = offer.getValue("me").jsonObject.getValue("assets").jsonArray.single().jsonObject
        assertEquals("asset-77", give.getValue("assetid").jsonPrimitive.content)
        assertEquals(730, give.getValue("appid").jsonPrimitive.int)
        assertTrue(offer.getValue("them").jsonObject.getValue("assets").jsonArray.isEmpty(), "nothing is asked for")
        assertEquals("""{"trade_offer_access_token":"tok-9"}""", form["trade_offer_create_params"])
    }

    @Test
    fun a_create_without_a_pending_confirmation_is_already_live() = runTest {
        val creator = HeaderSessionSteamOfferCreator(
            createHttpClient(engine(mutableListOf()) { respond("""{"tradeofferid":"600"}""", headers = json) }),
            sessionId = "s",
        )
        assertEquals(CreateOfferResult.Created(OfferId("600")), creator.createOffer(credential, draft))
    }

    @Test
    fun a_refused_create_keeps_steams_own_error_text() = runTest {
        val creator = HeaderSessionSteamOfferCreator(
            createHttpClient(
                engine(mutableListOf()) {
                    respondError(HttpStatusCode.InternalServerError, """{"strError":"There was an error sending your trade offer. (26)"}""")
                },
            ),
            sessionId = "s",
        )
        val result = creator.createOffer(credential, draft)
        assertTrue(result is CreateOfferResult.Failed)
        assertTrue("500" in result.error && "(26)" in result.error, result.error)
        assertTrue("header-session" !in result.error, "the token must not leak into the outcome")
    }

    @Test
    fun a_cancel_posts_the_fixed_url_with_the_session_headers() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val canceller = HeaderSessionSteamOfferCanceller(
            createHttpClient(engine(seen) { respond("""{"tradeofferid":"9"}""", headers = json) }),
            sessionId = "sid",
        )
        canceller.cancelOffer(credential, OfferId("9"))

        val request = seen.single()
        assertEquals("https://steamcommunity.com/tradeoffer/9/cancel", request.url.toString())
        assertEquals("steamLoginSecure=76561198000000001%7C%7CeyJ.header-session.token; sessionid=sid", request.headers[HttpHeaders.Cookie])
        assertEquals("https://steamcommunity.com", request.headers[HttpHeaders.Origin])
        assertEquals(mapOf("sessionid" to "sid"), request.form())
    }

    @Test
    fun a_cancel_refused_inside_a_2xx_throws() = runTest {
        val canceller = HeaderSessionSteamOfferCanceller(
            createHttpClient(engine(mutableListOf()) { respond("""{"success":15}""", headers = json) }),
            sessionId = "sid",
        )
        val e = assertFailsWith<IllegalStateException> { canceller.cancelOffer(credential, OfferId("9")) }
        assertEquals("Steam cancel refused with EResult 15", e.message)
    }

    @Test
    fun a_cancel_answered_with_either_ok_spelling_succeeds() = runTest {
        for (body in listOf("""{"success":1}""", """{"success":true}""", """{"tradeofferid":"9"}""", "not json")) {
            val canceller = HeaderSessionSteamOfferCanceller(
                createHttpClient(engine(mutableListOf()) { respond(body, headers = json) }),
                sessionId = "sid",
            )
            canceller.cancelOffer(credential, OfferId("9"))
        }
    }
}
