// PHASE 3 SCAFFOLD — reference only, NOT compiled until the iOS targets are enabled in
// build-logic/.../dmarket.kmp.library.gradle.kts (iosArm64()/iosSimulatorArm64()/iosX64()). KMP ignores
// this source set until then; it is linted by spotless but not type-checked.
package com.dmarket.p2p.tracker.adapter

import com.dmarket.p2p.tracker.model.TrackerMode
import com.dmarket.p2p.tracker.port.host.Scheduler
import kotlinx.coroutines.CoroutineScope

/**
 * iOS selection: foreground (app active) stays alive → in-process [CoroutineScheduler]; background →
 * [BgTaskScheduler], which survives app suspension via `BGTaskScheduler`. The host must register the
 * task identifier at launch (see [BgTaskScheduler]) and route the launch handler to its `fireTick()`.
 */
actual fun platformScheduler(scope: CoroutineScope, mode: TrackerMode): Scheduler = when (mode) {
    TrackerMode.Foreground -> CoroutineScheduler(scope)
    TrackerMode.Background -> BgTaskScheduler()
}
