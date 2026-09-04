package com.dmarket.p2p.tracker.credential.steam

import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.steam.SteamSessionCookie
import com.dmarket.p2p.tracker.port.SessionRefreshOutcome
import com.dmarket.p2p.tracker.port.WebCookie
import com.dmarket.p2p.tracker.port.steam.SteamWebSessionState
import com.dmarket.p2p.tracker.support.FakeClock
import com.dmarket.p2p.tracker.support.FakeSteamWebSessionGateway
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultSteamSessionRefresherTest {

    // FakeClock default now = 1_781_611_200_000 ms → 1_781_611_200 s.
    private val nowSeconds = 1_781_611_200L
    private val clock = FakeClock()

    // A valid steamLoginSecure value (steamid %7C%7C <jwt>) so the orchestration can parse the steam id.
    private val header = "eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9"
    private val sig = "dGVzdA"

    // Embedded access-token exp = now + 86_400s (24h fresh) — {"exp":1781697600,"sub":"765...01"}.
    private val payloadExpSub = "eyJleHAiOjE3ODE2OTc2MDAsInN1YiI6Ijc2NTYxMTk4MDAwMDAwMDAxIn0"
    private val sessionCookieValue = "76561198000000001%7C%7C$header.$payloadExpSub.$sig"

    // Embedded access-token exp = now + 600s (< 1h headroom → needs re-mint) —
    // {"exp":1781611800,"sub":"765...01"}. The self-gate keys off THIS, not the cookie's browser expiry.
    private val nearExpiryPayload = "eyJleHAiOjE3ODE2MTE4MDAsInN1YiI6Ijc2NTYxMTk4MDAwMDAwMDAxIn0"
    private val nearExpiryCookieValue = "76561198000000001%7C%7C$header.$nearExpiryPayload.$sig"

    // A real `POST /jwt/ajaxrefresh` response, captured from live Steam: ONE domain's transfer, named by
    // `login_url`. This is the shape the endpoint actually answers with.
    private val flatAjaxBody = """
        {
          "success": true,
          "login_url": "https://steamcommunity.com/login/settoken",
          "steamID": "76561198000000001",
          "nonce": "094f6ef03aabfd530b66e7ecf5bc5f72",
          "redir": "",
          "auth": "4bb08eb2b0cdc284a20b651ebc98a877"
        }
    """.trimIndent()

    /** The ajaxrefresh POSTs the refresher made (one per Steam web domain). */
    private fun FakeSteamWebSessionGateway.refreshPosts() = postedForms.filter { it.first.endsWith("/jwt/ajaxrefresh") }

    /** The settoken POSTs the refresher made. */
    private fun FakeSteamWebSessionGateway.setTokenPosts() = postedForms.filter { it.first.contains("/login/settoken") }

    // A valid transfer_info ajaxrefresh response with a per-domain settoken url each.
    private val transferAjaxBody = """
        {
          "steamid": "76561198000000001",
          "transfer_info": [
            { "url": "https://steamcommunity.com/login/settoken", "params": { "nonce": "n1", "auth": "a1" } },
            { "url": "https://store.steampowered.com/login/settoken", "params": { "nonce": "n2", "auth": "a2" } }
          ]
        }
    """.trimIndent()

    private fun community(name: String, value: String, exp: Long) = ("steamcommunity.com" to name) to WebCookie(value, exp)
    private fun store(name: String, value: String, exp: Long) = ("store.steampowered.com" to name) to WebCookie(value, exp)

    @Test
    fun fresh_cookie_skips_network_and_writes_nothing() = runTest {
        val gw = FakeSteamWebSessionGateway(
            cookies = mutableMapOf(
                community("steamLoginSecure", sessionCookieValue, nowSeconds + 100_000),
                store("steamLoginSecure", sessionCookieValue, nowSeconds + 50_000),
            ),
        )
        val refresher = DefaultSteamSessionRefresher(gw, clock)

        val outcome = refresher.refreshSession()

        assertEquals(SessionRefreshOutcome.NOT_NEEDED, outcome)
        assertTrue(gw.getUrls.isEmpty(), "fresh cookie must not hit ajaxrefresh")
        assertTrue(gw.postedForms.isEmpty())
        // Contamination regression: the web path never writes a Steam cookie, not even on the gate.
        assertTrue(gw.cookieWrites.isEmpty(), "NOT_NEEDED must not write any cookie")
    }

    @Test
    fun near_expiry_cookie_runs_ajaxrefresh_then_settoken_and_reports_REFRESHED() = runTest {
        val gw = FakeSteamWebSessionGateway(
            cookies = mutableMapOf(
                // Browser expiry stays far-future on purpose — only the embedded token is near expiry,
                // proving the self-gate keys off the token's exp, not the cookie's browser expiry.
                community("steamLoginSecure", nearExpiryCookieValue, nowSeconds + 100_000),
                store("steamLoginSecure", nearExpiryCookieValue, nowSeconds + 100_000),
            ),
            ajaxRefreshBody = flatAjaxBody,
            // Steam's settoken Set-Cookie re-mints the community cookie with a fresh (24h) token.
            settokenSetsCommunityCookie = WebCookie(sessionCookieValue, nowSeconds + 100_000),
        )
        val refresher = DefaultSteamSessionRefresher(gw, clock)

        val outcome = refresher.refreshSession()

        assertEquals(SessionRefreshOutcome.REFRESHED, outcome)
        // The handshake is a POST with `redir` as a form field — Steam answers the GET-with-query form of
        // the same request with InvalidParam — and it runs once per Steam web domain, because the response
        // covers only the domain it asked for and Steam's session cookies are audience-scoped per domain.
        assertTrue(gw.getUrls.isEmpty(), "the refresh handshake must not be a GET")
        assertEquals(
            listOf("https://steamcommunity.com", "https://store.steampowered.com"),
            gw.refreshPosts().map { it.second["redir"] },
        )
        // settoken goes to the endpoint Steam named, and carries the whole response plus `prior` (the token
        // currently in the cookie) — the shape Steam's own page sends.
        val setToken = gw.setTokenPosts()
        assertEquals(2, setToken.size)
        assertTrue(setToken.all { it.first == "https://steamcommunity.com/login/settoken" })
        val form = setToken.first().second
        assertEquals("094f6ef03aabfd530b66e7ecf5bc5f72", form["nonce"])
        assertEquals("4bb08eb2b0cdc284a20b651ebc98a877", form["auth"])
        assertEquals("true", form["success"], "the whole refresh response is echoed back")
        // steamID is taken from the existing cookie (precision-safe), not re-derived from the response.
        assertEquals("76561198000000001", form["steamID"])
        assertEquals(SteamSessionCookie.parse(nearExpiryCookieValue)?.accessToken, form["prior"])
        // Contamination regression: cookies are set ONLY by Steam's per-domain settoken Set-Cookie,
        // never copied across domains by us.
        assertTrue(gw.cookieWrites.isEmpty(), "the web path must never write a Steam cookie")

        // The orchestration only ever hands the gateway Steam's own refresh endpoints.
        gw.postedForms.forEach {
            assertTrue(it.first.contains("/jwt/ajaxrefresh") || it.first.contains("/login/settoken"), "unexpected endpoint: ${it.first}")
        }
    }

    @Test
    fun a_rejected_settoken_reports_FAILED_even_if_a_cookie_appears() = runTest {
        // Steam answers a rejected transfer with a non-OK EResult. Reading that is the only way to tell a
        // session that came back from one that merely looks like it did.
        val gw = FakeSteamWebSessionGateway(
            cookies = mutableMapOf(community("steamLoginSecure", nearExpiryCookieValue, nowSeconds + 100_000)),
            ajaxRefreshBody = flatAjaxBody,
            setTokenBody = """{"result":8}""",
            settokenSetsCommunityCookie = WebCookie(sessionCookieValue, nowSeconds + 100_000),
        )

        assertEquals(SessionRefreshOutcome.FAILED, DefaultSteamSessionRefresher(gw, clock).refreshSession())
    }

    @Test
    fun settoken_url_on_a_non_steam_host_is_dropped_not_posted() = runTest {
        // Defense-in-depth: the per-domain settoken URL is read from the (network-fetched) ajaxrefresh
        // body, so a poisoned/MITM'd response must not be able to redirect the session-transfer secrets
        // ({steamID,nonce,auth,sessionid}) off a Steam host. Non-Steam hosts are dropped, not POSTed to.
        val poisonedBody = """
            {
              "steamid": "76561198000000001",
              "transfer_info": [
                { "url": "https://steamcommunity.com/login/settoken", "params": { "nonce": "n1", "auth": "a1" } },
                { "url": "https://evil.attacker.example/login/settoken", "params": { "nonce": "n2", "auth": "a2" } }
              ]
            }
        """.trimIndent()
        val gw = FakeSteamWebSessionGateway(
            cookies = mutableMapOf(community("steamLoginSecure", nearExpiryCookieValue, nowSeconds + 100_000)),
            ajaxRefreshBody = poisonedBody,
            settokenSetsCommunityCookie = WebCookie(sessionCookieValue, nowSeconds + 100_000),
        )
        val refresher = DefaultSteamSessionRefresher(gw, clock)

        refresher.refreshSession()

        assertTrue(gw.setTokenPosts().isNotEmpty(), "the Steam-host settoken is still POSTed")
        assertTrue(gw.setTokenPosts().all { it.first.contains("steamcommunity.com") })
        assertTrue(gw.postedForms.none { it.first.contains("attacker") }, "session secrets must never be POSTed off Steam")
    }

    @Test
    fun settoken_that_does_not_advance_exp_reports_FAILED() = runTest {
        // Simulates a silently-rejected settoken (e.g. an anti-CSRF Origin 403): the ajaxrefresh parses
        // fine and settoken is POSTed, but the community cookie is never re-minted — so its embedded
        // token still doesn't clear the gate. We must report FAILED, not a no-op "REFRESHED".
        val gw = FakeSteamWebSessionGateway(
            cookies = mutableMapOf(
                community("steamLoginSecure", nearExpiryCookieValue, nowSeconds + 100_000),
                store("steamLoginSecure", nearExpiryCookieValue, nowSeconds + 100_000),
            ),
            ajaxRefreshBody = transferAjaxBody,
            settokenSetsCommunityCookie = null, // settoken rejected → cookie unchanged
        )
        val refresher = DefaultSteamSessionRefresher(gw, clock)

        val outcome = refresher.refreshSession()

        assertEquals(SessionRefreshOutcome.FAILED, outcome)
        assertTrue(gw.setTokenPosts().isNotEmpty(), "settoken is still attempted")
        assertTrue(gw.cookieWrites.isEmpty())
    }

    @Test
    fun stale_token_with_future_browser_expiry_does_not_self_gate() = runTest {
        // Regression: a logged-out session can leave steamLoginSecure in the jar with a far-future
        // browser expiry but a long-expired embedded token. The self-gate must NOT report NOT_NEEDED
        // off the browser expiry — it must attempt the re-mint (and here report NOT_LOGGED_IN), so it
        // stays consistent with the page scrape rather than masking a dead session.
        val expiredPayload = "eyJleHAiOjE3ODE2MDc2MDAsInN1YiI6Ijc2NTYxMTk4MDAwMDAwMDAxIn0" // exp = now - 3600
        val staleCookieValue = "76561198000000001%7C%7C$header.$expiredPayload.$sig"
        val gw = FakeSteamWebSessionGateway(
            cookies = mutableMapOf(
                community("steamLoginSecure", staleCookieValue, nowSeconds + 30_000_000), // browser expiry ~1y out
                store("steamLoginSecure", staleCookieValue, nowSeconds + 30_000_000),
            ),
            ajaxRefreshBody = "<html>please log in</html>", // logged out
        )
        val refresher = DefaultSteamSessionRefresher(gw, clock)

        val outcome = refresher.refreshSession()

        assertEquals(SessionRefreshOutcome.NOT_LOGGED_IN, outcome)
        assertEquals(2, gw.refreshPosts().size, "must attempt ajaxrefresh instead of self-gating on the browser expiry")
        assertTrue(gw.cookieWrites.isEmpty())
    }

    @Test
    fun logged_out_when_ajaxrefresh_is_unusable() = runTest {
        val gw = FakeSteamWebSessionGateway(ajaxRefreshBody = "<html>please log in</html>")
        val refresher = DefaultSteamSessionRefresher(gw, clock)

        val outcome = refresher.refreshSession()

        assertEquals(SessionRefreshOutcome.NOT_LOGGED_IN, outcome)
        assertEquals(2, gw.refreshPosts().size, "ajaxrefresh is attempted, once per domain")
        assertTrue(gw.setTokenPosts().isEmpty(), "no settoken when not logged in")
    }

    @Test
    fun transient_ajaxrefresh_reports_FAILED_not_logged_out() = runTest {
        // A non-OK ajaxrefresh (5xx/429/403) throws TransientSessionException; it is a transient blip,
        // NOT a logged-out session, so it maps to FAILED (retry later) — never NOT_LOGGED_IN.
        val gw = FakeSteamWebSessionGateway(postThrowsTransient = true)
        val refresher = DefaultSteamSessionRefresher(gw, clock)

        val outcome = refresher.refreshSession()

        assertEquals(SessionRefreshOutcome.FAILED, outcome)
        assertTrue(gw.setTokenPosts().isEmpty())
    }

    @Test
    fun redir_is_sent_as_a_form_field_not_a_query_string() = runTest {
        // The regression this pins: sending `redir` as a GET query string is answered
        // `{"success":false,"error":8}` (InvalidParam). Steam's own login page POSTs it as a form field.
        val gw = FakeSteamWebSessionGateway(ajaxRefreshBody = "<html>please log in</html>")

        DefaultSteamSessionRefresher(gw, clock).refreshSession()

        assertTrue(gw.getUrls.isEmpty(), "no GET may be used for the handshake")
        gw.refreshPosts().forEach { (url, form) ->
            assertFalse(url.contains("?"), "redir must not travel in the query string, was: $url")
            assertTrue(form["redir"]?.startsWith("https://") == true, "redir must be a form field")
        }
    }

    @Test
    fun gateway_failure_is_swallowed_as_FAILED() = runTest {
        val gw = FakeSteamWebSessionGateway(failWith = RuntimeException("network down"))
        val refresher = DefaultSteamSessionRefresher(gw, clock)

        assertEquals(SessionRefreshOutcome.FAILED, refresher.refreshSession())
    }

    // ---- sessionState(): the zero-network classification the keep-alive is scheduled from ----------

    /** Plenty of life left → nothing to do, and above all no network. */
    @Test
    fun sessionState_reports_alive_for_a_session_well_inside_its_life() = runTest {
        val gw = FakeSteamWebSessionGateway(
            cookies = mutableMapOf(community("steamLoginSecure", sessionCookieValue, nowSeconds + 100_000)),
        )

        assertEquals(SteamWebSessionState.ALIVE, DefaultSteamSessionRefresher(gw, clock).sessionState())
        assertTrue(gw.getUrls.isEmpty(), "classification must never hit the network")
        assertTrue(gw.postedForms.isEmpty())
    }

    /**
     * Inside the headroom → this is the window the session must be renewed in. Judged from the EMBEDDED
     * token, not the cookie's browser expiry: here that expiry is months out while the token has 10 min.
     */
    @Test
    fun sessionState_reports_needs_refresh_inside_the_headroom_window() = runTest {
        val gw = FakeSteamWebSessionGateway(
            cookies = mutableMapOf(community("steamLoginSecure", nearExpiryCookieValue, nowSeconds + 5_000_000)),
        )

        assertEquals(SteamWebSessionState.NEEDS_REFRESH, DefaultSteamSessionRefresher(gw, clock).sessionState())
        assertTrue(gw.getUrls.isEmpty())
    }

    // ---- sessionState(expectedSteamId): whose session is this? -------------------------------------
    // The cookie's steamid is the only zero-network evidence of identity, and after a re-login as another
    // account it is the ONLY thing that changed — expiry-wise the fresh cookie looks perfect.

    private val cookieOwner = SteamId("76561198000000001")
    private val someoneElse = SteamId("76561198000000099")

    /** The table: (cookie, expected id) → verdict. */
    @Test
    fun sessionState_judges_identity_alongside_liveness() = runTest {
        // {"exp":1781611100,...} — 100s BEFORE now, i.e. already expired.
        val expiredValue = "76561198000000001%7C%7C$header.eyJleHAiOjE3ODE2MTExMDAsInN1YiI6Ijc2NTYxMTk4MDAwMDAwMDAxIn0.$sig"
        val cases = listOf(
            // Same account → the pre-existing liveness verdicts are untouched.
            Triple(sessionCookieValue, cookieOwner, SteamWebSessionState.ALIVE),
            Triple(nearExpiryCookieValue, cookieOwner, SteamWebSessionState.NEEDS_REFRESH),
            Triple(expiredValue, cookieOwner, SteamWebSessionState.GONE),
            // Another account → the switch is reported whichever side of the renewal window we are on:
            // renewing somebody else's session buys the caller nothing.
            Triple(sessionCookieValue, someoneElse, SteamWebSessionState.OTHER_ACCOUNT),
            Triple(nearExpiryCookieValue, someoneElse, SteamWebSessionState.OTHER_ACCOUNT),
            // …except when there is no session left at all: it belongs to nobody, and GONE is the state
            // whose remedy (sign in) actually applies.
            Triple(expiredValue, someoneElse, SteamWebSessionState.GONE),
            // No expected id → pure liveness, exactly as before. Back-compat for the re-scrape/mint paths.
            Triple(sessionCookieValue, null, SteamWebSessionState.ALIVE),
            Triple(nearExpiryCookieValue, null, SteamWebSessionState.NEEDS_REFRESH),
        )
        for ((cookieValue, expected, verdict) in cases) {
            val gw = FakeSteamWebSessionGateway(
                cookies = mutableMapOf(community("steamLoginSecure", cookieValue, nowSeconds + 5_000_000)),
            )
            assertEquals(verdict, DefaultSteamSessionRefresher(gw, clock).sessionState(expected), "expected=$expected")
            assertTrue(gw.getUrls.isEmpty() && gw.postedForms.isEmpty(), "identity must stay zero-network")
        }
    }

    /** Identity unknown is never a switch: an unreadable value keeps its existing re-mint verdict. */
    @Test
    fun sessionState_never_claims_a_switch_on_an_unparseable_cookie() = runTest {
        val gw = FakeSteamWebSessionGateway(
            cookies = mutableMapOf(community("steamLoginSecure", "not-a-cookie-value", nowSeconds + 5_000_000)),
        )

        assertEquals(SteamWebSessionState.NEEDS_REFRESH, DefaultSteamSessionRefresher(gw, clock).sessionState(someoneElse))
    }

    /**
     * The two halves of the cookie fail independently: an account switch must still be reported when the
     * steamid in front of the separator is legible but the token half is not (a Steam JWT shape change, a
     * truncated value). Judging liveness first and bailing to NEEDS_REFRESH answered "your cached credential
     * is fine" — the one fail-open path by which a wrong-account credential could stay cached for its whole
     * ~24h life, and the caller acts on that identity.
     */
    @Test
    fun sessionState_reports_a_switch_when_the_steamid_is_legible_but_the_token_is_not() = runTest {
        val gw = FakeSteamWebSessionGateway(
            cookies = mutableMapOf(community("steamLoginSecure", "76561198000000001%7C%7Cnot-a-jwt", nowSeconds + 5_000_000)),
        )
        val refresher = DefaultSteamSessionRefresher(gw, clock)

        assertEquals(SteamWebSessionState.OTHER_ACCOUNT, refresher.sessionState(someoneElse), "another account")
        assertEquals(SteamWebSessionState.NEEDS_REFRESH, refresher.sessionState(cookieOwner), "our own account, unreadable token")
        assertEquals(SteamWebSessionState.NEEDS_REFRESH, refresher.sessionState(), "no expected id → pure liveness")
        assertTrue(gw.getUrls.isEmpty() && gw.postedForms.isEmpty(), "identity must stay zero-network")
    }

    /** A non-numeric prefix is not a steamid, so it is still "identity unknown" rather than a switch. */
    @Test
    fun sessionState_never_claims_a_switch_on_a_non_numeric_steamid_prefix() = runTest {
        val gw = FakeSteamWebSessionGateway(
            cookies = mutableMapOf(community("steamLoginSecure", "garbage%7C%7Cnot-a-jwt", nowSeconds + 5_000_000)),
        )

        assertEquals(SteamWebSessionState.NEEDS_REFRESH, DefaultSteamSessionRefresher(gw, clock).sessionState(someoneElse))
    }

    /** Fail-open survives the new branch: a cookie-store hiccup must never manufacture a switch verdict. */
    @Test
    fun sessionState_fails_open_to_alive_when_the_cookie_store_throws() = runTest {
        val gw = FakeSteamWebSessionGateway(failWith = IllegalStateException("cookie store unavailable"))

        assertEquals(SteamWebSessionState.ALIVE, DefaultSteamSessionRefresher(gw, clock).sessionState(someoneElse))
    }

    /** No cookie at all → gone. Steam's renew endpoint refreshes a live session; there is none. */
    @Test
    fun sessionState_reports_gone_without_a_session_cookie() = runTest {
        val gw = FakeSteamWebSessionGateway(cookies = mutableMapOf())

        assertEquals(SteamWebSessionState.GONE, DefaultSteamSessionRefresher(gw, clock).sessionState())
        assertTrue(gw.getUrls.isEmpty())
    }

    /** An embedded token that has already expired is equally gone — nothing left to renew. */
    @Test
    fun sessionState_reports_gone_for_an_already_expired_session() = runTest {
        // {"exp":1781611100,"sub":"765...01"} — 100s BEFORE the fake clock's now.
        val expiredPayload = "eyJleHAiOjE3ODE2MTExMDAsInN1YiI6Ijc2NTYxMTk4MDAwMDAwMDAxIn0"
        val gw = FakeSteamWebSessionGateway(
            cookies = mutableMapOf(
                community("steamLoginSecure", "76561198000000001%7C%7C$header.$expiredPayload.$sig", nowSeconds + 5_000_000),
            ),
        )

        assertEquals(SteamWebSessionState.GONE, DefaultSteamSessionRefresher(gw, clock).sessionState())
        assertTrue(gw.getUrls.isEmpty(), "an expired session must not be chased over the network")
    }

    /** An unreadable value falls through to a re-mint attempt, exactly as the self-gate does. */
    @Test
    fun sessionState_reports_needs_refresh_for_an_unparseable_cookie() = runTest {
        val gw = FakeSteamWebSessionGateway(
            cookies = mutableMapOf(community("steamLoginSecure", "not-a-steam-cookie", nowSeconds + 100_000)),
        )

        assertEquals(SteamWebSessionState.NEEDS_REFRESH, DefaultSteamSessionRefresher(gw, clock).sessionState())
    }

    // ---- settoken host allowlist ---------------------------------------------------------------

    /**
     * A `login_url` off Steam — a poisoned or MITM'd refresh body — must never receive the transfer
     * secrets ({steamID, nonce, auth, sessionid}). The re-mint still completes against the domain we
     * asked, which is why the fallback exists: refusing outright would turn any Steam-side host change
     * into a spurious re-login prompt.
     *
     * Characterisation, not a regression guard: this already held before the allowlist covered the
     * fallback, because the default `redir` is itself a Steam domain. The guard for the change is
     * [a_non_steam_redir_sends_nothing_when_steam_names_no_login_url]; this pins the behaviour that
     * must NOT change while fixing it.
     */
    @Test
    fun a_login_url_off_steam_never_receives_the_transfer_secrets() = runTest {
        val poisoned = flatAjaxBody.replace("https://steamcommunity.com/login/settoken", "https://evil.example.com/login/settoken")
        val gw = FakeSteamWebSessionGateway(
            cookies = mutableMapOf(community("steamLoginSecure", nearExpiryCookieValue, nowSeconds + 100_000)),
            ajaxRefreshBody = poisoned,
            settokenSetsCommunityCookie = WebCookie(sessionCookieValue, nowSeconds + 100_000),
        )

        val outcome = DefaultSteamSessionRefresher(gw, clock).refreshSession()

        assertFalse(gw.postedForms.any { it.first.contains("evil.example.com") }, "the named host must not be posted to")
        // Fell back to the domain the handshake asked for, so the session still came back.
        assertEquals(SessionRefreshOutcome.REFRESHED, outcome)
        assertEquals(
            listOf("https://steamcommunity.com/login/settoken", "https://store.steampowered.com/login/settoken"),
            gw.setTokenPosts().map { it.first },
        )
        assertTrue(gw.cookieWrites.isEmpty(), "the web path must never write a Steam cookie")
    }

    /**
     * The fallback target is built from the CONFIGURED redir, so it is allow-listed too: a refresher
     * constructed with a non-Steam base (a bad remote config, or a direct construction that bypasses
     * `SteamEndpointsConfig`'s guard) must send the secrets nowhere at all.
     */
    @Test
    fun a_non_steam_redir_sends_nothing_when_steam_names_no_login_url() = runTest {
        val noLoginUrl = flatAjaxBody.replace("\"login_url\": \"https://steamcommunity.com/login/settoken\",\n  ", "")
        val gw = FakeSteamWebSessionGateway(
            cookies = mutableMapOf(community("steamLoginSecure", nearExpiryCookieValue, nowSeconds + 100_000)),
            ajaxRefreshBody = noLoginUrl,
        )

        val outcome = DefaultSteamSessionRefresher(
            gw,
            clock,
            communityBaseUrl = "https://evil.example.com",
            storeBaseUrl = "https://evil.example.com",
        ).refreshSession()

        assertTrue(gw.setTokenPosts().isEmpty(), "a non-Steam redir must never receive the transfer secrets")
        // No usable transfer target is indistinguishable from "Steam gave us nothing", which the caller
        // already handles by prompting a re-login.
        assertEquals(SessionRefreshOutcome.NOT_LOGGED_IN, outcome)
        assertTrue(gw.cookieWrites.isEmpty())
    }
}
