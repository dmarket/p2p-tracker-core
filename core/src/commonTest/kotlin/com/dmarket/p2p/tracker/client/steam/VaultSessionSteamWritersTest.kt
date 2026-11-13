package com.dmarket.p2p.tracker.client.steam

import com.dmarket.p2p.tracker.adapter.host.InMemoryDeviceKeyValueStore
import com.dmarket.p2p.tracker.adapter.host.SealedSteamCookieVault
import com.dmarket.p2p.tracker.client.createSteamHttpClient
import com.dmarket.p2p.tracker.config.SteamWriteConfig
import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.steam.SteamCookie
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.model.steam.TradeDraft
import com.dmarket.p2p.tracker.policy.SteamCreateFailureCause
import com.dmarket.p2p.tracker.policy.SteamWriteThrottle
import com.dmarket.p2p.tracker.port.steam.CancelOfferResult
import com.dmarket.p2p.tracker.port.steam.CreateOfferResult
import com.dmarket.p2p.tracker.support.FakeClock
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The mobile writers: the session is the core's vault, sent by the client's [VaultCookieJar]. */
class VaultSessionSteamWritersTest {
    private val clock = FakeClock()
    private val vault = SealedSteamCookieVault(InMemoryDeviceKeyValueStore())
    private val jar = VaultCookieJar(vault, clock)
    private val seen = mutableListOf<HttpRequestData>()
    private var answer: MockRequestHandleScope.() -> HttpResponseData = { respond("", HttpStatusCode.InternalServerError) }
    private val http = createSteamHttpClient(
        MockEngine { request ->
            seen += request
            answer()
        },
        cookies = jar,
    )
    private val creator = HeaderSessionSteamOfferCreator(http, jar)
    private val canceller = HeaderSessionSteamOfferCanceller(http, jar)

    /** The scraped token: a different JWT from the vault's cookie, and never sent on a write. */
    private val credential = SteamCredential("eyJ.scraped.token", SteamId("76561198000000001"), Instant.fromEpochSeconds(1_786_356_000))
    private val draft = TradeDraft(SteamId("76561198000000002"), listOf(AssetId("asset-77")), tradeToken = "tok-9")
    private val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private suspend fun signedIn(sessionId: String? = "vault-sid") = vault.writeSteamCookies(
        listOfNotNull(
            SteamCookie("steamLoginSecure", "76561198000000001%7C%7Cvault.jwt", "steamcommunity.com"),
            SteamCookie("steamLoginSecure", "76561198000000001%7C%7Cstore.jwt", "store.steampowered.com"),
            sessionId?.let { SteamCookie("sessionid", it, "steamcommunity.com") },
            SteamCookie("steamRefresh_steam", "refresh", "login.steampowered.com"),
        ),
    )

