package com.dmarket.p2p.tracker.adapter.host

import com.dmarket.p2p.tracker.model.steam.SteamCookie
import com.dmarket.p2p.tracker.port.host.DeviceKeyValueStore
import com.dmarket.p2p.tracker.port.host.SteamCookieVault
import com.dmarket.p2p.tracker.wire.trackerJson

/**
 * [SteamCookieVault] as one JSON entry in [sealed], a store the platform seals or keeps in its secure storage (the
 * Android Keystore, the iOS Keychain). A value that does not decode is erased and read as no session.
 */
class SealedSteamCookieVault(private val sealed: DeviceKeyValueStore) : SteamCookieVault {
    private val json = trackerJson { ignoreUnknownKeys = true }

    override suspend fun readSteamCookies(): List<SteamCookie> {
        val stored = sealed.get(KEY) ?: return emptyList()
        return runCatching { json.decodeFromString<List<SteamCookie>>(stored) }.getOrElse {
            sealed.remove(KEY)
            emptyList()
        }
    }

    override suspend fun writeSteamCookies(cookies: List<SteamCookie>) {
        if (cookies.isEmpty()) sealed.remove(KEY) else sealed.set(KEY, json.encodeToString(cookies))
    }

    override suspend fun clearSteamCookies() {
        sealed.remove(KEY)
    }

    companion object {
        /** The key of the JSON-encoded cookie list. */
        const val KEY: String = "steam_cookies"
    }
}
