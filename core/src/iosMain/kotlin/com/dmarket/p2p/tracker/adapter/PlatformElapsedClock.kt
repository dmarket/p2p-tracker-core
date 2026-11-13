// PHASE 3 SCAFFOLD — reference only, NOT compiled until the iOS targets are enabled in
// build-logic/.../dmarket.kmp.library.gradle.kts (iosArm64()/iosSimulatorArm64()/iosX64()). KMP ignores
// this source set until then; it is linted by spotless but not type-checked.
package com.dmarket.p2p.tracker.adapter

import com.dmarket.p2p.tracker.port.host.ElapsedClock
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.darwin.mach_continuous_time
import platform.darwin.mach_timebase_info
import platform.darwin.mach_timebase_info_data_t
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/** iOS: `mach_continuous_time`, which keeps counting while the device sleeps (`mach_absolute_time` does not). */
actual fun platformElapsedClock(): ElapsedClock = ContinuousClock

@OptIn(ExperimentalForeignApi::class)
private object ContinuousClock : ElapsedClock {
    /** Ticks to nanoseconds: `numer / denom` (1/1 on Intel, 125/3 on Apple silicon). */
    private val timebase: Pair<Long, Long> = memScoped {
        val info = alloc<mach_timebase_info_data_t>()
        mach_timebase_info(info.ptr)
        info.numer.toLong() to info.denom.toLong()
    }

    override fun elapsed(): Duration = (mach_continuous_time().toLong() * timebase.first / timebase.second).nanoseconds
}
