// Compiled with the Android target. Nothing calls it yet: startTracker has no Android runtime until the
// Android tracker facade lands (DMA-661), and the store it returns is reworked with the persistent state.
package com.dmarket.p2p.tracker.adapter

import com.dmarket.p2p.tracker.port.host.DeviceKeyValueStore

/**
 * Android resolves to [SharedPreferencesKeyValueStore]. Like [platformCredentialVault], it takes the
 * application [android.content.Context] the host stashed once at startup ([AndroidAppContextHolder]), so
 * this factory stays free of Context plumbing.
 */
actual fun platformKeyValueStore(): DeviceKeyValueStore = SharedPreferencesKeyValueStore(AndroidAppContextHolder.context)
