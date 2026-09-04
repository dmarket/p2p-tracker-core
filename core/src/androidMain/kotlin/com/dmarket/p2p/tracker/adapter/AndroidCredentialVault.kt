// PHASE 3 SCAFFOLD — reference only, NOT compiled until the Android target is enabled in
// build-logic/.../dmarket.kmp.library.gradle.kts (the `androidTarget { ... }` block). KMP ignores this
// source set until then; it is linted by spotless but not type-checked. Finalize against a real Android
// build. It implements ONLY the CredentialVault port.
//
// Encryption is delegated to the Android Keystore: the AES-256-GCM key is generated *inside* the
// Keystore (hardware-backed where available, StrongBox if present) and is **non-exportable** — it never
// enters lib/app memory. We deliberately do NOT use `androidx.security:security-crypto`
// (EncryptedSharedPreferences), which is deprecated/maintenance-only. Google Tink
// (`com.google.crypto.tink:tink-android`) is a fine maintained alternative if the team prefers a library
// over the direct-Keystore approach below — swap the cipher block, keep the port + holder wiring.
package com.dmarket.p2p.tracker.adapter

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.dmarket.p2p.tracker.credential.steam.DeviceVaultKeys
import com.dmarket.p2p.tracker.credential.steam.StoredSteamCredential
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.port.host.CredentialVault
import com.dmarket.p2p.tracker.wire.trackerJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android [CredentialVault]. The credential is JSON-encoded as a [StoredSteamCredential] (the same
 * on-disk shape the web/iOS vaults use), AES-256-GCM encrypted with a Keystore-resident key, and the
 * resulting `IV || ciphertext` blob is base64-stored in a private [Context.MODE_PRIVATE]
 * `SharedPreferences` file. Only ciphertext touches disk; the key never leaves the Keystore.
 *
 * The host supplies only a [Context] (non-secret) once at startup via [AndroidAppContextHolder]; the
 * plaintext [SteamCredential] is never handed back to host code — honouring the audit boundary.
 *
 * Storage/Keystore I/O is moved off the caller's dispatcher via [Dispatchers.IO]. The IV is regenerated
 * by the cipher on every encrypt (GCM must never reuse an IV under the same key) and stored alongside
 * the ciphertext.
 */
class AndroidCredentialVault(
    context: Context,
    private val prefsName: String = DEFAULT_PREFS,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : CredentialVault {

    private val appContext = context.applicationContext
    private val json = trackerJson { ignoreUnknownKeys = true }
    private val prefs = appContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override suspend fun readSteamCredential(): SteamCredential? = withContext(Dispatchers.IO) {
        val blob = prefs.getString(DeviceVaultKeys.STEAM_CREDENTIAL, null) ?: return@withContext null
        // A corrupt / undecryptable / schema-migrated row → null → triggers a re-scrape; no crash.
        runCatching { json.decodeFromString<StoredSteamCredential>(decrypt(blob)).toDomain() }.getOrNull()
    }

    override suspend fun writeSteamCredential(credential: SteamCredential) = withContext(Dispatchers.IO) {
        val plaintext = json.encodeToString(StoredSteamCredential.from(credential))
        prefs.edit().putString(DeviceVaultKeys.STEAM_CREDENTIAL, encrypt(plaintext)).apply()
    }

    override suspend fun clearSteamCredential() = withContext(Dispatchers.IO) {
        prefs.edit().remove(DeviceVaultKeys.STEAM_CREDENTIAL).apply()
    }

    // ---- AES-256-GCM via a non-exportable Keystore key --------------------------------------------

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.encodeToByteArray())
        val packed = ByteArray(1 + iv.size + ciphertext.size)
        packed[0] = iv.size.toByte()
        iv.copyInto(packed, 1)
        ciphertext.copyInto(packed, 1 + iv.size)
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        val packed = Base64.decode(blob, Base64.NO_WRAP)
        val ivLen = packed[0].toInt()
        val iv = packed.copyOfRange(1, 1 + ivLen)
        val ciphertext = packed.copyOfRange(1 + ivLen, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        return cipher.doFinal(ciphertext).decodeToString()
    }

    /** Loads the Keystore AES key for [keyAlias], generating a hardware-backed one on first use. */
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        const val DEFAULT_PREFS: String = "com.dmarket.p2p.tracker.secure"
        const val DEFAULT_KEY_ALIAS: String = "com.dmarket.p2p.tracker.steam-credential"
    }
}
