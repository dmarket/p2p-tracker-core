package com.dmarket.p2p.tracker.port.marketplace

import com.dmarket.p2p.tracker.model.DeviceId
import com.dmarket.p2p.tracker.model.marketplace.DeviceRegistration

/**
 * The push device registry (contract d455888 §3.2), on the `/p2p/ext/` surface under DMarket auth; the account comes
 * from the token. Throws like [MarketplaceClient] does.
 */
interface DeviceRegistryClient {
    /** `PUT /exchange/v1/p2p/ext/devices/{deviceId}`: create or replace. */
    suspend fun registerDevice(deviceId: DeviceId, registration: DeviceRegistration)

    /** `DELETE /exchange/v1/p2p/ext/devices/{deviceId}`: an unknown id answers 204 too. */
    suspend fun unregisterDevice(deviceId: DeviceId)
}
