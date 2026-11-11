// The default createLoop falls back to. It is not scoped to an account or bound to the install: the Android
// tracker takes its vault and stores from AndroidTrackerPlatform instead.
package com.dmarket.p2p.tracker.adapter

import com.dmarket.p2p.tracker.port.host.DeviceKeyValueStore

/**
 * Android resolves to [SharedPreferencesKeyValueStore]. Like [platformCredentialVault], it takes the
 * application [android.content.Context] the host stashed once at startup ([AndroidAppContextHolder]), so
 * this factory stays free of Context plumbing.
 */
actual fun platformKeyValueStore(): DeviceKeyValueStore = SharedPreferencesKeyValueStore(AndroidAppContextHolder.context)
