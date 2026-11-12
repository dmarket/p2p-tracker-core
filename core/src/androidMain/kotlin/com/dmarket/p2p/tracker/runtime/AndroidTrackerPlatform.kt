package com.dmarket.p2p.tracker.runtime

import android.content.Context
import android.content.SharedPreferences
import com.dmarket.p2p.tracker.adapter.AndroidCredentialVault
import com.dmarket.p2p.tracker.adapter.ConnectivityNetworkConditions
import com.dmarket.p2p.tracker.adapter.KeystoreAesGcmCipher
import com.dmarket.p2p.tracker.adapter.KeystoreInstallMarker
import com.dmarket.p2p.tracker.adapter.SealedKeyValueStore
import com.dmarket.p2p.tracker.adapter.SecretCipher
import com.dmarket.p2p.tracker.adapter.SharedPreferencesKeyValueStore
import com.dmarket.p2p.tracker.adapter.host.SystemClock
import com.dmarket.p2p.tracker.client.platformHttpEngine
import com.dmarket.p2p.tracker.credential.marketplace.MarketplaceTokenSupplier
import com.dmarket.p2p.tracker.model.RuntimeSurface
import com.dmarket.p2p.tracker.port.host.Clock
import com.dmarket.p2p.tracker.port.host.CredentialVault
import com.dmarket.p2p.tracker.port.host.DeviceKeyValueStore
import com.dmarket.p2p.tracker.port.host.InstallMarker
import com.dmarket.p2p.tracker.port.host.NetworkConditions
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * The Android adapters of the mobile tracker; the install and account rules over them are [MobileTrackerPlatform]'s.
 *
 * **Files.** Two private `SharedPreferences` files, fixed names so the app's `dataExtractionRules` can list them
 * (README): [INSTALL_FILE] (the sealed device id and Steam credential, and which account the state belongs to) and
 * [ACCOUNT_FILE] (that account's state). Only the install file holds secrets, and only sealed with the Keystore key;
 * the account file holds no secret.
 *
 * **Install scope.** The device id and the Steam credential are sealed with a Keystore key, which is never backed up
 * and never moves to another device ([KeystoreInstallMarker]). On a restored or transferred install neither opens and
 * the state that came along with them is erased. This holds even if the app's `dataExtractionRules` let the files
 * through; those rules (README) keep them off the backup in the first place.
 *
 * @param tokens the host's DMarket token layer (on the DMarket app, `TokenManager.getTokenData(forceRefresh)`).
 * @param httpEngine the HTTP engine for DMarket and Steam; `null` takes the platform's (OkHttp).
 * @param network what the network costs; reading it needs `ACCESS_NETWORK_STATE`.
 */
class AndroidTrackerPlatform internal constructor(
    prefs: (String) -> SharedPreferences,
    cipher: SecretCipher,
    tokens: MarketplaceTokenSupplier,
    override val clock: Clock = SystemClock(),
    val httpEngine: HttpClientEngine? = null,
    io: CoroutineDispatcher = Dispatchers.IO,
    override val network: NetworkConditions = NetworkConditions.Unmetered,
) : MobileTrackerPlatform(tokens) {
    constructor(
        context: Context,
        tokens: MarketplaceTokenSupplier,
        clock: Clock = SystemClock(),
        httpEngine: HttpClientEngine? = null,
    ) : this(
        prefs = { name -> context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE) },
        cipher = KeystoreAesGcmCipher(),
        tokens = tokens,
        clock = clock,
        httpEngine = httpEngine,
        network = ConnectivityNetworkConditions(context),
    )

    private val install = SharedPreferencesKeyValueStore(prefs(INSTALL_FILE), io)
    private val accountFile = SharedPreferencesKeyValueStore(prefs(ACCOUNT_FILE), io)

    override val surface: RuntimeSurface = RuntimeSurface.AndroidNative
    override val installStore: DeviceKeyValueStore = install
    override val sealedStore: DeviceKeyValueStore = SealedKeyValueStore(install, cipher)
    override val sealedVault: CredentialVault = AndroidCredentialVault(sealedStore)
    override val accountStore: DeviceKeyValueStore = accountFile
    override val installMarker: InstallMarker = KeystoreInstallMarker(sealedStore)

    override fun dmarketEngine(): HttpClientEngine = httpEngine ?: platformHttpEngine()

    override fun steamEngine(): HttpClientEngine = httpEngine ?: platformHttpEngine(credentialsInclude = true)

    override suspend fun clearAccountStore() = accountFile.clear()

    companion object {
        /** The install file: the sealed device id and Steam credential, and whose state [ACCOUNT_FILE] holds. */
        const val INSTALL_FILE: String = "dmarket_p2p_tracker_install"

        /** The current account's state; emptied when the account changes and on logout. */
        const val ACCOUNT_FILE: String = "dmarket_p2p_tracker_account"
    }
}
