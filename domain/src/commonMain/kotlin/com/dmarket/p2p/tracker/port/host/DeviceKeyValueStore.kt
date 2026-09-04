package com.dmarket.p2p.tracker.port.host

/**
 * A minimal **non-secret** device-local string key-value store — the one persistence primitive the
 * library needs on every target, so higher-level stores can be written **once** in common code instead
 * of per platform.
 *
 * Each platform's `actual` is a thin adapter over what that OS already provides
 * (`browser`/`chrome.storage.local` on web, private `SharedPreferences` on Android, `NSUserDefaults` on
 * iOS, memory on the JVM) — see `platformKeyValueStore`.
 *
 * **Contract:**
 * - **Non-secret data only.** Credentials go to [CredentialVault], which delegates to OS-backed secure
 *   storage; nothing here is encrypted. Storing a token through this port would break the audit boundary.
 * - Values are opaque strings; structured data is JSON-encoded by the caller.
 * - **No atomicity and no compare-and-swap** are promised — none of the backing stores offer one. A
 *   caller that read-modify-writes a value must serialize its own access (the claim store holds a
 *   `Mutex` across the whole read-decide-write for exactly this reason).
 * - Reads of an absent key return `null`; a write to an existing key overwrites it.
 * - Implementations may perform blocking IO, so every method is `suspend` and each `actual` owns the
 *   dispatcher it needs.
 */
interface DeviceKeyValueStore {
    /** The stored string for [key], or `null` when absent. */
    suspend fun get(key: String): String?

    /** Stores [value] under [key], overwriting any previous value. */
    suspend fun set(key: String, value: String)

    /** Removes [key]; a no-op when absent. */
    suspend fun remove(key: String)
}
