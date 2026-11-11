package com.dmarket.p2p.tracker.adapter.host

import com.dmarket.p2p.tracker.model.DeviceId
import com.dmarket.p2p.tracker.model.Wake
import com.dmarket.p2p.tracker.port.marketplace.WakeAcknowledger

/**
 * The [WakeAcknowledger] until DMA-659 defines the ack: sends nothing, and the wake counts as acknowledged.
 * Provisional, like the port.
 */
object NoOpWakeAcknowledger : WakeAcknowledger {
    override suspend fun acknowledge(deviceId: DeviceId, wake: Wake) { /* no contract yet: nothing to send */ }
}
