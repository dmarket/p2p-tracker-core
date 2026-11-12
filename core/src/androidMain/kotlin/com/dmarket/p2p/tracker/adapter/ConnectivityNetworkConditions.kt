package com.dmarket.p2p.tracker.adapter

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.dmarket.p2p.tracker.port.host.NetworkConditions

/**
 * [NetworkConditions] from `ConnectivityManager`: unmetered only when the active network has
 * `NET_CAPABILITY_NOT_METERED` and data saver does not restrict this app. No active network, or no answer (the app
 * lacks `ACCESS_NETWORK_STATE`), reads as metered.
 */
class ConnectivityNetworkConditions(context: Context) : NetworkConditions {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun metered(): Boolean = try {
        val capabilities = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
        val unmetered = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        !unmetered || connectivity.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
    } catch (_: SecurityException) {
        true
    }
}
