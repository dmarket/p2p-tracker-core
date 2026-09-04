package com.dmarket.p2p.tracker.adapter.host

import com.dmarket.p2p.tracker.model.PushSignal
import com.dmarket.p2p.tracker.port.host.PushChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The default [PushChannel]: no transport wired. [signals] never emits and [register] is a no-op,
 * so a [com.dmarket.p2p.tracker.loop.TradeTrackerLoop] using it behaves exactly as a poll-only loop.
 *
 * This is the standing default until a platform push transport (APNs on iOS, FCM on Android, or a
 * web push transport — **not Centrifugo**) is implemented against the finalized backend contract and
 * passed to [com.dmarket.p2p.tracker.runtime.TradeTrackerCore.createLoop].
 */
object NoOpPushChannel : PushChannel {
    override val signals: Flow<PushSignal> = emptyFlow()

    override suspend fun register(deviceToken: String) { /* no-op: no transport to register with */ }
}
