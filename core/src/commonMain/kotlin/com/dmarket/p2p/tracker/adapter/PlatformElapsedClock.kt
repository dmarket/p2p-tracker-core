package com.dmarket.p2p.tracker.adapter

import com.dmarket.p2p.tracker.port.host.ElapsedClock
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * The platform's [ElapsedClock]: Android `SystemClock.elapsedRealtimeNanos()`, iOS `mach_continuous_time`, both of
 * which count deep sleep. JVM and JS have no device sleep to count and get [MonotonicElapsedClock].
 *
 * [TimeSource.Monotonic] is not used on Android or iOS: on Android it is `System.nanoTime()` (`CLOCK_MONOTONIC`), on
 * iOS a clock based on `mach_absolute_time`, and both stop while the device sleeps.
 */
expect fun platformElapsedClock(): ElapsedClock

/** [TimeSource.Monotonic] from this object's creation. Counts sleep only where the host's monotonic clock does. */
class MonotonicElapsedClock : ElapsedClock {
    private val origin = TimeSource.Monotonic.markNow()

    override fun elapsed(): Duration = origin.elapsedNow()
}
