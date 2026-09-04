package com.dmarket.p2p.tracker.adapter.host

import com.dmarket.p2p.tracker.model.NetworkExchange
import com.dmarket.p2p.tracker.port.host.NetworkObserver

/**
 * The default [NetworkObserver]: discards every exchange. Wiring sites check for this instance to skip
 * installing the observation plugin entirely, so production has zero observability overhead.
 */
object NoOpNetworkObserver : NetworkObserver {
    override suspend fun onExchange(exchange: NetworkExchange) = Unit
}
