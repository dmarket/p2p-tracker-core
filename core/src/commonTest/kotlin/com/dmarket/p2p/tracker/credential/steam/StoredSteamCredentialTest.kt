package com.dmarket.p2p.tracker.credential.steam

import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.support.fakeSteamCredential
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class StoredSteamCredentialTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun round_trip_preserves_all_fields() {
        val credential = fakeSteamCredential(
            token = "ya29.test-token",
            steamId = "76561198000000001",
        )
        val stored = StoredSteamCredential.from(credential)
        val restored = stored.toDomain()

        assertEquals(credential.token, restored.token)
        assertEquals(credential.subjectSteamId, restored.subjectSteamId)
        assertEquals(credential.expiresAt, restored.expiresAt)
    }

    @Test
    fun serializes_to_snake_case_keys() {
        val credential = fakeSteamCredential(
            token = "tok",
            steamId = "76561198000000001",
        ).copy(expiresAt = Instant.fromEpochMilliseconds(1_781_697_600_000L))

        val stored = StoredSteamCredential.from(credential)
        val jsonStr = json.encodeToString(StoredSteamCredential.serializer(), stored)

        // On-disk shape must use snake_case keys so chrome.storage.local rows are stable.
        assertTrue(jsonStr.contains("\"steam_id\""), "Expected 'steam_id' key, got: $jsonStr")
        assertTrue(jsonStr.contains("\"expires_at_ms\""), "Expected 'expires_at_ms' key, got: $jsonStr")
        assertTrue(jsonStr.contains("\"token\""), "Expected 'token' key, got: $jsonStr")
    }

    @Test
    fun deserializes_from_json_correctly() {
        val raw = """{"token":"tok","steam_id":"76561198000000001","expires_at_ms":1781697600000}"""
        val stored = json.decodeFromString<StoredSteamCredential>(raw)

        assertEquals("tok", stored.token)
        assertEquals("76561198000000001", stored.steamId)
        assertEquals(1_781_697_600_000L, stored.expiresAtMs)
        assertEquals(SteamId("76561198000000001"), stored.toDomain().subjectSteamId)
        assertEquals(Instant.fromEpochMilliseconds(1_781_697_600_000L), stored.toDomain().expiresAt)
    }

    @Test
    fun device_vault_keys_constants_are_stable() {
        // Changing these constants is a breaking migration (stored chrome.storage.local keys).
        assertEquals("steam_credential", DeviceVaultKeys.STEAM_CREDENTIAL)
    }

    /**
     * The DTO must not print the Steam JWT — a persistence DTO ends up in exception messages and debug
     * lines more often than the credential itself. Serialization is the deliberate exception: the stored
     * JSON still carries the token, because persisting it is this type's whole job.
     */
    @Test
    fun toString_redacts_the_token_while_serialization_still_carries_it() {
        val stored = StoredSteamCredential.from(fakeSteamCredential(token = "SECRET-TOKEN-a1b2c3", steamId = "76561198000000001"))

        val printed = stored.toString()
        assertFalse("SECRET-TOKEN-a1b2c3" in printed, printed)
        assertTrue("redacted" in printed, printed)
        assertTrue("76561198000000001" in printed, "the non-secret steam id stays: $printed")

        assertTrue("SECRET-TOKEN-a1b2c3" in json.encodeToString(stored), "the persisted JSON must still hold the token")
    }
}
