package com.dmarket.p2p.tracker.adapter.host

import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.port.host.CredentialVault

/**
 * In-memory [CredentialVault] stub.
 *
 * Suitable for JVM tests, browser demos, and any context where OS-level secure storage is not
 * available. **Not suitable for production use in mobile/extension targets** — credentials are
 * stored in plain memory and lost on process exit. Phase 3 replaces this with Keychain (iOS),
 * EncryptedSharedPreferences (Android), and `chrome.storage.local` (web extension).
 */
class InMemoryCredentialVault : CredentialVault {
    // No @Volatile: JS is single-threaded; JVM callers share a single coroutine scope per loop.
    private var steamCredential: SteamCredential? = null

    override suspend fun readSteamCredential(): SteamCredential? = steamCredential

    override suspend fun writeSteamCredential(credential: SteamCredential) {
        steamCredential = credential
    }

    override suspend fun clearSteamCredential() {
        steamCredential = null
    }
}
