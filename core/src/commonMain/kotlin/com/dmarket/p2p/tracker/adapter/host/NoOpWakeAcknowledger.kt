package com.dmarket.p2p.tracker.adapter.host

import com.dmarket.p2p.tracker.model.DeviceId
import com.dmarket.p2p.tracker.model.WakeOutcome
import com.dmarket.p2p.tracker.port.marketplace.WakeAcknowledger

/** A [WakeAcknowledger] that sends nothing: a tracker built without a DMarket client that acks (tests, a host's own). */
object NoOpWakeAcknowledger : WakeAcknowledger {
    override suspend fun acknowledge(deviceId: DeviceId, wakeId: String, outcome: WakeOutcome) = Unit
}
