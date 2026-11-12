package com.dmarket.p2p.tracker.port.host

/**
 * Tells whether the state the core finds on the device was left by this install on this device. When it was not (a
 * fresh install, a reinstall, a backup restored onto another device), the mobile tracker erases what it finds (the
 * account state, the Steam credential, the device id) before anything reads it, and the next device id is a new one.
 *
 * **Android.** The sealed device id is the marker: it is sealed with a Keystore key that never leaves the device and
 * does not survive a restore or a transfer, so a device id that does not open means another install.
 *
 * **iOS** (DMA-715). Two marks, because the Keychain survives deleting the app and `UserDefaults` does not:
 *  - a flag in `UserDefaults`;
 *  - a marker in the Keychain, `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`, so it never reaches another iPhone.
 *
 * No flag while the core's Keychain items are there: the app was deleted and installed again; the core's Keychain
 * items are erased and a new device id is made. No Keychain marker while the flag is there: the app's data was
 * restored onto another iPhone; a new device id is made. [isSameInstall] is `false` in both cases and on a fresh
 * install; [markInstall] then sets both marks.
 */
interface InstallMarker {
    /** `true` when the core's state on this device belongs to this install on this device. */
    suspend fun isSameInstall(): Boolean

    /** Marks the current install as this one; called after the core erased what another install left. */
    suspend fun markInstall()
}
