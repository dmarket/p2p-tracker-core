// PHASE 3 SCAFFOLD — reference only, NOT compiled until the iOS targets are enabled in
// build-logic/.../dmarket.kmp.library.gradle.kts (iosArm64()/iosSimulatorArm64()/iosX64()). KMP ignores
// this source set until then; it is linted by spotless but not type-checked.
package com.dmarket.p2p.tracker.adapter

import com.dmarket.p2p.tracker.adapter.host.CoroutineScheduler
import com.dmarket.p2p.tracker.model.TrackerMode
import com.dmarket.p2p.tracker.port.host.Scheduler
import kotlinx.coroutines.CoroutineScope

/**
 * iOS: an in-process [CoroutineScheduler] in either mode, as on Android. The mobile tracker (`P2pTracker`) never arms
 * the scheduler: on screen it polls, and in the background only a forwarded APNs wake runs a cycle.
 */
actual fun platformScheduler(scope: CoroutineScope, mode: TrackerMode): Scheduler = CoroutineScheduler(scope)
