// PHASE 3 SCAFFOLD — reference only, NOT compiled until the Android target is enabled in
// build-logic/.../dmarket.kmp.library.gradle.kts (the `androidTarget { ... }` block). KMP ignores this
// source set until then; it is linted by spotless but not type-checked.
package com.dmarket.p2p.tracker.adapter

import com.dmarket.p2p.tracker.port.host.DeviceKeyValueStore

/**
 * Android resolves to [SharedPreferencesKeyValueStore]. Like [platformCredentialVault], it takes the
 * application [android.content.Context] the host stashed once at startup ([AndroidAppContextHolder]), so
 * this factory stays free of Context plumbing.
 */
actual fun platformKeyValueStore(): DeviceKeyValueStore = SharedPreferencesKeyValueStore(AndroidAppContextHolder.context)