    private fun HttpRequestData.form(): Map<String, String> {
        val text = (body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        return parseQueryString(text).entries().associate { (k, v) -> k to v.single() }
    }

    private fun HttpRequestData.cookies(): List<Pair<String, String>> =
        headers.getAll(HttpHeaders.Cookie).orEmpty().flatMap { it.split("; ") }.map { it.substringBefore('=') to it.substringAfter('=') }

    @Test
    fun aCreateSendsTheVaultSessionOnceAndStopsAtNeedsConfirmation() = runTest {
        signedIn()
        answer = { respond("""{"tradeofferid":"555","needs_mobile_confirmation":true}""", headers = json) }

        assertEquals(CreateOfferResult.NeedsConfirmation(OfferId("555")), creator.createOffer(credential, draft))

        val request = seen.single()
        assertEquals("https://steamcommunity.com/tradeoffer/new/send", request.url.toString())
        // One value per cookie, both the vault's; the scraped token is not a cookie at all.
        assertEquals(listOf("steamLoginSecure" to "76561198000000001%7C%7Cvault.jwt", "sessionid" to "vault-sid"), request.cookies())
        assertEquals("vault-sid", request.form()["sessionid"], "the form double-submits the cookie's value")
        assertEquals("https://steamcommunity.com/tradeoffer/new/?partner=39734274&token=tok-9", request.headers[HttpHeaders.Referrer])
        assertEquals("https://steamcommunity.com", request.headers[HttpHeaders.Origin])
    }

    @Test
    fun withNoSessionIdInTheVaultOneIsMadeStoredAndSentTheSameEverywhere() = runTest {
        signedIn(sessionId = null)
        answer = { respond("""{"tradeofferid":"9"}""", headers = json) }

        listOf(async { creator.createOffer(credential, draft) }, async { canceller.cancelOffer(credential, OfferId("9")) }).awaitAll()

        val stored = vault.readSteamCookies().filter { it.name == "sessionid" }
        assertEquals(1, stored.size, "two writes at once store one sessionid")
        assertEquals("steamcommunity.com", stored.single().domain)
        assertEquals(24, stored.single().value.length)
        for (request in seen) {
            assertEquals(listOf(stored.single().value), request.cookies().filter { it.first == "sessionid" }.map { it.second })
            assertEquals(stored.single().value, request.form()["sessionid"])
        }
    }

    @Test
    fun aCreateSteamRefusesIsFailedWithSteamsText() = runTest {
        signedIn()
        answer =
            { respond("""{"strError":"There was an error sending your trade offer. (26)"}""", HttpStatusCode.InternalServerError, json) }

        val result = assertIs<CreateOfferResult.Failed>(creator.createOffer(credential, draft))
        assertTrue("500" in result.error && "(26)" in result.error, result.error)
        assertTrue("vault.jwt" !in result.error)
    }

    @Test
    fun aCreateWithNoAnswerIsATransportFailure() = runTest {
        signedIn()
        answer = { error("connection reset") }

        val result = assertIs<CreateOfferResult.Failed>(creator.createOffer(credential, draft))
        assertEquals(SteamCreateFailureCause.TRANSPORT, SteamWriteThrottle.classifyCause(result.error, SteamWriteConfig()))
    }

    @Test
    fun aCreateOnAnExpiredSessionIsFailedAndNeverReadsAsAnOffer() = runTest {
        signedIn()
        // Steam answers a dead session with a 401, or with its sign-in page once a redirect is followed.
        for (expired in listOf<MockRequestHandleScope.() -> HttpResponseData>(
            { respond("", HttpStatusCode.Unauthorized) },
            { respond("<html>Sign In</html>", HttpStatusCode.OK) },
        )) {
            answer = expired
            val result = assertIs<CreateOfferResult.Failed>(creator.createOffer(credential, draft))
            assertEquals(SteamCreateFailureCause.OTHER, SteamWriteThrottle.classifyCause(result.error, SteamWriteConfig()), result.error)
        }
    }

    @Test
    fun aCancelPostsTheFixedUrlOnTheVaultSession() = runTest {
        signedIn()
        answer = { respond("""{"tradeofferid":"9"}""", headers = json) }

        assertEquals(CancelOfferResult.Cancelled, canceller.cancelOffer(credential, OfferId("9")))

        val request = seen.single()
        assertEquals("https://steamcommunity.com/tradeoffer/9/cancel", request.url.toString())
        assertEquals(listOf("steamLoginSecure" to "76561198000000001%7C%7Cvault.jwt", "sessionid" to "vault-sid"), request.cookies())
        assertEquals(mapOf("sessionid" to "vault-sid"), request.form())
        assertEquals("https://steamcommunity.com/tradeoffer/9/", request.headers[HttpHeaders.Referrer])
    }

    @Test
    fun aCancelSteamRefusesIsRefused() = runTest {
        signedIn()
        answer = { respond("""{"strError":"The trade offer could not be cancelled."}""", HttpStatusCode.InternalServerError, json) }

        val result = assertIs<CancelOfferResult.Refused>(canceller.cancelOffer(credential, OfferId("9")))
        assertTrue("could not be cancelled" in result.error, result.error)
    }
}
