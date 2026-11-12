package com.dmarket.p2p.tracker.support

import android.content.SharedPreferences
import com.dmarket.p2p.tracker.adapter.KeystoreAesGcmCipher
import com.dmarket.p2p.tracker.adapter.SealKeyStore
import com.dmarket.p2p.tracker.adapter.SecretCipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The prefs files of one install, as on disk: file name → key → value. [copy] is what a backup or a device-to-device
 * transfer carries over.
 */
class FakeDisk(val files: MutableMap<String, MutableMap<String, String>> = mutableMapOf()) {
    fun prefs(name: String): SharedPreferences = FakePreferences(files.getOrPut(name) { mutableMapOf() })

    fun copy(): FakeDisk = FakeDisk(files.mapValuesTo(mutableMapOf()) { (_, file) -> file.toMutableMap() })

    fun allValues(): List<String> = files.values.flatMap { it.values }
}

/**
 * The Android Keystore of one device under [KeystoreAesGcmCipher]: no key until the first encrypt, kept on the device,
 * never copied. While [failure] is set, every call throws it, as a busy or restarting Keystore does; [entryFailure] is
 * what reading the entry throws (an entry that never opens again) until a new key replaces it.
 */
class FakeKeystore : SealKeyStore {
    override val alias = "fake"
    var key: SecretKey? = null
    var failure: Exception? = null
    var entryFailure: Exception? = null

    override fun find(): SecretKey? {
        failure?.let { throw it }
        entryFailure?.let { throw it }
        return key
    }

    override fun replace(): SecretKey {
        failure?.let { throw it }
        entryFailure = null
        return KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }
    }

    fun cipher(): SecretCipher = KeystoreAesGcmCipher(this)
}

/** A string-only [SharedPreferences] over [values]; `commit()` applies `clear()` first, as Android does. */
private class FakePreferences(private val values: MutableMap<String, String>) : SharedPreferences {
    override fun getAll(): Map<String, *> = values.toMap()

    override fun getString(key: String, defValue: String?): String? = values[key] ?: defValue

    override fun contains(key: String): Boolean = key in values

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = unsupported()

    override fun getInt(key: String, defValue: Int): Int = unsupported()

    override fun getLong(key: String, defValue: Long): Long = unsupported()

    override fun getFloat(key: String, defValue: Float): Float = unsupported()

    override fun getBoolean(key: String, defValue: Boolean): Boolean = unsupported()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private var clear = false
        private val puts = mutableMapOf<String, String?>()

        override fun putString(key: String, value: String?) = apply { puts[key] = value }

        override fun remove(key: String) = apply { puts[key] = null }

        override fun clear() = apply { clear = true }

        override fun commit(): Boolean {
            if (clear) values.clear()
            puts.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            return true
        }

        override fun apply() {
            commit()
        }

        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor = unsupported()

        override fun putInt(key: String, value: Int): SharedPreferences.Editor = unsupported()

        override fun putLong(key: String, value: Long): SharedPreferences.Editor = unsupported()

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = unsupported()

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = unsupported()
    }
}

private fun unsupported(): Nothing = throw UnsupportedOperationException("the core stores strings only")
