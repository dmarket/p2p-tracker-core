package com.dmarket.p2p.tracker.adapter.webext

import com.dmarket.p2p.tracker.credential.steam.DeviceVaultKeys
import com.dmarket.p2p.tracker.support.fakeSteamCredential
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for [WebExtStorageCredentialVault] running against in-memory `chrome.storage.local` and
 * `chrome.storage.session` shims installed on `globalThis`.
 *
 * The shim uses a pure-JS closure per area so Kotlin/JS name-mangling is not a concern, and every call
 * returns an already-resolved Promise (matching Chrome MV3 behaviour).
 */
class WebExtStorageCredentialVaultTest {

    @BeforeTest
    fun installChromeMock() {
        js(
            """
            (function () {
                function area() {
                    var store = {};
                    return {
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
                    };
                }
                globalThis.chrome = { storage: { local: area(), session: area() } };
            })()
            """,
        )
    }

    private val key = DeviceVaultKeys.STEAM_CREDENTIAL

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

    // ---- memory-only storage -----------------------------------------------------------------

    @Test
    fun write_lands_in_session_storage_and_never_in_local() = runTest {
        WebExtStorageCredentialVault().writeSteamCredential(fakeSteamCredential(token = "test-token"))

        assertNotNull(webExtStorageGet(key, STORAGE_SESSION))
        assertNull(webExtStorageGet(key, STORAGE_LOCAL))
    }

    @Test
    fun construction_removes_the_legacy_local_copy_without_any_vault_call() = runTest {
        webExtStorageSet(key, "{\"token\":\"old\"}", STORAGE_LOCAL)

        WebExtStorageCredentialVault()

        assertNull(webExtStorageGet(key, STORAGE_LOCAL))
    }

    @Test
    fun legacy_local_token_is_not_carried_over_so_the_loop_re_scrapes() = runTest {
        webExtStorageSet(key, "{\"token\":\"old\"}", STORAGE_LOCAL)

        assertNull(WebExtStorageCredentialVault().readSteamCredential())
        assertNull(webExtStorageGet(key, STORAGE_LOCAL))
    }

    @Test
    fun a_rejected_legacy_removal_does_not_break_vault_operations() = runTest {
        js("globalThis.chrome.storage.local.remove = function () { return Promise.reject(new Error('x')); }")
        val vault = WebExtStorageCredentialVault()

        assertNull(vault.readSteamCredential())
        vault.writeSteamCredential(fakeSteamCredential(token = "test-token"))

        assertEquals("test-token", vault.readSteamCredential()?.token)
    }

    @Test
    fun clear_leaves_session_storage_empty() = runTest {
        val vault = WebExtStorageCredentialVault()
        vault.writeSteamCredential(fakeSteamCredential())

        vault.clearSteamCredential()

        assertNull(webExtStorageGet(key, STORAGE_SESSION))
    }

    // ---- stored key shape check --------------------------------------------------------------

    @Test
    fun device_vault_key_constants_are_stable() = runTest {
        // The legacy-copy removal and the extension's hygiene/diagnostics code address this key by name.
        assertEquals("steam_credential", DeviceVaultKeys.STEAM_CREDENTIAL)
    }
}
