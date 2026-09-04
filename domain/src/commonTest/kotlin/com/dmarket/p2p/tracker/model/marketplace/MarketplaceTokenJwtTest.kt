package com.dmarket.p2p.tracker.model.marketplace

import com.dmarket.p2p.tracker.model.TokenFingerprint
import com.dmarket.p2p.tracker.support.base64UrlEncode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class MarketplaceTokenJwtTest {

    // {"exp":1781697600} — no padding needed, which is the normal base64url case.
    private val expPayload = "eyJleHAiOjE3ODE2OTc2MDB9"

    @Test
    fun reads_the_exp_claim() {
        val token = "eyJhbGciOiJub25lIn0.$expPayload.sig"
        assertEquals(Instant.fromEpochSeconds(1_781_697_600L), MarketplaceTokenJwt.expiresAtOrNull(token))
    }

    @Test
    fun accepts_a_quoted_numeric_exp() {
        // {"exp":"1781697600"} — the DMarket contract serialises int64 epochs as JSON strings elsewhere, so
        // tolerating it here removes a whole class of "token looks unreadable" incident.
        val payload = base64UrlEncode("""{"exp":"1781697600"}""")
        assertEquals(
            Instant.fromEpochSeconds(1_781_697_600L),
            MarketplaceTokenJwt.expiresAtOrNull("h.$payload.s"),
        )
    }

    @Test
    fun returns_null_for_a_token_without_exp() {
        val payload = base64UrlEncode("""{"sub":"account-1"}""")
        assertNull(MarketplaceTokenJwt.expiresAtOrNull("h.$payload.s"))
    }

    @Test
    fun returns_null_for_a_non_numeric_exp() {
        val payload = base64UrlEncode("""{"exp":"soon"}""")
        assertNull(MarketplaceTokenJwt.expiresAtOrNull("h.$payload.s"))
    }

    @Test
    fun returns_null_rather_than_throwing_for_junk() {
        // Total by design: every caller's answer to "unreadable" is the same as to "expired" — refresh it.
        assertNull(MarketplaceTokenJwt.expiresAtOrNull("not-a-jwt"))
        assertNull(MarketplaceTokenJwt.expiresAtOrNull(""))
        assertNull(MarketplaceTokenJwt.expiresAtOrNull("h.!!!not-base64!!!.s"))
        assertNull(MarketplaceTokenJwt.expiresAtOrNull("h.${base64UrlEncode("{not json")}.s"))
    }

    @Test
    fun tolerates_a_payload_that_needs_padding() {
        // {"exp":1000000000} is 18 chars → 24 base64 chars with one '=' of padding, which we omit.
        val payload = base64UrlEncode("""{"exp":1000000000}""")
        assertEquals(Instant.fromEpochSeconds(1_000_000_000L), MarketplaceTokenJwt.expiresAtOrNull("h.$payload.s"))
    }

    // ---- fingerprints --------------------------------------------------------------------------

    @Test
    fun fingerprints_are_stable_distinct_and_never_the_input() {
        val token = "durable-refresh-token-value-0123456789"
        val fp = TokenFingerprint.of(token)
        assertEquals(fp, TokenFingerprint.of(token), "same input → same fingerprint (it is a latch key)")
        assertNotEquals(fp, TokenFingerprint.of(token + "x"))
        assertEquals(16, fp?.length)
        assertTrue(fp!!.all { it in "0123456789abcdef" })
        assertTrue(token !in fp, "the stored value must not contain the credential")
    }

    @Test
    fun a_blank_token_has_no_fingerprint() {
        assertNull(TokenFingerprint.of(null))
        assertNull(TokenFingerprint.of(""))
        assertNull(TokenFingerprint.of("   "))
    }

    // ---- redaction -----------------------------------------------------------------------------

    @Test
    fun token_carriers_never_print_their_tokens() {
        val refreshExpiry = Instant.fromEpochSeconds(1_784_289_600L)
        val pair = MarketplaceTokenPair(
            accessToken = "access-SENTINEL",
            refreshToken = "refresh-SENTINEL",
            refreshTokenExpiresAt = refreshExpiry,
        )
        val rendered = pair.toString()
        assertTrue("SENTINEL" !in rendered, "leaked into: $rendered")
        assertTrue(refreshExpiry.toString() in rendered, "the expiry is the diagnostic value and must survive")

        val stored = StoredMarketplaceTokens("access-SENTINEL", "refresh-SENTINEL", null).toString()
        assertTrue("SENTINEL" !in stored, "leaked into: $stored")
        assertTrue("absent" in StoredMarketplaceTokens(null, null, null).toString())
    }
}
