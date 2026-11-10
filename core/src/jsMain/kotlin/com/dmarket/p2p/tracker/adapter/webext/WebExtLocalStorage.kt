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

internal const val STORAGE_LOCAL = "local"

/** In-memory only: never written to disk, cleared when the browser closes (Chrome 102+, Firefox 115+). */
internal const val STORAGE_SESSION = "session"

/** Returns `browser.storage.<area>` (Firefox) or `chrome.storage.<area>` (Chrome). */
private fun storageArea(area: String): dynamic = webExtApi().storage[area]

/** The stored string for [key], or `null` if absent. */
internal suspend fun webExtStorageGet(key: String, areaName: String = STORAGE_LOCAL): String? {
    val area = storageArea(areaName)

    @Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")
    val items: dynamic = area.get(key).unsafeCast<Promise<dynamic>>().await()
    // items[key] is the stored string, or undefined if absent; `as? String` returns null for both.
    return items[key] as? String
}

internal suspend fun webExtStorageSet(key: String, value: String, areaName: String = STORAGE_LOCAL) {
    val area = storageArea(areaName)
    val items: dynamic = js("({})")
    items[key] = value

    @Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")
    area.set(items).unsafeCast<Promise<dynamic>>().await()
}

internal suspend fun webExtStorageRemove(key: String, areaName: String = STORAGE_LOCAL) {
    webExtStorageRemoveStart(key, areaName).await()
}

/** Starts the remove without suspending, for callers that must kick it off from a constructor. */
@Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")
internal fun webExtStorageRemoveStart(key: String, areaName: String = STORAGE_LOCAL): Promise<dynamic> =
    storageArea(areaName).remove(key).unsafeCast<Promise<dynamic>>()
