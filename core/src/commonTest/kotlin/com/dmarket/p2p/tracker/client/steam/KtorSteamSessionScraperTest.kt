package com.dmarket.p2p.tracker.client.steam

import com.dmarket.p2p.tracker.client.createHttpClient
import com.dmarket.p2p.tracker.support.fixture
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * Tests for [KtorSteamSessionScraper] using Ktor's [MockEngine].
 *
 * HTML fixtures come from `steamcommunity_home.html` / `steamcommunity_logged_out.html`.
 *
 * JWT in `steamcommunity_home.html`:
 *   payload = {"exp":1781697600,"sub":"76561198000000001"}
 */
class KtorSteamSessionScraperTest {

    private val homeHtml by lazy { fixture("steamcommunity_home.html") }
    private val loggedOutHtml by lazy { fixture("steamcommunity_logged_out.html") }

    private fun scraper(body: String, status: HttpStatusCode = HttpStatusCode.OK): KtorSteamSessionScraper {
        val engine = MockEngine {
            respond(content = body, status = status, headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Html.toString()))
        }
        return KtorSteamSessionScraper(httpClient = createHttpClient(engine))
    }

    @Test
    fun scrape_returns_credential_from_home_page() = runTest {
        val cred = scraper(homeHtml).scrape()
        assertEquals(
            "eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9" +
                ".eyJleHAiOjE3ODE2OTc2MDAsInN1YiI6Ijc2NTYxMTk4MDAwMDAwMDAxIn0" +
                ".dGVzdA",
            cred?.token,
        )
        assertEquals("76561198000000001", cred?.subjectSteamId?.value)
    }

    @Test
    fun scrape_sets_expiresAt_from_jwt_exp() = runTest {
        val cred = scraper(homeHtml).scrape()
        assertEquals(Instant.fromEpochSeconds(1_781_697_600L), cred?.expiresAt)
    }

    @Test
    fun scrape_returns_null_from_logged_out_page() = runTest {
        assertNull(scraper(loggedOutHtml).scrape())
    }

    @Test
    fun scrape_returns_null_when_sub_and_steamid_disagree() = runTest {
        val mismatchHtml = homeHtml.replace(
            """var g_steamID = "76561198000000001";""",
            """var g_steamID = "76561199999999999";""",
        )
        assertNull(scraper(mismatchHtml).scrape(), "Expected null when JWT sub disagrees with g_steamID")
    }

    @Test
    fun scrape_returns_null_for_malformed_jwt() = runTest {
        val badHtml = homeHtml.replace(
            Regex("""data-loyalty_webapi_token="&quot;([^&]+)&quot;""""),
            """data-loyalty_webapi_token="&quot;bad.!!!!.token&quot;"""",
        )
        assertNull(scraper(badHtml).scrape(), "Expected null when JWT payload is not valid base64url")
    }

    @Test
    fun scrape_returns_null_on_non_ok_status_without_throwing() = runTest {
        // A non-OK status is "logged out" (e.g. a redirect landing), not a transport failure — the scraper
        // catches the transport's HttpStatusException and returns null instead of throwing. (It never
        // "opted out of expectSuccess", as an older comment here claimed.)
        assertNull(scraper(homeHtml, status = HttpStatusCode.InternalServerError).scrape())
    }
}
