// PHASE 3 SCAFFOLD — reference only, NOT compiled until the iOS targets are enabled in
// build-logic/.../dmarket.kmp.library.gradle.kts (iosArm64()/iosSimulatorArm64()/iosX64()). KMP ignores
// this source set until then; it is linted by spotless but not type-checked, and can only be built on a
// macOS CI runner with full Xcode. It implements ONLY the DeviceKeyValueStore port.
package com.dmarket.p2p.tracker.adapter

import com.dmarket.p2p.tracker.port.host.DeviceKeyValueStore
import platform.Foundation.NSUserDefaults

/**
 * iOS [DeviceKeyValueStore] over [NSUserDefaults].
 *
 * **Non-secret data only** — `NSUserDefaults` is a plist, not secure storage; the Steam credential goes
 * through `KeychainCredentialVault` instead. Keys are namespaced so the tracker's rows never collide with
 * the host app's own defaults.
 *
 * No dispatcher hop: `NSUserDefaults` reads are served from an in-process cache and writes are flushed by
 * the OS, so there is no blocking disk IO to move off the caller's thread (unlike the Android prefs actual).
 */
class UserDefaultsKeyValueStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
    private val keyPrefix: String = DEFAULT_PREFIX,
) : DeviceKeyValueStore {
    override suspend fun get(key: String): String? = defaults.stringForKey(keyPrefix + key)

    override suspend fun set(key: String, value: String) = defaults.setObject(value, keyPrefix + key)

    override suspend fun remove(key: String) = defaults.removeObjectForKey(keyPrefix + key)

    companion object {
        const val DEFAULT_PREFIX: String = "com.dmarket.p2p.tracker."
    }
}
