package com.dmarket.p2p.tracker.support

import com.dmarket.p2p.tracker.port.host.Clock
import kotlin.time.Instant

/** A clock pinned to a fixed instant, so engine inputs are deterministic. */
class FixedClock(private val instant: Instant = T0) : Clock {
    override fun now(): Instant = instant
}
