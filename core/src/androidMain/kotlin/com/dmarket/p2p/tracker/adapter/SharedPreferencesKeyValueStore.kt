// PHASE 3 SCAFFOLD — reference only, NOT compiled until the Android target is enabled in
// build-logic/.../dmarket.kmp.library.gradle.kts (the `androidTarget { ... }` block). KMP ignores this
// source set until then; it is linted by spotless but not type-checked. Finalize against a real Android
// build. It implements ONLY the DeviceKeyValueStore port.
package com.dmarket.p2p.tracker.adapter

import android.content.Context
import com.dmarket.p2p.tracker.port.host.DeviceKeyValueStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android [DeviceKeyValueStore] over a private [Context.MODE_PRIVATE] `SharedPreferences` file.
 *
 * **Non-secret data only** — this is deliberately *not* encrypted; the Steam credential goes through
 * `AndroidCredentialVault` (Keystore-backed) instead. Prefs IO is moved off the caller's dispatcher via
 * [Dispatchers.IO], matching the vault; writes use `commit()`-free `apply()` since durability here is
 * best-effort by contract (see `PersistedDealWriteClaimStore`).
 *
 * A separate prefs file from the vault's keeps the two concerns independently clearable.
 */
class SharedPreferencesKeyValueStore(context: Context, prefsName: String = DEFAULT_PREFS) : DeviceKeyValueStore {
    private val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override suspend fun get(key: String): String? = withContext(Dispatchers.IO) { prefs.getString(key, null) }

    override suspend fun set(key: String, value: String) = withContext(Dispatchers.IO) {
        prefs.edit().putString(key, value).apply()
    }

    override suspend fun remove(key: String) = withContext(Dispatchers.IO) {
        prefs.edit().remove(key).apply()
    }

    companion object {
        const val DEFAULT_PREFS: String = "dmarket_p2p_tracker_state"
    }
}
