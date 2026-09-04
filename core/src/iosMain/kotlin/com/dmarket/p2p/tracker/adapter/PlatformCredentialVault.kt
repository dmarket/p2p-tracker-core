// PHASE 3 SCAFFOLD — reference only, NOT compiled until the iOS targets are enabled in
// build-logic/.../dmarket.kmp.library.gradle.kts (iosArm64()/iosSimulatorArm64()/iosX64()). KMP ignores
// this source set until then; it is linted by spotless but not type-checked.
package com.dmarket.p2p.tracker.adapter

import com.dmarket.p2p.tracker.port.host.CredentialVault

/**
 * iOS resolves to the hardware-backed [KeychainCredentialVault]. No host input is needed (the Keychain
 * is process-wide), so the plaintext credential never crosses into host code.
 */
actual fun platformCredentialVault(): CredentialVault = KeychainCredentialVault()
