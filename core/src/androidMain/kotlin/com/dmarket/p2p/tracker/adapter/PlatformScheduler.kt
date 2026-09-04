// PHASE 3 SCAFFOLD — reference only, NOT compiled until the Android target is enabled in
// build-logic/.../dmarket.kmp.library.gradle.kts (the `androidTarget { ... }` block). KMP ignores this
// source set until then; it is linted by spotless but not type-checked.
package com.dmarket.p2p.tracker.adapter

import androidx.work.WorkManager
import com.dmarket.p2p.tracker.model.TrackerMode
import com.dmarket.p2p.tracker.port.host.Scheduler
import kotlinx.coroutines.CoroutineScope

/**
 * Android selection: foreground (app on screen) stays alive → in-process [CoroutineScheduler];
 * background → [WorkManagerScheduler], which survives process death and Doze.
 *
 * [WorkManager] needs an application context; the host stashes the instance at startup (see
 * [WorkManagerScheduler]) and [AndroidWorkManagerHolder.instance] returns it.
 */
actual fun platformScheduler(scope: CoroutineScope, mode: TrackerMode): Scheduler = when (mode) {
    TrackerMode.Foreground -> CoroutineScheduler(scope)
    TrackerMode.Background -> WorkManagerScheduler(AndroidWorkManagerHolder.instance)
}

/**
 * Captured once by the host via an `androidx.startup` `Initializer`:
 * `AndroidWorkManagerHolder.instance = WorkManager.getInstance(context)`. Kept as a tiny holder so the
 * [platformScheduler] actual stays free of Android `Context` plumbing.
 */
object AndroidWorkManagerHolder {
    lateinit var instance: WorkManager
}
