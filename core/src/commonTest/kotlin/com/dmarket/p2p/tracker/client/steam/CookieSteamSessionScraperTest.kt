package com.dmarket.p2p.tracker.client.steam

import com.dmarket.p2p.tracker.credential.steam.DefaultSteamSessionRefresher
import com.dmarket.p2p.tracker.credential.steam.SteamCredentialProvider
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.port.WebCookie
import com.dmarket.p2p.tracker.support.FakeClock
import com.dmarket.p2p.tracker.support.FakeCredentialVault
import com.dmarket.p2p.tracker.support.FakeSteamWebSessionGateway
import com.dmarket.p2p.tracker.support.steamAccessToken
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class CookieSteamSessionScraperTest {
    private val owner = "76561198000000001"
    private val exp = Instant.fromEpochSeconds(1_900_000_000)
    private val token = steamAccessToken(owner, exp)

    private fun scraper(vararg cookies: Pair<Pair<String, String>, String>, name: String = "steamLoginSecure") = CookieSteamSessionScraper(
        FakeSteamWebSessionGateway(cookies.associate { (key, value) -> key to WebCookie(value, null) }.toMutableMap()),
        name,
    )

    @Test
    fun reads_the_credential_from_the_encoded_session_cookie() = runTest {
        val credential = assertNotNull(scraper(("steamcommunity.com" to "steamLoginSecure") to "$owner%7C%7C$token").scrape())
        assertEquals(token, credential.token)
        assertEquals(SteamId(owner), credential.subjectSteamId)
        assertEquals(exp, credential.expiresAt)
    }

    @Test
    fun reads_the_raw_separator_form() = runTest {
        assertEquals(token, scraper(("steamcommunity.com" to "steamLoginSecure") to "$owner||$token").scrape()?.token)
    }

    @Test
    fun no_cookie_is_no_credential() = runTest {
        assertNull(scraper().scrape())
    }

    @Test
    fun an_unparseable_cookie_is_no_credential() = runTest {
        assertNull(scraper(("steamcommunity.com" to "steamLoginSecure") to "$owner%7C%7Cnot-a-jwt").scrape())
    }

    @Test
    fun a_token_of_another_account_is_no_credential() = runTest {
        val other = steamAccessToken("76561198000000002", exp)
        assertNull(scraper(("steamcommunity.com" to "steamLoginSecure") to "$owner%7C%7C$other").scrape())
    }

    @Test
    fun a_failed_cookie_read_propagates() = runTest {
        val scraper = CookieSteamSessionScraper(FakeSteamWebSessionGateway(failWith = IllegalStateException("store down")))
        assertFailsWith<IllegalStateException> { scraper.scrape() }
    }

    @Test
    fun reads_only_the_configured_name_on_the_community_domain() = runTest {
        val value = "$owner%7C%7C$token"
        assertNull(scraper(("steamcommunity.com" to "other") to value).scrape(), "another cookie name")
        assertNull(scraper(("store.steampowered.com" to "steamLoginSecure") to value).scrape(), "another domain")
        assertEquals(token, scraper(("steamcommunity.com" to "other") to value, name = "other").scrape()?.token)
    }

    // ---- through SteamCredentialProvider, with the real refresher on the same gateway ----------------

    private val clock = FakeClock()

    private fun provider(gateway: FakeSteamWebSessionGateway) = SteamCredentialProvider(
        vault = FakeCredentialVault(steamCredential = null),
        scraper = CookieSteamSessionScraper(gateway),
        clock = clock,
        sessionRefresher = DefaultSteamSessionRefresher(gateway, clock),
    )

    private fun session(expiresIn: Duration): Pair<String, WebCookie> {
        val value = "$owner%7C%7C${steamAccessToken(owner, clock.now() + expiresIn, aud = listOf("web:community"))}"
        return value to WebCookie(value, null)
    }

    @Test
    fun an_expired_cookie_gives_no_credential_and_reads_as_logged_out() = runTest {
        val (_, cookie) = session(expiresIn = (-1).hours)
        val gateway = FakeSteamWebSessionGateway(mutableMapOf(("steamcommunity.com" to "steamLoginSecure") to cookie))
        val provider = provider(gateway)

        assertNull(provider.current())
        assertTrue(provider.lastRefreshFailedLoggedOut)
        assertTrue(gateway.postedForms.isEmpty(), "a session that is gone is not renewed")
    }

    @Test
    fun a_cookie_due_for_renewal_yields_the_renewed_token() = runTest {
        val (_, dueCookie) = session(expiresIn = 30.minutes)
        val (renewedValue, renewedCookie) = session(expiresIn = 24.hours)
        val gateway = FakeSteamWebSessionGateway(
            cookies = mutableMapOf(("steamcommunity.com" to "steamLoginSecure") to dueCookie),
            ajaxRefreshBody =
            """{"success":true,"login_url":"https://steamcommunity.com/login/settoken","steamID":"$owner","nonce":"n","auth":"a"}""",
            settokenSetsCommunityCookie = renewedCookie,
        )

        val credential = assertNotNull(provider(gateway).current())

        assertEquals(renewedValue.substringAfter("%7C%7C"), credential.token)
        assertEquals(clock.now() + 24.hours, credential.expiresAt)
    }
}
