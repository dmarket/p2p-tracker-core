package com.dmarket.p2p.tracker.client.steam

import com.dmarket.p2p.tracker.adapter.host.InMemoryDeviceKeyValueStore
import com.dmarket.p2p.tracker.adapter.host.SealedSteamCookieVault
import com.dmarket.p2p.tracker.client.createSteamHttpClient
import com.dmarket.p2p.tracker.credential.steam.DefaultSteamSessionRefresher
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.steam.SteamCookie
import com.dmarket.p2p.tracker.port.SessionRefreshOutcome
import com.dmarket.p2p.tracker.port.steam.SteamWebSessionState
import com.dmarket.p2p.tracker.support.FakeClock
import com.dmarket.p2p.tracker.support.steamAccessToken
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class VaultSteamSessionTest {
    private val clock = FakeClock()
    private val vault = SealedSteamCookieVault(InMemoryDeviceKeyValueStore())
    private val jar = VaultCookieJar(vault, clock)
    private val alice = SteamId("76561198000000001")

    private fun loginSecure(left: Duration, steamId: SteamId = alice) =
        "${steamId.value}%7C%7C${steamAccessToken(steamId.value, clock.now() + left)}"

    private val refreshCookie = SteamCookie("steamRefresh_steam", "refresh-secret", "login.steampowered.com", httpOnly = true)

    private suspend fun session(left: Duration) = vault.writeSteamCookies(
        listOf(
            SteamCookie("steamLoginSecure", loginSecure(left), "steamcommunity.com"),
            SteamCookie("steamLoginSecure", loginSecure(left), "store.steampowered.com"),
            SteamCookie("sessionid", "sid", "steamcommunity.com"),
            refreshCookie,
        ),
    )

    private fun cookieNames(header: String?) = header.orEmpty().split("; ").filter { it.isNotEmpty() }.map { it.substringBefore('=') }

    @Test
    fun eachCookieGoesOnlyToItsOwnHost() = runTest {
        session(left = 20.hours)

        assertEquals(
            listOf("steamLoginSecure", "sessionid"),
            cookieNames(jar.cookieHeader(Url("https://steamcommunity.com/inventory/1/730/2"))),
        )
        assertEquals(listOf("steamLoginSecure"), cookieNames(jar.cookieHeader(Url("https://store.steampowered.com/login/settoken"))))
        assertEquals(listOf("steamRefresh_steam"), cookieNames(jar.cookieHeader(Url("https://login.steampowered.com/jwt/ajaxrefresh"))))
        assertNull(jar.cookieHeader(Url("https://api.steampowered.com/IEconService/GetTradeOffers/v1/")))
        assertNull(jar.cookieHeader(Url("https://help.steampowered.com/")))
        assertNull(jar.cookieHeader(Url("https://evil.login.steampowered.com/")))
    }

    @Test
    fun setCookieReplacesTheSameSlotAndAnExpiredOneErasesIt() = runTest {
        session(left = 20.hours)

        jar.save(
            Url("https://steamcommunity.com/login/settoken"),
            listOf("steamLoginSecure=fresh; Path=/; Secure; HttpOnly", "steamCountry=UA; Path=/", "sessionid=gone; Max-Age=0; Path=/"),
        )

        val community = vault.readSteamCookies().filter { it.domain == "steamcommunity.com" }.associateBy { it.name }
        assertEquals("fresh", community.getValue("steamLoginSecure").value)
        assertTrue(community.getValue("steamLoginSecure").httpOnly)
        assertEquals("UA", community.getValue("steamCountry").value)
        assertFalse("sessionid" in community)
        assertEquals(loginSecure(20.hours), vault.readSteamCookies().single { it.domain == "store.steampowered.com" }.value)
    }

    @Test
    fun anExpiredCookieIsNotSent() = runTest {
        vault.writeSteamCookies(
            listOf(
                SteamCookie(
                    "sessionid",
                    "old",
                    "steamcommunity.com",
                    expiresAtEpochSeconds =
                    clock.now().epochSeconds - 1,
                ),
            ),
        )

        assertNull(jar.cookieHeader(Url("https://steamcommunity.com/")))
    }

    @Test
    fun cookieValuesStayOutOfToString() {
        val text = refreshCookie.toString()

        assertFalse("refresh-secret" in text)
        assertTrue("steamRefresh_steam" in text)
    }

    /** Steam's refresh: ajaxrefresh on login, then settoken on the redir domain, whose Set-Cookie is the new session. */
    private fun steam(seen: MutableList<HttpRequestData>, rotated: String): MockEngine = MockEngine { request ->
        seen += request
        val path = request.url.encodedPath
        when {
            request.url.host == "login.steampowered.com" && path == "/jwt/ajaxrefresh" -> {
                val redir = (request.body as FormDataContent).formData["redir"]
                json(
                    """{"success":true,"steamID":"${alice.value}","nonce":"n","auth":"a","redir":"$redir","login_url":"$redir/login/settoken"}""",
                )
            }
            path == "/login/settoken" -> respond(
                """{"result":1}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.SetCookie, listOf("steamLoginSecure=$rotated; Path=/; Secure; HttpOnly; SameSite=None")),
            )
            else -> respond("", HttpStatusCode.NotFound)
        }
    }

    private fun MockRequestHandleScope.json(body: String) =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    @Test
    fun refreshFromTheVaultAloneReissuesSteamLoginSecureIntoTheVault() = runTest {
        session(left = 30.minutes)
        val seen = mutableListOf<HttpRequestData>()
        val rotated = loginSecure(24.hours)
        val http = createSteamHttpClient(steam(seen, rotated), cookies = jar)
        val refresher = DefaultSteamSessionRefresher(KtorSteamWebSessionGateway(http, jar), clock)

        assertEquals(SteamWebSessionState.NEEDS_REFRESH, refresher.sessionState(alice))
        assertEquals(SessionRefreshOutcome.REFRESHED, refresher.refreshSession())

        val stored = vault.readSteamCookies().filter { it.name == "steamLoginSecure" }.associate { it.domain to it.value }
        assertEquals(rotated, stored["steamcommunity.com"])
        assertEquals(rotated, stored["store.steampowered.com"])
        assertEquals(SteamWebSessionState.ALIVE, refresher.sessionState(alice))
        // The durable refresh cookie reached Steam on login.steampowered.com and nowhere else.
        seen.forEach { request ->
            val names = cookieNames(request.headers[HttpHeaders.Cookie])
            assertEquals(
                request.url.host == "login.steampowered.com",
                "steamRefresh_steam" in names,
                "${request.url.host}${request.url.encodedPath}",
            )
        }
        // Each POST carries the Origin and Referer of the domain being refreshed, as the browser's page would.
        val ajax = seen.filter { it.url.encodedPath == "/jwt/ajaxrefresh" }
        assertEquals(listOf("https://steamcommunity.com", "https://store.steampowered.com"), ajax.map { it.headers[HttpHeaders.Origin] })
        assertEquals(
            listOf("https://steamcommunity.com/", "https://store.steampowered.com/"),
            ajax.map {
                it.headers[HttpHeaders.Referrer]
            },
        )
        seen.filter { it.url.encodedPath == "/login/settoken" }.forEach {
            assertEquals("https://${it.url.host}", it.headers[HttpHeaders.Origin])
        }
        assertEquals(4, seen.size)
    }

    @Test
    fun aRotatedRefreshCookieIsKept() = runTest {
        session(left = 20.hours)

        jar.save(Url("https://login.steampowered.com/jwt/ajaxrefresh"), listOf("steamRefresh_steam=rotated; Path=/; Secure; HttpOnly"))

        val refresh = vault.readSteamCookies().filter { it.name == "steamRefresh_steam" }
        assertEquals(listOf("rotated"), refresh.map { it.value })
        assertEquals("login.steampowered.com", refresh.single().domain)
        assertNotEquals(refreshCookie.value, refresh.single().value)
    }
}
