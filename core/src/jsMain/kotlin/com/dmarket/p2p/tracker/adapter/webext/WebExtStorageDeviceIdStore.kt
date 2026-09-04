package com.dmarket.p2p.tracker.adapter.webext

import com.dmarket.p2p.tracker.credential.steam.DeviceVaultKeys
import com.dmarket.p2p.tracker.model.DeviceId
import com.dmarket.p2p.tracker.port.host.DeviceIdStore

/**
 * [DeviceIdStore] backed by `chrome.storage.local`. On the first call a UUID is generated via
 * `crypto.randomUUID()` and persisted; subsequent calls return the same value.
 *
 * The `device_id` is install-scoped and persistent — it must survive token refresh, re-login, and
 * MV3 service-worker respawns because the backend leases each directive to one `device_id`.
 */
class WebExtStorageDeviceIdStore : DeviceIdStore {
    override suspend fun current(): DeviceId {
        val existing = webExtStorageGet(DeviceVaultKeys.DEVICE_ID)
        if (existing != null) return DeviceId(existing)
        @Suppress("UnsafeCastFromDynamic")
        val generated: String = js("crypto.randomUUID()")
        webExtStorageSet(DeviceVaultKeys.DEVICE_ID, generated)
        return DeviceId(generated)
    }
}
