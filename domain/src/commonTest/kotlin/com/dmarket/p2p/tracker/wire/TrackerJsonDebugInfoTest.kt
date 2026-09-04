package com.dmarket.p2p.tracker.wire

import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * kotlinx-serialization ships `exceptionsWithDebugInfo = true`, which appends `"\nJSON input: " + input`
 * to a decoding exception and — under 200 characters — echoes the input **verbatim**. Bodies this core
 * decodes sit at or under that threshold and carry `nonce`/`auth`/`tradeToken`, and the resulting messages
 * leave the core (lifecycle events, directive outcomes, the host's crash reporter). [trackerJson] forces
 * the flag off; these tests pin that.
 */
class TrackerJsonDebugInfoTest {
    /** Under the 200-char `minify` threshold, i.e. pre-fix this whole string was appended verbatim. */
    private val secretBody = """{"nonce":"NONCE-abc","auth":"AUTH-xyz","ttlSeconds":"not-a-number"}"""

    @Test
    fun a_decode_failure_does_not_echo_the_input() {
        val e = assertFailsWith<SerializationException> {
            TrackerJson.decodeFromString<HeartbeatResponseDto>(secretBody)
        }

        val rendered = e.message.orEmpty() + "\n" + e.stackTraceToString()
        assertFalse("JSON input:" in rendered, "the input echo must be gone: $rendered")
        assertFalse("NONCE-abc" in rendered, "secret leaked: $rendered")
        assertFalse("AUTH-xyz" in rendered, "secret leaked: $rendered")
        // Still diagnosable: the offending field is named.
        assertTrue("ttlSeconds" in rendered, "expected the field name for diagnosis, got: $rendered")
    }

    @Test
    fun the_factory_cannot_be_overridden_by_a_caller() {
        // trackerJson applies `configure` first and forces the flag afterwards, so this must be a no-op.
        val json = trackerJson {
            ignoreUnknownKeys = true
            @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
            exceptionsWithDebugInfo = true
        }

        val e = assertFailsWith<SerializationException> { json.decodeFromString<HeartbeatResponseDto>(secretBody) }

        assertFalse("JSON input:" in e.message.orEmpty())
        assertFalse("NONCE-abc" in e.message.orEmpty())
    }

    @Test
    fun a_body_over_the_200_char_threshold_leaks_no_window_either() {
        // Over the threshold, `minify` yields a ±30-char window around the offset rather than the whole
        // input — still a leak, so assert this case separately.
        val padded = """{"pad":"${"x".repeat(400)}","nonce":"NONCE-abc","ttlSeconds":"not-a-number"}"""

        val e = assertFailsWith<SerializationException> { TrackerJson.decodeFromString<HeartbeatResponseDto>(padded) }

        val rendered = e.message.orEmpty() + "\n" + e.stackTraceToString()
        assertFalse("JSON input:" in rendered)
        assertFalse("NONCE-abc" in rendered)
    }
}
