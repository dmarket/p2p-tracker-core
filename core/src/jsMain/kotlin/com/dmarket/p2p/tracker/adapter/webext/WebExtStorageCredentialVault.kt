package com.dmarket.p2p.tracker.adapter.webext

import com.dmarket.p2p.tracker.credential.steam.DeviceVaultKeys
import com.dmarket.p2p.tracker.credential.steam.StoredSteamCredential
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.port.host.CredentialVault
import com.dmarket.p2p.tracker.wire.trackerJson
import kotlinx.serialization.encodeToString

/**
 * [CredentialVault] backed by `chrome.storage.local` (Chrome MV3) or `browser.storage.local`
 * (Firefox MV3).
 *
 * Persists the Steam credential (as a JSON-encoded [StoredSteamCredential]) so it is not lost when
 * the MV3 service worker is killed and restarted. The DMarket marketplace JWT is not stored here —
 * it is re-scraped from the `dm-trade-token` cookie on demand.
 *
 * Requires `"storage"` permission in `manifest.json`.
 *
 * Storage note: `chrome.storage.local` is **not encrypted at rest**. It is origin-isolated
 * (writable only by this extension) but is not a hardware secure enclave. See the extension's
 * README for details.
 */
class WebExtStorageCredentialVault : CredentialVault {

    private val json = trackerJson { ignoreUnknownKeys = true }

    override suspend fun readSteamCredential(): SteamCredential? {
        val raw = webExtStorageGet(DeviceVaultKeys.STEAM_CREDENTIAL) ?: return null
        // A corrupt or schema-migrated row decodes to null → triggers a re-scrape; no crash.
        return runCatching {
            json.decodeFromString<StoredSteamCredential>(raw).toDomain()
        }.getOrNull()
    }

    override suspend fun writeSteamCredential(credential: SteamCredential) {
        webExtStorageSet(DeviceVaultKeys.STEAM_CREDENTIAL, json.encodeToString(StoredSteamCredential.from(credential)))
    }

    override suspend fun clearSteamCredential() {
        webExtStorageRemove(DeviceVaultKeys.STEAM_CREDENTIAL)
    }
}
