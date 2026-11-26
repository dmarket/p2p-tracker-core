package com.dmarket.p2p.tracker.client.steam

import com.dmarket.p2p.tracker.client.createSteamHttpClient
import com.dmarket.p2p.tracker.credential.steam.DefaultSteamSessionRefresher
import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.model.steam.TradeDraft
import com.dmarket.p2p.tracker.port.host.STEAM_COOKIE_STORE_UNREADABLE_REASON
import com.dmarket.p2p.tracker.port.host.SteamCookieStoreException
import com.dmarket.p2p.tracker.port.steam.CancelOfferResult
import com.dmarket.p2p.tracker.port.steam.CreateOfferResult
import com.dmarket.p2p.tracker.port.steam.SteamWebSessionState
import com.dmarket.p2p.tracker.support.FakeBrowserCookieStore
import com.dmarket.p2p.tracker.support.FakeClock
import com.dmarket.p2p.tracker.support.steamAccessToken
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** The jar and the writers over a store with a browser's semantics (the WebView store, spike S2/S3). */
class WebCookieJarTest {
    private val clock = FakeClock()
    private val store = FakeBrowserCookieStore(clock)
    private val jar = WebCookieJar(store)
    private val alice = SteamId("76561198000000001")
    private val seen = mutableListOf<HttpRequestData>()
    private var setCookies: List<String> = emptyList()
    private var body = "{}"
    private val http = createSteamHttpClient(
        MockEngine { request ->
            seen += request
            respond(
                body,
                HttpStatusCode.OK,
                Headers.build {
                    append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setCookies.forEach { append(HttpHeaders.SetCookie, it) }
                },
            )
        },
        cookies = jar,
    )
    private val credential = SteamCredential("eyJ.scraped.token", alice, Instant.fromEpochSeconds(1_786_356_000))
    private val draft = TradeDraft(SteamId("76561198000000002"), listOf(AssetId("asset-77")), tradeToken = "tok-9")

    private fun loginSecure(steamId: SteamId = alice) = "${steamId.value}%7C%7C${steamAccessToken(steamId.value, clock.now() + 20.hours)}"

    private fun HttpRequestData.cookieNames() = headers[HttpHeaders.Cookie].orEmpty().split("; ").filter { it.isNotEmpty() }

    @Test
    fun aResponsesSetCookieReplacesTheValueTheNextRequestSends() = runTest {
        store.signIn("steamcommunity.com", "steamLoginSecure", "old")
        setCookies = listOf("steamLoginSecure=new; Path=/; Secure; HttpOnly; SameSite=None")

        http.get("https://steamcommunity.com/inventory/1/730/2")
        setCookies = emptyList()
        http.get("https://steamcommunity.com/tradeoffer/1/")

        assertEquals(listOf("steamLoginSecure=old"), seen[0].cookieNames())
        assertEquals(listOf("steamLoginSecure=new"), seen[1].cookieNames())
        assertEquals(1, store.slots.size, "the same slot, replaced")
        assertTrue(store.slots.single().httpOnly, "the line's attributes are kept")
    }

    @Test
    fun aHostOnlyCookieStaysOnItsHostADomainCookieReachesSubdomainsAForeignDomainIsDropped() = runTest {
        jar.save(
            Url("https://store.steampowered.com/login/settoken"),
            listOf("own=1; Path=/", "shared=2; Domain=steampowered.com; Path=/", "foreign=3; Domain=evil.com; Path=/"),
        )

        assertEquals("own=1; shared=2", jar.cookieHeader(Url("https://store.steampowered.com/")))
        assertEquals("shared=2", jar.cookieHeader(Url("https://help.steampowered.com/")))
        assertNull(jar.cookieHeader(Url("https://evil.com/")))
        assertNull(jar.cookieHeader(Url("https://steamcommunity.com/")))
    }

    @Test
    fun aDeletionRemovesOnlyTheSlotItsDomainNames() = runTest {
        val url = Url("https://store.steampowered.com/")
        jar.save(url, listOf("a=host; Path=/", "a=dom; Domain=steampowered.com; Path=/"))
        assertEquals("a=host; a=dom", jar.cookieHeader(url), "host-only and Domain= are two cookies")

        jar.save(url, listOf("a=; Path=/; Max-Age=0"))
        assertEquals("a=dom", jar.cookieHeader(url))

        jar.save(url, listOf("a=; Domain=steampowered.com; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT"))
        assertNull(jar.cookieHeader(url))
    }

