package com.dmarket.p2p.tracker.adapter

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts what the core keeps sealed on disk. A seam so tests can stand in for the Android Keystore, which the host
 * tests do not have.
 *
 * [decrypt] throws when the value cannot be opened: the key is gone (a restore or a device-to-device transfer, since
 * Keystore keys never leave the device), or the value is corrupt or was sealed by another key.
 */
interface SecretCipher {
    fun encrypt(plaintext: ByteArray): ByteArray

    fun decrypt(sealed: ByteArray): ByteArray
}

/**
 * AES-256-GCM with a non-exportable key generated inside the Android Keystore (hardware-backed where the device has
 * it). The key never enters process memory and is never backed up or transferred, which is what makes everything
 * sealed with it install-scoped. Output is `ivLength || iv || ciphertext+tag`; the cipher draws a fresh IV per call.
 *
 * Only [encrypt] creates the key. [decrypt] with no key fails instead of creating one: a fresh key could not open the
 * value anyway, and the caller treats the failure as "not there".
 */
class KeystoreAesGcmCipher(private val keyAlias: String = DEFAULT_KEY_ALIAS) : SecretCipher {
    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, existingKey() ?: generateKey()) }
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return byteArrayOf(iv.size.toByte()) + iv + ciphertext
    }

    override fun decrypt(sealed: ByteArray): ByteArray {
        val key = existingKey() ?: error("no Keystore key $keyAlias")
        val ivLength = sealed[0].toInt()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, sealed, 1, ivLength))
        }
        return cipher.doFinal(sealed, 1 + ivLength, sealed.size - 1 - ivLength)
    }

    private fun existingKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    private fun generateKey(): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
        init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        generateKey()
    }

    companion object {
        const val DEFAULT_KEY_ALIAS: String = "com.dmarket.p2p.tracker.install"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val KEY_BITS = 256
    }
}
