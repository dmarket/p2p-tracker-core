// PHASE 3 SCAFFOLD — reference only, NOT compiled until the iOS targets are enabled in
// build-logic/.../dmarket.kmp.library.gradle.kts (iosArm64()/iosSimulatorArm64()/iosX64()). KMP ignores
// this source set until then; it is linted by spotless but not type-checked.
package com.dmarket.p2p.tracker.adapter

import com.dmarket.p2p.tracker.port.host.DeviceKeyValueStore

/**
 * iOS resolves to [UserDefaultsKeyValueStore]. No host input is needed (`NSUserDefaults` is
 * process-wide), mirroring how [platformCredentialVault] needs none for the Keychain.
 */
actual fun platformKeyValueStore(): DeviceKeyValueStore = UserDefaultsKeyValueStore()
