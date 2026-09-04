package com.dmarket.p2p.tracker.adapter.webext

import kotlinx.coroutines.await
import kotlin.js.Promise

/**
 * Shared `chrome.storage.local` (Chrome MV3) / `browser.storage.local` (Firefox) bridge.
 *
 * Centralised so every persistent adapter (the credential vault, the change-detection store, the
 * loop-state store) talks to storage the same way and survives MV3 service-worker teardown.
 * Requires the `"storage"` permission in `manifest.json`. Values are bare strings; structured data
 * is JSON-encoded by the caller.
 */

/** Returns `browser.storage.local` (Firefox) or `chrome.storage.local` (Chrome). */
private fun storageArea(): dynamic = webExtApi().storage.local

/** The stored string for [key], or `null` if absent. */
internal suspend fun webExtStorageGet(key: String): String? {
    val area = storageArea()

    @Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")
    val items: dynamic = area.get(key).unsafeCast<Promise<dynamic>>().await()
    // items[key] is the stored string, or undefined if absent; `as? String` returns null for both.
    return items[key] as? String
}

internal suspend fun webExtStorageSet(key: String, value: String) {
    val area = storageArea()
    val items: dynamic = js("({})")
    items[key] = value

    @Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")
    area.set(items).unsafeCast<Promise<dynamic>>().await()
}

internal suspend fun webExtStorageRemove(key: String) {
    val area = storageArea()

    @Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")
    area.remove(key).unsafeCast<Promise<dynamic>>().await()
}
