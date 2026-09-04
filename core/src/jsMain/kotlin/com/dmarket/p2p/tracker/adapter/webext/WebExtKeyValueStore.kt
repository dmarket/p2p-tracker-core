package com.dmarket.p2p.tracker.adapter.webext

import com.dmarket.p2p.tracker.port.host.DeviceKeyValueStore

/**
 * [DeviceKeyValueStore] over the WebExtension storage area, so common-code stores survive an MV3
 * service-worker teardown.
 *
 * Chrome and Firefox are both covered without a branch here: the shared bridge resolves
 * `browser.storage.local` when it exists and `chrome.storage.local` otherwise (see [webExtApi]). Needs
 * the `"storage"` permission, which the extension already declares for the credential vault.
 */
class WebExtKeyValueStore : DeviceKeyValueStore {
    override suspend fun get(key: String): String? = webExtStorageGet(key)

    override suspend fun set(key: String, value: String) = webExtStorageSet(key, value)

    override suspend fun remove(key: String) = webExtStorageRemove(key)
}
