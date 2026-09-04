package com.dmarket.p2p.tracker.debug

import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The redaction default is a claim this repo makes in its README and its extension manifest, so it is
 * asserted here rather than left to review: a diagnostic probe must not print the Steam token unless
 * the caller went through the opt-in entry point.
 */
class SecretRedactionTest {

    private val now = Instant.parse("2026-01-01T00:00:00Z")

    /** Deliberately distinctive so a substring assertion cannot pass by accident. */
    private val secret = "steam-access-token-a1b2c3d4e5f6"

    private val credential = SteamCredential(
        token = secret,
        subjectSteamId = SteamId("76561198000000001"),
        expiresAt = Instant.parse("2026-01-01T01:00:00Z"),
    )

    @Test
    fun a_redacted_credential_prints_no_trace_of_the_token() {
        val json = buildJsonObject { putSteamCredential(credential, now, reveal = false) }
        assertFalse(secret in json.toString(), "the raw token must not appear anywhere in the output: $json")
        assertNull(json["token"], "the raw key must be absent, not null-valued")
    }

    @Test
    fun a_redacted_credential_still_answers_every_diagnostic_question() {
        val json = buildJsonObject { putSteamCredential(credential, now, reveal = false) }
        // Identity, freshness and length: enough to tell "wrong account", "expired" and "empty" apart.
        assertEquals("76561198000000001", json["steamId"]!!.jsonPrimitive.content)
        assertEquals(secret.length, json["tokenLength"]!!.jsonPrimitive.int)
        assertTrue(json["fresh"]!!.jsonPrimitive.boolean)
        assertEquals("2026-01-01T01:00:00Z", json["expiresAtIso"]!!.jsonPrimitive.content)
        // And an identity marker, so "is this the same token as before?" survives redaction.
        assertEquals(SecretRedaction.fingerprint(secret), json["tokenFingerprint"]!!.jsonPrimitive.content)
    }

    @Test
    fun revealing_is_opt_in_and_then_prints_the_token_verbatim() {
        val json = buildJsonObject { putSteamCredential(credential, now, reveal = true) }
        assertEquals(secret, json["token"]!!.jsonPrimitive.content)
        // The redacted keys stay, so both modes have the same shape plus one field.
        assertEquals(secret.length, json["tokenLength"]!!.jsonPrimitive.int)
    }

    @Test
    fun a_secret_keeps_its_key_set_when_it_is_absent() {
        val json = buildJsonObject { putSecret("cookie", null, reveal = false) }
        assertTrue("cookieLength" in json && "cookieFingerprint" in json)
        assertNull(json["cookieLength"]!!.jsonPrimitive.contentOrNull)
        assertNull(json["cookieFingerprint"]!!.jsonPrimitive.contentOrNull)
        assertNull(json["cookie"])
    }

    @Test
    fun the_fingerprint_is_stable_for_equal_secrets_and_differs_otherwise() {
        assertEquals(SecretRedaction.fingerprint(secret), SecretRedaction.fingerprint(secret))
        assertNotEquals(SecretRedaction.fingerprint(secret), SecretRedaction.fingerprint(secret + "x"))
        // A single flipped character must still change the marker. (FNV-1a diffuses a trailing byte
        // through one multiply, so neighbouring inputs can share most digits — inequality is the
        // property that holds, not avalanche.)
        assertNotEquals(SecretRedaction.fingerprint("token-a"), SecretRedaction.fingerprint("token-b"))
    }

    @Test
    fun the_fingerprint_is_a_fixed_width_hex_marker() {
        val fingerprint = SecretRedaction.fingerprint(secret)!!
        assertEquals(16, fingerprint.length)
        assertTrue(fingerprint.all { it in "0123456789abcdef" }, fingerprint)
        // Short and empty inputs must not produce a shorter marker (no leading-zero truncation).
        assertEquals(16, SecretRedaction.fingerprint("")!!.length)
        assertEquals(16, SecretRedaction.fingerprint("a")!!.length)
    }

    @Test
    fun a_missing_secret_has_no_fingerprint() {
        assertNull(SecretRedaction.fingerprint(null))
    }
}
