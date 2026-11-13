package com.dmarket.p2p.tracker.port.host

import kotlin.time.Duration

/**
 * Time since a fixed origin that keeps running while the device sleeps (Android `elapsedRealtime`, iOS
 * `mach_continuous_time`). The origin means nothing; only the difference of two readings does. Unlike [Clock] it
 * cannot be set, so a wall clock the user or the network moves does not move it.
 */
interface ElapsedClock {
    fun elapsed(): Duration
}
