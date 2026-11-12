package com.dmarket.p2p.tracker.adapter

import android.content.Context
import android.content.SharedPreferences
import com.dmarket.p2p.tracker.port.host.DeviceKeyValueStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android [DeviceKeyValueStore] over one private `SharedPreferences` file. **Non-secret data only**: nothing here is
 * encrypted; secrets go to [AndroidCredentialVault].
 *
 * Every call runs on [io], never on the caller's thread. Writes use `commit()`, which returns once the file is on
 * disk, so a write that returned survives the process being killed right after (`apply()` would not); a `commit()`
 * that answers `false` throws `IllegalStateException`.
 */
class SharedPreferencesKeyValueStore(private val prefs: SharedPreferences, private val io: CoroutineDispatcher = Dispatchers.IO) :
    DeviceKeyValueStore {
    constructor(context: Context, prefsName: String = DEFAULT_PREFS) :
        this(context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE))

    override suspend fun get(key: String): String? = withContext(io) { prefs.getString(key, null) }

    override suspend fun set(key: String, value: String) {
        withContext(io) { check(prefs.edit().putString(key, value).commit()) { COMMIT_FAILED } }
    }

    override suspend fun remove(key: String) {
        withContext(io) { check(prefs.edit().remove(key).commit()) { COMMIT_FAILED } }
    }

    /** Removes every key in the file. */
    suspend fun clear() {
        withContext(io) { check(prefs.edit().clear().commit()) { COMMIT_FAILED } }
    }

    companion object {
        const val DEFAULT_PREFS: String = "dmarket_p2p_tracker_state"
        private const val COMMIT_FAILED = "SharedPreferences commit() did not write the file"
    }
}
