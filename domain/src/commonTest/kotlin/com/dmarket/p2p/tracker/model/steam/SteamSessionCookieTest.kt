package com.dmarket.p2p.tracker.model.steam

import com.dmarket.p2p.tracker.model.SteamId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Tests for [SteamSessionCookie] — the pure parse/build/freshness helper for the `steamLoginSecure`
 * cookie. JWTs are hand-constructed the same way as in [SteamTokenJwtTest].
 *
 * Shared payload: {"exp":1781697600,"sub":"76561198000000001"} → exp = 1781697600.
 */
class SteamSessionCookieTest {

    private val header = "eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9"
    private val sig = "dGVzdA"
    private val payloadExpSub = "eyJleHAiOjE3ODE2OTc2MDAsInN1YiI6Ijc2NTYxMTk4MDAwMDAwMDAxIn0"
    private val token = "$header.$payloadExpSub.$sig"
    private val steamId = "76561198000000001"
    private val expiry = Instant.fromEpochSeconds(1781697600L)

    // ---- parse: happy paths --------------------------------------------------------------------

    @Test
    fun parse_accepts_raw_double_pipe_separator() {
        val parsed = SteamSessionCookie.parse("$steamId||$token")
        assertEquals(SteamId(steamId), parsed?.steamId)
        assertEquals(token, parsed?.accessToken)
        assertEquals(expiry, parsed?.expiresAt)
    }

    @Test
    fun parse_accepts_url_encoded_separator() {
        val parsed = SteamSessionCookie.parse("$steamId%7C%7C$token")
        assertEquals(SteamId(steamId), parsed?.steamId)
        assertEquals(token, parsed?.accessToken)
        assertEquals(expiry, parsed?.expiresAt)
    }

    @Test
    fun parse_accepts_lowercase_url_encoded_separator() {
        val parsed = SteamSessionCookie.parse("$steamId%7c%7c$token")
        assertEquals(SteamId(steamId), parsed?.steamId)
    }

    @Test
    fun cookieValue_round_trips_through_parse() {
        val original = SteamSessionCookie(SteamId(steamId), token, expiry)
        assertEquals("$steamId%7C%7C$token", original.cookieValue())
        assertEquals(original, SteamSessionCookie.parse(original.cookieValue()))
    }

    // ---- parse: malformed → null ---------------------------------------------------------------

    @Test
    fun parse_returns_null_when_separator_absent() {
        assertNull(SteamSessionCookie.parse("just-one-blob-no-separator"))
    }

    @Test
    fun parse_returns_null_when_steamid_part_empty() {
        assertNull(SteamSessionCookie.parse("||$token"))
    }

    @Test
    fun parse_returns_null_when_token_part_empty() {
        assertNull(SteamSessionCookie.parse("$steamId||"))
    }

    @Test
    fun parse_returns_null_when_token_is_not_a_jwt() {
        assertNull(SteamSessionCookie.parse("$steamId||not.a.validjwt!"))
    }

    // ---- isFresh: skew boundaries --------------------------------------------------------------

    @Test
    fun isFresh_true_with_ample_headroom() {
        val cookie = SteamSessionCookie(SteamId(steamId), token, expiry)
        assertTrue(cookie.isFresh(expiry - 3600.seconds))
    }

    @Test
    fun isFresh_false_after_expiry() {
        val cookie = SteamSessionCookie(SteamId(steamId), token, expiry)
        assertFalse(cookie.isFresh(expiry + 1.seconds))
    }

    @Test
    fun isFresh_false_exactly_at_skew_boundary() {
        val cookie = SteamSessionCookie(SteamId(steamId), token, expiry)
        // now == expiresAt - skew → strictly-less-than check is false.
        assertFalse(cookie.isFresh(expiry - SteamSessionCookie.DEFAULT_SKEW))
    }
}
