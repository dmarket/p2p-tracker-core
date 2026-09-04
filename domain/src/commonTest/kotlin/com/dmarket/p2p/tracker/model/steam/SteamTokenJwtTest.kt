package com.dmarket.p2p.tracker.model.steam

import com.dmarket.p2p.tracker.support.base64UrlEncode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * Tests for [SteamTokenJwt] JWT parser and [MalformedSteamTokenException].
 *
 * JWTs are hand-constructed: `header.payload.signature` where payload is the base64url
 * of a JSON object. The parser never verifies the signature — it trusts the source (the user's
 * own Steam session).
 *
 * Shared payload base64url values (computed from UTF-8 bytes):
 *   {"exp":1781697600,"sub":"76561198000000001"}
 *       → eyJleHAiOjE3ODE2OTc2MDAsInN1YiI6Ijc2NTYxMTk4MDAwMDAwMDAxIn0
 *   {"exp":1000000000}
 *       → eyJleHAiOjEwMDAwMDAwMDB9
 *   {"sub":"76561198000000001"}
 *       → eyJzdWIiOiI3NjU2MTE5ODAwMDAwMDAwMSJ9
 */
class SteamTokenJwtTest {

    // Shared JWT fragments
    private val header = "eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9"
    private val sig = "dGVzdA"

    // Payloads
    private val payloadExpSub = "eyJleHAiOjE3ODE2OTc2MDAsInN1YiI6Ijc2NTYxMTk4MDAwMDAwMDAxIn0"
    private val payloadExpOnly = "eyJleHAiOjEwMDAwMDAwMDB9"
    private val payloadSubOnly = "eyJzdWIiOiI3NjU2MTE5ODAwMDAwMDAwMSJ9"

    private fun jwt(payload: String) = "$header.$payload.$sig"

    // ---- parseExp: happy paths -----------------------------------------------------------------

    @Test
    fun parseExp_returns_correct_instant_for_valid_token() {
        val token = jwt(payloadExpSub)
        val exp = SteamTokenJwt.parseExp(token)
        assertEquals(Instant.fromEpochSeconds(1781697600L), exp)
    }

    @Test
    fun parseExp_ignores_extra_claims() {
        // extra "iss" claim in the payload should be silently ignored
        // {"exp":1000000000,"iss":"steam","aud":"mobile"} →
        val payload = base64UrlEncode("""{"exp":1000000000,"iss":"steam","aud":"mobile"}""")
        val exp = SteamTokenJwt.parseExp(jwt(payload))
        assertEquals(Instant.fromEpochSeconds(1_000_000_000L), exp)
    }

    @Test
    fun parseExp_parses_past_instant_without_throwing() {
        // An expired token still parses — expiry check is the caller's responsibility.
        val token = jwt(payloadExpOnly)
        val exp = SteamTokenJwt.parseExp(token)
        assertEquals(Instant.fromEpochSeconds(1_000_000_000L), exp)
    }

    // ---- parseExp: error cases -----------------------------------------------------------------

    @Test
    fun parseExp_throws_on_single_segment_token() {
        assertFailsWith<MalformedSteamTokenException> {
            SteamTokenJwt.parseExp("onlyone")
        }
    }

    @Test
    fun parseExp_throws_on_non_base64url_payload() {
        // Payload contains `!` which is not in the base64url alphabet
        assertFailsWith<MalformedSteamTokenException> {
            SteamTokenJwt.parseExp("$header.!!!invalid!!!.$sig")
        }
    }

    @Test
    fun parseExp_throws_when_exp_claim_is_absent() {
        val token = jwt(payloadSubOnly) // payload only has "sub", no "exp"
        assertFailsWith<MalformedSteamTokenException> {
            SteamTokenJwt.parseExp(token)
        }
    }

    @Test
    fun parseExp_throws_when_exp_is_not_a_number() {
        val payload = base64UrlEncode("""{"exp":"not-a-number","sub":"76561198000000001"}""")
        assertFailsWith<MalformedSteamTokenException> {
            SteamTokenJwt.parseExp(jwt(payload))
        }
    }

    @Test
    fun a_malformed_payload_is_never_quoted_in_the_exception() {
        // The decoded input here IS the token's own payload, and both a kotlinx decoder message and V8's
        // SyntaxError can quote it. Nothing about it may reach the message, the cause chain, or the stack
        // trace — the host's crash reporter ships `stackTraceToString()`, which walks causes.
        val payload = base64UrlEncode("""{"exp":123,"ip_subject":"203.0.113.44","x":"SENTINEL-CLAIM"} trailing""")

        val e = assertFailsWith<MalformedSteamTokenException> { SteamTokenJwt.parseExp(jwt(payload)) }

        assertNull(e.cause, "a retained cause is rendered by stackTraceToString()")
        val rendered = e.message.orEmpty() + "\n" + e.stackTraceToString()
        assertFalse("SENTINEL-CLAIM" in rendered, "payload leaked into: $rendered")
        assertFalse("203.0.113.44" in rendered, "payload leaked into: $rendered")
    }

    // ---- subjectOrNull -------------------------------------------------------------------------

    @Test
    fun subjectOrNull_returns_sub_from_valid_token() {
        assertEquals("76561198000000001", SteamTokenJwt.subjectOrNull(jwt(payloadExpSub)))
    }

    @Test
    fun subjectOrNull_returns_null_for_malformed_token() {
        assertNull(SteamTokenJwt.subjectOrNull("bad.!.jwt"))
    }

    @Test
    fun subjectOrNull_returns_null_when_sub_absent() {
        assertNull(SteamTokenJwt.subjectOrNull(jwt(payloadExpOnly)))
    }

    // ---- claimsOrNull --------------------------------------------------------------------------

    @Test
    fun claimsOrNull_decodes_all_diagnostic_claims() {
        val payload = base64UrlEncode(
            """{"iss":"steam","sub":"76561198000000001","aud":["web"],"exp":1781697600,"iat":1781611200}""",
        )
        val claims = SteamTokenJwt.claimsOrNull(jwt(payload))
        assertEquals("steam", claims?.iss)
        assertEquals("76561198000000001", claims?.sub)
        assertEquals(listOf("web"), claims?.aud)
        assertEquals(1781697600L, claims?.exp)
        assertEquals(1781611200L, claims?.iat)
    }

    @Test
    fun claimsOrNull_normalises_string_aud_to_single_element_list() {
        val payload = base64UrlEncode("""{"aud":"web","exp":1000000000}""")
        assertEquals(listOf("web"), SteamTokenJwt.claimsOrNull(jwt(payload))?.aud)
    }

    @Test
    fun claimsOrNull_returns_empty_aud_when_absent() {
        assertEquals(emptyList<String>(), SteamTokenJwt.claimsOrNull(jwt(payloadExpOnly))?.aud)
    }

    @Test
    fun claimsOrNull_returns_null_for_malformed_token() {
        assertNull(SteamTokenJwt.claimsOrNull("bad.!.jwt"))
        assertNull(SteamTokenJwt.claimsOrNull("onlyone"))
    }

    // ---- base64url decoder edge-cases ----------------------------------------------------------

    @Test
    fun decoder_tolerates_missing_padding() {
        // Payload for {"exp":1000000000} has no padding in base64url — verify it still parses
        val token = jwt(payloadExpOnly)
        assertEquals(Instant.fromEpochSeconds(1_000_000_000L), SteamTokenJwt.parseExp(token))
    }

    // ---- helpers -------------------------------------------------------------------------------

    /**
     * Minimal base64url encoder for constructing test JWTs inline.
     * Mirrors [SteamTokenJwt]'s decoder alphabet.
     */
}
