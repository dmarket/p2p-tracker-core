package com.dmarket.p2p.tracker.port.host

/**
 * What the device's network costs the user right now, read before each cycle by the mobile proof gate. Unrelated
 * to [NetworkObserver], which only watches exchanges.
 *
 * Status reads and reports go on any network; a notary proof (~20 MB) goes on a metered one only with the user's
 * cellular opt-in or an explicit permission for one manual update.
 */
fun interface NetworkConditions {
    /** `true` on a metered network (cellular, a metered Wi-Fi, data saver on) and when there is no network at all. */
    fun metered(): Boolean

    companion object {
        /** Never metered: the gate stays open, as on the web. */
        val Unmetered: NetworkConditions = NetworkConditions { false }
    }
}