    @Test
    fun aSessionIdIsMadeOnceHostOnlyAndReused() = runTest {
        val community = Url("https://steamcommunity.com/")
        val first = jar.valueOrPut(community, "sessionid") { "made-1" }
        val second = jar.valueOrPut(community, "sessionid") { "made-2" }

        assertEquals("made-1", first)
        assertEquals("made-1", second)
        val slot = store.slots.single()
        assertTrue(slot.hostOnly)
        assertFalse(slot.httpOnly, "Steam's pages read sessionid")
        assertNull(jar.value(Url("https://store.steampowered.com/"), "sessionid"))
    }

    @Test
    fun theRefreshedSteamLoginSecureIsWrittenHostOnlyAndHttpOnly() = runTest {
        val gateway = KtorSteamWebSessionGateway(http, jar)
        gateway.writeSessionCookie("steamcommunity.com", "fresh", expiresAtEpochSeconds = clock.now().epochSeconds + 3600)

        assertEquals("fresh", gateway.readCookie("steamcommunity.com", "steamLoginSecure")?.value)
        assertNull(gateway.readCookie("store.steampowered.com", "steamLoginSecure"))
        val slot = store.slots.single()
        assertTrue(slot.hostOnly && slot.httpOnly)
        assertEquals(clock.now().epochSeconds + 3600, slot.expires)
    }

    @Test
    fun aStoreThatCannotBeReadThrowsAndNeverReadsAsNoSession() = runTest {
        store.signIn("steamcommunity.com", "steamLoginSecure", loginSecure())
        store.failReads = true
        val gateway = KtorSteamWebSessionGateway(http, jar)
        val refresher = DefaultSteamSessionRefresher(gateway, clock)

        assertFailsWith<SteamCookieStoreException> { jar.cookieHeader(Url("https://steamcommunity.com/")) }
        assertFailsWith<SteamCookieStoreException> { http.get("https://steamcommunity.com/inventory/1/730/2") }
        assertTrue(seen.isEmpty(), "no request leaves without its cookies")
        assertFailsWith<SteamCookieStoreException> { CookieSteamSessionScraper(gateway).scrape() }
        // The refresher fails open: a read failure is never GONE, so no logout and no wrong-account verdict.
        assertEquals(SteamWebSessionState.ALIVE, refresher.sessionState(alice))
        assertNull(refresher.sessionSteamId())

        store.failReads = false
        assertEquals(SteamWebSessionState.ALIVE, refresher.sessionState(alice))
    }

    @Test
    fun aWriteWhoseSessionCannotBeReadIsNotSent() = runTest {
        store.signIn("steamcommunity.com", "steamLoginSecure", loginSecure())
        store.signIn("steamcommunity.com", "sessionid", "sid")
        store.failReads = true

        assertEquals(
            CreateOfferResult.NotSent(STEAM_COOKIE_STORE_UNREADABLE_REASON),
            HeaderSessionSteamOfferCreator(http, jar).createOffer(credential, draft),
        )
        assertEquals(
            CancelOfferResult.NotSent(STEAM_COOKIE_STORE_UNREADABLE_REASON),
            HeaderSessionSteamOfferCanceller(http, jar).cancelOffer(credential, OfferId("9")),
        )
        assertTrue(seen.isEmpty())
    }

    @Test
    fun aCreateSteamAnsweredKeepsItsAnswerWhenItsCookiesCannotBeStored() = runTest {
        store.signIn("steamcommunity.com", "steamLoginSecure", loginSecure())
        store.signIn("steamcommunity.com", "sessionid", "sid")
        store.failWrites = true
        body = """{"tradeofferid":"555","needs_mobile_confirmation":true}"""
        setCookies = listOf("steamCountry=UA; Path=/")

        val result = HeaderSessionSteamOfferCreator(http, jar).createOffer(credential, draft)

        assertEquals(1, seen.size, "the POST left")
        assertEquals(CreateOfferResult.NeedsConfirmation(OfferId("555")), result)
    }
}
