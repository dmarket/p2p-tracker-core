package com.dmarket.p2p.tracker.adapter.webext

import com.dmarket.p2p.tracker.credential.steam.DeviceVaultKeys
import com.dmarket.p2p.tracker.credential.steam.StoredSteamCredential
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.port.host.CredentialVault
import com.dmarket.p2p.tracker.wire.trackerJson
import kotlinx.coroutines.await
import kotlinx.serialization.encodeToString
import kotlin.js.Promise

/**
 * [CredentialVault] backed by `chrome.storage.session` (Chrome MV3) or `browser.storage.session`
 * (Firefox MV3).
 *
 * The Steam credential (a JSON-encoded [StoredSteamCredential]) lives in memory only: it survives the
 * MV3 service worker being killed and restarted, is never written to disk, and is gone when the browser
 * closes. The next cycle then finds no credential and re-scrapes one from the logged-in Steam session.
 * The DMarket marketplace JWT is not stored here — it is re-scraped from the `dm-trade-token` cookie.
 *
 * Earlier builds kept the credential in `storage.local`, on disk; construction starts removing that copy,
 * and every operation waits for the removal first.
 *
 * Requires `"storage"` permission in `manifest.json`. Content scripts cannot read `storage.session`
 * unless the extension opens it with `setAccessLevel`; an extension that does exposes this token to them.
 */
class WebExtStorageCredentialVault : CredentialVault {

    private val json = trackerJson { ignoreUnknownKeys = true }

    // Kicked off at construction (worker spawn), so an idle seller's on-disk token goes too, not only on first use.
    // TODO(DMA-817): drop this removal (and its two tests) in the release after the one that first ships the
    //  session vault (1.2.1 or whatever 1.2.1-SNAPSHOT releases as). A device that skips it keeps an expired token.
    private val legacyRemoval: Promise<dynamic>? =
        runCatching { webExtStorageRemoveStart(DeviceVaultKeys.STEAM_CREDENTIAL, STORAGE_LOCAL) }.getOrNull()

    private suspend fun awaitLegacyRemoval() {
        runCatching { legacyRemoval?.await() }
    }

    override suspend fun readSteamCredential(): SteamCredential? {
        awaitLegacyRemoval()
        val raw = webExtStorageGet(DeviceVaultKeys.STEAM_CREDENTIAL, STORAGE_SESSION) ?: return null
        // A corrupt or schema-migrated row decodes to null → triggers a re-scrape; no crash.
        return runCatching {
            json.decodeFromString<StoredSteamCredential>(raw).toDomain()
        }.getOrNull()
    }

    override suspend fun writeSteamCredential(credential: SteamCredential) {
        awaitLegacyRemoval()
        webExtStorageSet(
            DeviceVaultKeys.STEAM_CREDENTIAL,
            json.encodeToString(StoredSteamCredential.from(credential)),
            STORAGE_SESSION,
        )
    }

    override suspend fun clearSteamCredential() {
        awaitLegacyRemoval()
        webExtStorageRemove(DeviceVaultKeys.STEAM_CREDENTIAL, STORAGE_SESSION)
    }
}
