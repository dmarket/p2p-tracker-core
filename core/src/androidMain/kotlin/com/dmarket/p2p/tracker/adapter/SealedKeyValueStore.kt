package com.dmarket.p2p.tracker.adapter

import com.dmarket.p2p.tracker.port.host.DeviceKeyValueStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.crypto.AEADBadTagException
import kotlin.io.encoding.Base64

/**
 * A [DeviceKeyValueStore] whose values are sealed with [cipher] before they reach [plain]; only ciphertext touches
 * disk.
 *
 * A value that does not open (the Keystore key is gone after a restore or a device transfer, or the value is corrupt)
 * is removed and read as absent: the caller starts over as on a fresh install. Any other failure of [cipher] (a
 * Keystore that is busy or restarting) is thrown and the value stays, so a passing fault never erases the install.
 *
 * Locked so two first writes cannot race the Keystore into generating two keys under one alias.
 */
class SealedKeyValueStore(private val plain: DeviceKeyValueStore, private val cipher: SecretCipher) : DeviceKeyValueStore {
    private val mutex = Mutex()

    override suspend fun get(key: String): String? = mutex.withLock {
        val stored = plain.get(key) ?: return@withLock null
        val opened = runCatching { Base64.decode(stored) }.getOrNull()?.let { sealed ->
            try {
                cipher.decrypt(sealed)
            } catch (_: AEADBadTagException) {
                null
            } catch (_: SealKeyMissingException) {
                null
            }
        }
        if (opened == null) plain.remove(key)
        opened?.decodeToString()
    }

    override suspend fun set(key: String, value: String) = mutex.withLock {
        plain.set(key, Base64.encode(cipher.encrypt(value.encodeToByteArray())))
    }

    override suspend fun remove(key: String) = mutex.withLock { plain.remove(key) }
}
