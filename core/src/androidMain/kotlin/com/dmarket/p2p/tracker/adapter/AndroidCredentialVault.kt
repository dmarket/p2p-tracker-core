package com.dmarket.p2p.tracker.adapter

import android.content.Context
import com.dmarket.p2p.tracker.credential.steam.DeviceVaultKeys
import com.dmarket.p2p.tracker.credential.steam.StoredSteamCredential
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.port.host.CredentialVault
import com.dmarket.p2p.tracker.port.host.DeviceKeyValueStore
import com.dmarket.p2p.tracker.wire.trackerJson
import kotlinx.serialization.encodeToString

/**
 * Android [CredentialVault]: the Steam credential, JSON-encoded as a [StoredSteamCredential], in a [sealed] store
 * (AES-256-GCM under a non-exportable Android Keystore key, see [KeystoreAesGcmCipher]). Only ciphertext touches disk
 * and the plaintext is never handed to host code.
 *
 * An entry that does not open or does not decode (the key is gone after a restore or a device transfer, a corrupt
 * value, an old schema) is erased and read as `null`, never thrown. `SteamCredentialProvider` then takes the refresh
 * path, as it does for any credential it has not got.
 */
class AndroidCredentialVault(private val sealed: DeviceKeyValueStore) : CredentialVault {
    /** A vault in its own prefs file, sealed with the default Keystore key. */
    constructor(context: Context, prefsName: String = DEFAULT_PREFS) :
        this(SealedKeyValueStore(SharedPreferencesKeyValueStore(context, prefsName), KeystoreAesGcmCipher()))

    private val json = trackerJson { ignoreUnknownKeys = true }

    override suspend fun readSteamCredential(): SteamCredential? {
        val stored = sealed.get(DeviceVaultKeys.STEAM_CREDENTIAL) ?: return null
        return runCatching { json.decodeFromString<StoredSteamCredential>(stored).toDomain() }
            .getOrElse {
                sealed.remove(DeviceVaultKeys.STEAM_CREDENTIAL)
                null
            }
    }

    override suspend fun writeSteamCredential(credential: SteamCredential) {
        sealed.set(DeviceVaultKeys.STEAM_CREDENTIAL, json.encodeToString(StoredSteamCredential.from(credential)))
    }

    override suspend fun clearSteamCredential() {
        sealed.remove(DeviceVaultKeys.STEAM_CREDENTIAL)
    }

    companion object {
        const val DEFAULT_PREFS: String = "dmarket_p2p_tracker_vault"
    }
}
