package com.dmarket.p2p.tracker.support

import android.content.SharedPreferences
import android.security.KeyStoreException
import android.security.Keystore2Failure
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
    /** While true, `commit()` writes nothing and answers `false`, as for a full or read-only disk. */
    var commitFails = false

    fun prefs(name: String): SharedPreferences = FakePreferences(files.getOrPut(name) { mutableMapOf() }) { !commitFails }

    fun copy(): FakeDisk = FakeDisk(files.mapValuesTo(mutableMapOf()) { (_, file) -> file.toMutableMap() })

    fun allValues(): List<String> = files.values.flatMap { it.values }
}

/**
 * The Android Keystore of one device on API [sdkInt] under [KeystoreAesGcmCipher]: no key until the first encrypt, kept
 * on the device, never copied. Until a new key replaces the entry, [entryFailure] is what `KeyStore.getEntry` throws and
 * [initFailure] what `Cipher.init` throws for the key; [generateFailure] is what generating a key throws. keystore2 uses
 * the same wrappers for a key that is gone and for a busy service, so build them with [gone] and [busy].
 */
class FakeKeystore(var sdkInt: Int = 36) : SealKeyStore {
    override val alias = "fake"
    var key: SecretKey? = null
    var entryFailure: Exception? = null
    var initFailure: Exception? = null
    var generateFailure: Exception? = null

    override fun find(): SecretKey? {
        entryFailure?.let { throw it }
        return initFailure?.let(::RefusedKey) ?: key
    }

    override fun replace(): SecretKey {
        generateFailure?.let { throw it }
        entryFailure = null
        initFailure = null
        return KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }
    }

    fun cipher(): SecretCipher = KeystoreAesGcmCipher(this, sdkInt)

    companion object {
        fun gone(code: Int = KeyStoreException.ERROR_KEY_DOES_NOT_EXIST) = Keystore2Failure(code, transient = false)

        fun busy(code: Int = KeyStoreException.ERROR_INTERNAL_SYSTEM_ERROR) = Keystore2Failure(code, transient = true)
    }
}

/** A key handle `Cipher.init` refuses: the JCE rethrows what [getEncoded] throws when no provider takes the key. */
private class RefusedKey(private val failure: Exception) : SecretKey {
    override fun getAlgorithm() = "AES"

    override fun getFormat() = "RAW"

    override fun getEncoded(): ByteArray = throw failure
}

/** A string-only [SharedPreferences] over [values]; `commit()` applies `clear()` first, as Android does. */
private class FakePreferences(private val values: MutableMap<String, String>, private val writes: () -> Boolean) : SharedPreferences {
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
            if (!writes()) return false
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
