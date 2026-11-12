package com.dmarket.p2p.tracker.adapter

import com.dmarket.p2p.tracker.credential.steam.DeviceVaultKeys
import com.dmarket.p2p.tracker.port.host.DeviceKeyValueStore
import com.dmarket.p2p.tracker.port.host.InstallMarker

/**
 * Android [InstallMarker]: the device id sealed with the Keystore key is the marker. The key never leaves the device
 * and does not survive a restore or a transfer, so a sealed id that does not open (the [sealed] store erases it and
 * reads it as absent) means another install. The id the tracker makes next is the new mark, so [markInstall] does
 * nothing.
 */
class KeystoreInstallMarker(private val sealed: DeviceKeyValueStore) : InstallMarker {
    override suspend fun isSameInstall(): Boolean = sealed.get(DeviceVaultKeys.DEVICE_ID) != null

    override suspend fun markInstall() = Unit
}
