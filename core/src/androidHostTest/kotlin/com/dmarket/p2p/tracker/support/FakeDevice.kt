package com.dmarket.p2p.tracker.support

import android.content.SharedPreferences
import com.dmarket.p2p.tracker.adapter.SecretCipher
import kotlin.random.Random

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
 * The Android Keystore of one device: a key made on the first encrypt, kept on the device, never copied. A value
 * sealed under another key (or with no key at all) does not open, as AES-GCM's tag check would refuse it.
 */
class FakeKeystore : SecretCipher {
    private var key: ByteArray? = null

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val k = key ?: Random.nextBytes(KEY_BYTES).also { key = it }
        return k + plaintext.mapIndexed { i, b -> (b.toInt() xor k[i % KEY_BYTES].toInt()).toByte() }
    }

    override fun decrypt(sealed: ByteArray): ByteArray {
        val k = checkNotNull(key) { "no key" }
        check(sealed.size >= KEY_BYTES && sealed.copyOf(KEY_BYTES).contentEquals(k)) { "tag mismatch" }
        return sealed.copyOfRange(KEY_BYTES, sealed.size).mapIndexed { i, b -> (b.toInt() xor k[i % KEY_BYTES].toInt()).toByte() }
            .toByteArray()
    }

    private companion object {
        const val KEY_BYTES = 16
    }
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
