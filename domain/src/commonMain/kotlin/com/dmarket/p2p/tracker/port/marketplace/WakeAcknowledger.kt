package com.dmarket.p2p.tracker.port.marketplace

import com.dmarket.p2p.tracker.model.DeviceId
import com.dmarket.p2p.tracker.model.WakeOutcome

/**
 * The wake ack, `POST /exchange/v1/p2p/ext/wake-ack` (contract d455888 §6.4). The mobile tracker sends it, not the app:
 * `done` on receipt ("the work started"), then the final outcome under the same `wakeId`; the last one wins.
 * A throw counts as "not acknowledged" and never stops the sync.
 */
fun interface WakeAcknowledger {
    suspend fun acknowledge(deviceId: DeviceId, wakeId: String, outcome: WakeOutcome)
}
