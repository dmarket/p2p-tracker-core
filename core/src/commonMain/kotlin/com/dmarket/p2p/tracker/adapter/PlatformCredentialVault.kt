package com.dmarket.p2p.tracker.adapter

import com.dmarket.p2p.tracker.adapter.host.InMemoryCredentialVault
import com.dmarket.p2p.tracker.port.host.CredentialVault

/**
 * The single place that **decides which [CredentialVault] to use** — the cross-platform secure-storage
 * selection, resolved by which `actual` compiles (web / JVM / iOS / Android). Mirrors
 * [platformScheduler].
 *
 * This factory exists so the Steam credential's storage is **lib-owned**: production code never asks
 * the host to supply a vault, so the host never touches the plaintext credential. Each `actual`
 * delegates encryption to OS-backed secure storage (Keychain / Android Keystore /
 * `chrome.storage.local`); the lib hand-rolls no crypto. See [CredentialVault] for the security
 * contract every `actual` must honour.
 *
 * - **iOS** → `KeychainCredentialVault` (hardware-backed, never leaves the device).
 * - **Android** → `AndroidCredentialVault` (Android Keystore-backed; key is non-exportable).
 * - **web** → [WebExtStorageCredentialVault] (`chrome.storage.local`; origin-isolated, see its note).
 * - **JVM** → [InMemoryCredentialVault] — **test/foreground-only**, plaintext, lost on process exit.
 */
expect fun platformCredentialVault(): CredentialVault
