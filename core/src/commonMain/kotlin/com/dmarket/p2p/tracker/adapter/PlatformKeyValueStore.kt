package com.dmarket.p2p.tracker.adapter

import com.dmarket.p2p.tracker.adapter.host.InMemoryDeviceKeyValueStore
import com.dmarket.p2p.tracker.port.host.DeviceKeyValueStore

/**
 * The single place that **decides which [DeviceKeyValueStore] to use** — the cross-platform
 * non-secret-persistence selection, resolved by which `actual` compiles. Mirrors
 * [platformCredentialVault] and [platformScheduler].
 *
 * This exists so stores that need to survive a process death can be written **once** in common code
 * (`PersistedDealWriteClaimStore` is the first) instead of one adapter per platform per concern — which
 * is how the web-only `WebExtStorage*` stores ended up leaving mobile with in-memory-only fallbacks.
 *
 * - **web** → `WebExtKeyValueStore` (`browser`/`chrome.storage.local`; survives MV3 worker teardown).
 * - **Android** → `SharedPreferencesKeyValueStore` (private `SharedPreferences`).
 * - **iOS** → `UserDefaultsKeyValueStore` (`NSUserDefaults`).
 * - **JVM** → [InMemoryDeviceKeyValueStore] — process-lifetime only; the JVM target is not a shipping client.
 *
 * **Non-secret data only** — see the [DeviceKeyValueStore] contract; credentials belong to
 * [com.dmarket.p2p.tracker.port.host.CredentialVault], which is OS-secure-storage backed.
 *
 * Callers that read-modify-write should construct **one** store per process and serialize their own
 * access: no backend here offers compare-and-swap.
 */
expect fun platformKeyValueStore(): DeviceKeyValueStore
