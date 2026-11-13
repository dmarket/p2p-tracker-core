package com.dmarket.p2p.tracker.adapter

import android.os.SystemClock
import com.dmarket.p2p.tracker.port.host.ElapsedClock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/** Android: `SystemClock.elapsedRealtimeNanos()`, time since boot including deep sleep. */
actual fun platformElapsedClock(): ElapsedClock = ElapsedRealtimeClock

private object ElapsedRealtimeClock : ElapsedClock {
    override fun elapsed(): Duration = SystemClock.elapsedRealtimeNanos().nanoseconds
}
