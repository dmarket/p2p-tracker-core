package com.dmarket.p2p.tracker.wire

import com.dmarket.p2p.tracker.net.NetworkRedaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class MarketplaceTokenDtosTest {

    @Test
    fun decodes_the_pascal_case_wire_shape_with_string_epochs() {
        // The exact body three independent producers of this call agree on (the web frontend, the swagger
        // definition, and the Android client) — PascalCase keys, expiries as epoch-second STRINGS.
        val json = """
            {"AuthToken":"access-1","AuthTokenExpiresAt":"1781697600",
             "RefreshToken":"refresh-1","RefreshTokenExpiresAt":"1784289600"}
        """.trimIndent()

        val pair = TrackerJson.decodeFromString<RefreshTokenResponseDto>(json).toPairOrNull()

        assertNotNull(pair)
        assertEquals("access-1", pair.accessToken)
        assertEquals("refresh-1", pair.refreshToken)
        assertEquals(Instant.fromEpochSeconds(1_784_289_600L), pair.refreshTokenExpiresAt)
    }

    @Test
    fun a_200_carrying_an_error_code_is_not_a_pair() {
        // These APIs answer some failures with a Code in the body rather than a status. Reading that as a
        // successful no-op would leave the caller believing it had rotated when it had not.
        val json = """{"Code":"InvalidToken","Message":"refresh token expired"}"""
        assertNull(TrackerJson.decodeFromString<RefreshTokenResponseDto>(json).toPairOrNull())
    }

    @Test
    fun a_body_missing_either_half_is_not_a_pair() {
        assertNull(TrackerJson.decodeFromString<RefreshTokenResponseDto>("""{"AuthToken":"a"}""").toPairOrNull())
        assertNull(TrackerJson.decodeFromString<RefreshTokenResponseDto>("""{"RefreshToken":"r"}""").toPairOrNull())
        assertNull(
            TrackerJson.decodeFromString<RefreshTokenResponseDto>(
                """{"AuthToken":"","RefreshToken":"r"}""",
            ).toPairOrNull(),
        )
    }

    @Test
    fun unparseable_or_absent_expiries_decode_to_null_rather_than_failing() {
        val json = """{"AuthToken":"a","AuthTokenExpiresAt":"soon","RefreshToken":"r"}"""
        val pair = TrackerJson.decodeFromString<RefreshTokenResponseDto>(json).toPairOrNull()
        assertNotNull(pair)
        assertNull(pair.refreshTokenExpiresAt)
    }

    @Test
    fun the_request_encodes_exactly_one_pascal_case_field() {
        assertEquals("""{"RefreshToken":"r-1"}""", TrackerJson.encodeToString(RefreshTokenRequestDto("r-1")))
    }

    @Test
    fun neither_dto_prints_a_token() {
        val request = RefreshTokenRequestDto("refresh-SENTINEL").toString()
        assertTrue("SENTINEL" !in request, "leaked into: $request")

        val response = RefreshTokenResponseDto(
            authToken = "access-SENTINEL",
            refreshToken = "refresh-SENTINEL",
            code = "SomeCode",
            message = "server text",
        ).toString()
        assertTrue("SENTINEL" !in response, "leaked into: $response")
        assertTrue("SomeCode" in response, "the error code is the diagnostic and must survive")
        assertTrue("server text" !in response, "a server-supplied string is not interpolated into logs")
    }

    @Test
    fun the_redactor_scrubs_the_refresh_exchange_by_name() {
        // The access half is JWT-shaped and would also be caught by the shape rule; the refresh half is
        // opaque, so only its key name can save it.
        val body = """{"AuthToken":"eyJhbGciOiJub25lIn0.eyJleHAiOjF9.s","RefreshToken":"OPAQUE-30-DAY-SECRET"}"""
        val redacted = NetworkRedaction.redactBody(body).orEmpty()
        assertTrue("OPAQUE-30-DAY-SECRET" !in redacted, "leaked into: $redacted")
        assertTrue("eyJhbGciOiJub25lIn0" !in redacted, "leaked into: $redacted")

        val request = NetworkRedaction.redactBody("""{"RefreshToken":"OPAQUE-30-DAY-SECRET"}""").orEmpty()
        assertTrue("OPAQUE-30-DAY-SECRET" !in request, "leaked into: $request")
    }
}
