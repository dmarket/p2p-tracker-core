package com.dmarket.p2p.tracker.adapter

import com.dmarket.p2p.tracker.adapter.webext.WebExtKeyValueStore
import com.dmarket.p2p.tracker.port.host.DeviceKeyValueStore

/**
 * Web resolves to [WebExtKeyValueStore] (`browser`/`chrome.storage.local`), which survives an MV3
 * service-worker teardown — the whole reason persistence exists on this target.
 */
actual fun platformKeyValueStore(): DeviceKeyValueStore = WebExtKeyValueStore()
