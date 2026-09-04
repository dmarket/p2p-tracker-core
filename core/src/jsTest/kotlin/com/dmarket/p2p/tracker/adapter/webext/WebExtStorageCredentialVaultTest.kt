package com.dmarket.p2p.tracker.adapter.webext

import com.dmarket.p2p.tracker.credential.steam.DeviceVaultKeys
import com.dmarket.p2p.tracker.support.fakeSteamCredential
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for [WebExtStorageCredentialVault] running against an in-memory `chrome.storage.local`
 * shim installed on `globalThis`.
 *
 * The shim uses a pure-JS closure so Kotlin/JS name-mangling is not a concern, and Promise
 * resolution is synchronous (the shim wraps values in `Promise.resolve`).
 */
class WebExtStorageCredentialVaultTest {

    @BeforeTest
    fun installChromeMock() {
        // Installs a minimal chrome.storage.local shim that stores values in a JS-level closure.
        // All calls return Promises (matching Chrome MV3 behaviour).
        js(
            """
            (function () {
                var store = {};
                globalThis.chrome = {
                    storage: {
                        local: {
                            get: function (key) {
                                var result = {};
                                if (store[key] !== undefined) { result[key] = store[key]; }
                                return Promise.resolve(result);
                            },
                            set: function (items) {
                                Object.keys(items).forEach(function (k) { store[k] = items[k]; });
                                return Promise.resolve(undefined);
                            },
                            remove: function (key) {
                                delete store[key];
                                return Promise.resolve(undefined);
                            }
                        }
                    }
                };
            })()
            """,
        )
    }

    // ---- steam credential round-trip -----------------------------------------------------------

    @Test
    fun steam_credential_survives_write_read_round_trip() = runTest {
        val vault = WebExtStorageCredentialVault()
        val cred = fakeSteamCredential(token = "test-token", steamId = "76561198000000001")

        vault.writeSteamCredential(cred)
        val read = vault.readSteamCredential()

        assertEquals(cred.token, read?.token)
        assertEquals(cred.subjectSteamId, read?.subjectSteamId)
        assertEquals(cred.expiresAt, read?.expiresAt)
    }

    // ---- empty storage returns null ------------------------------------------------------------

    @Test
    fun read_returns_null_when_steam_credential_absent() = runTest {
        val vault = WebExtStorageCredentialVault()
        assertNull(vault.readSteamCredential())
    }

    // ---- clear removes steam credential -------------------------------------------------------

    @Test
    fun clear_removes_steam_credential() = runTest {
        val vault = WebExtStorageCredentialVault()
        vault.writeSteamCredential(fakeSteamCredential())

        vault.clearSteamCredential()

        assertNull(vault.readSteamCredential())
    }

    // ---- on-disk key shape check --------------------------------------------------------------

    @Test
    fun device_vault_key_constants_are_stable() = runTest {
        // These key names are stored on disk in chrome.storage.local; changing them is a
        // breaking migration for any user with existing stored credentials.
        assertEquals("steam_credential", DeviceVaultKeys.STEAM_CREDENTIAL)
    }
}
