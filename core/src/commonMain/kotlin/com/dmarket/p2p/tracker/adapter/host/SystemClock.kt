package com.dmarket.p2p.tracker.adapter.host

import com.dmarket.p2p.tracker.port.host.Clock
import kotlin.time.Instant

/**
 * [Clock] implementation that delegates to [kotlin.time.Clock.System].
 *
 * Works in commonMain on all targets — the Kotlin stdlib provides a multiplatform
 * `Clock.System.now()` without requiring `expect`/`actual`.
 */
class SystemClock : Clock {
    override fun now(): Instant = kotlin.time.Clock.System.now()
}
