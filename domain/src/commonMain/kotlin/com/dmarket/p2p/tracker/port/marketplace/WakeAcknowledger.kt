package com.dmarket.p2p.tracker.port.marketplace

import com.dmarket.p2p.tracker.model.DeviceId
import com.dmarket.p2p.tracker.model.Wake

/**
 * Tells DMarket this device received a [Wake], before the sync it triggers. The mobile tracker sends it, not the app.
 *
 * **PROVISIONAL** — DMA-659 asks for an ack on receipt, but the contract does not define its shape yet, so nothing
 * here names an endpoint. Until it does the tracker runs the no-op one; the real one lands behind this port.
 * A throw counts as "not acknowledged" and never stops the sync.
 */
fun interface WakeAcknowledger {
    suspend fun acknowledge(deviceId: DeviceId, wake: Wake)
}
