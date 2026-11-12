package com.dmarket.p2p.tracker.adapter

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.UnrecoverableEntryException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts what the core keeps sealed on disk. A seam so tests can stand in for the Android Keystore, which the host
 * tests do not have.
 *
 * [decrypt] throws [SealKeyMissingException] when the key is gone (a restore or a device-to-device transfer, since
 * Keystore keys never leave the device) or can never be used again, and `AEADBadTagException` when the value is corrupt,
 * malformed or was sealed by another key. Anything else it throws is a failure of the key store itself, which may pass,
 * so it says nothing about the value.
 */
interface SecretCipher {
    fun encrypt(plaintext: ByteArray): ByteArray

    fun decrypt(sealed: ByteArray): ByteArray
}

/** [SecretCipher.decrypt] has no usable key under [alias]: whatever was sealed with it belongs to another install. */
class SealKeyMissingException(alias: String, cause: Throwable? = null) :
    GeneralSecurityException("no usable Keystore key $alias", cause)

/** Where [KeystoreAesGcmCipher] keeps its key. A seam so host tests run the real AES-GCM over a stand-in Keystore. */
internal interface SealKeyStore {
    val alias: String

    /** The key under [alias], or null when there is none. Throws what `KeyStore.getEntry` throws. */
    fun find(): SecretKey?

    /** Deletes whatever is under [alias] and generates a new key there. */
    fun replace(): SecretKey
}

/**
 * AES-256-GCM with a non-exportable key generated inside the Android Keystore (hardware-backed where the device has
 * it). The key never enters process memory and is never backed up or transferred, which is what makes everything
 * sealed with it install-scoped. Output is `ivLength || iv || ciphertext+tag`; the cipher draws a fresh IV per call.
 *
 * Only [encrypt] creates the key, and it replaces one that no longer works (`UnrecoverableKeyException`,
 * `KeyPermanentlyInvalidatedException` or another `InvalidKeyException`), since nothing sealed with it opens again.
 * [decrypt] with no usable key fails instead: a fresh key could not open the value anyway, and the caller treats the
 * failure as "not there". `KeyStoreException` and `ProviderException` pass through untouched.
 */
class KeystoreAesGcmCipher internal constructor(private val keys: SealKeyStore) : SecretCipher {
    constructor(keyAlias: String = DEFAULT_KEY_ALIAS) : this(AndroidSealKeyStore(keyAlias))

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = try {
            cipherFor(Cipher.ENCRYPT_MODE, usableKey() ?: keys.replace())
        } catch (_: SealKeyMissingException) {
            cipherFor(Cipher.ENCRYPT_MODE, keys.replace())
        }
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return byteArrayOf(iv.size.toByte()) + iv + ciphertext
    }

    override fun decrypt(sealed: ByteArray): ByteArray {
        val ivLength = sealed.firstOrNull()?.toInt() ?: 0
        if (ivLength !in IV_BYTES || sealed.size < 1 + ivLength + GCM_TAG_BITS / 8) {
            throw AEADBadTagException("not a sealed value")
        }
        val key = usableKey() ?: throw SealKeyMissingException(keys.alias)
        val cipher = cipherFor(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, sealed, 1, ivLength))
        return cipher.doFinal(sealed, 1 + ivLength, sealed.size - 1 - ivLength)
    }

    private fun usableKey(): SecretKey? = try {
        keys.find()
    } catch (e: UnrecoverableEntryException) {
        throw SealKeyMissingException(keys.alias, e)
    }

    private fun cipherFor(mode: Int, key: SecretKey, spec: GCMParameterSpec? = null): Cipher = Cipher.getInstance(TRANSFORMATION).apply {
        try {
            if (spec == null) init(mode, key) else init(mode, key, spec)
        } catch (e: InvalidKeyException) {
            throw SealKeyMissingException(keys.alias, e)
        }
    }

    companion object {
        const val DEFAULT_KEY_ALIAS: String = "com.dmarket.p2p.tracker.install"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private val IV_BYTES = 12..16
    }
}

private class AndroidSealKeyStore(override val alias: String) : SealKeyStore {
    override fun find(): SecretKey? = (keyStore().getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey

    override fun replace(): SecretKey {
        keyStore().deleteEntry(alias)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_BITS)
                    .build(),
            )
            generateKey()
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_BITS = 256
    }
}
