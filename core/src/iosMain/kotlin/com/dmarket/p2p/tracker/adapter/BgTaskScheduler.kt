// PHASE 3 SCAFFOLD — reference only, NOT compiled until the iOS targets are enabled in
// build-logic/.../dmarket.kmp.library.gradle.kts (iosArm64()/iosSimulatorArm64()/iosX64()). KMP ignores
// this source set until then; it is linted by spotless but not type-checked, and can only be built on a
// macOS CI runner with full Xcode. Finalize the cinterop details there. It implements ONLY the Scheduler
// port — the cadence/decision logic stays shared in commonMain (CadencePolicy + TradeTrackerLoop), so
// iOS and web stay consistent.
package com.dmarket.p2p.tracker.adapter

import com.dmarket.p2p.tracker.port.host.Scheduler
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSinceNow
import kotlin.time.Duration

/**
 * iOS [Scheduler] backed by `BGTaskScheduler` — the background wake-up that survives app suspension
 * (a plain coroutine `delay`, see [CoroutineScheduler], dies when the app is suspended).
 *
 * [schedule] submits a [BGAppRefreshTaskRequest] for [taskIdentifier] with `earliestBeginDate =
 * now + delay`. When iOS later launches the task, the host's registered handler calls [fireTick],
 * which pings [ticks]; the loop driver runs a cycle and calls [schedule] again to re-arm — the same
 * self-rearming feedback loop the web `startTracker` runs with `chrome.alarms`.
 *
 * **Host setup (one-time, at launch):** add [taskIdentifier] to `BGTaskSchedulerPermittedIdentifiers`
 * in Info.plist and register a handler in `application(_:didFinishLaunchingWithOptions:)`:
 * `BGTaskScheduler.shared.register(forTaskWithIdentifier: id) { task in scheduler.fireTick(); task.setTaskCompleted(success: true) }`.
 *
 * **Floor:** `earliestBeginDate` is only a hint; iOS decides the actual time (often coalesced to
 * minutes/hours by energy/usage heuristics) — the iOS analogue of Chrome's ~60s alarm floor, already
 * accounted for by `CadencePolicy` being authoritative-but-clamped.
 */
@OptIn(ExperimentalForeignApi::class)
class BgTaskScheduler(
    private val taskIdentifier: String = DEFAULT_TASK_IDENTIFIER,
    private val scheduler: BGTaskScheduler = BGTaskScheduler.sharedScheduler,
) : Scheduler {

    private val _ticks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val ticks: Flow<Unit> = _ticks.asSharedFlow()

    override fun schedule(delay: Duration) {
        val request = BGAppRefreshTaskRequest(taskIdentifier)
        request.earliestBeginDate = NSDate.dateWithTimeIntervalSinceNow(delay.inWholeMilliseconds / 1000.0)
        scheduler.cancelTaskRequestWithIdentifier(taskIdentifier) // single pending wake — replace, never stack
        scheduler.submitTaskRequest(request, error = null)
    }

    override fun cancel() {
        scheduler.cancelTaskRequestWithIdentifier(taskIdentifier)
    }

    /** Called by the host's `BGTaskScheduler.register` handler when iOS launches the task. */
    fun fireTick() {
        _ticks.tryEmit(Unit)
    }

    companion object {
        const val DEFAULT_TASK_IDENTIFIER: String = "com.dmarket.p2p.tracker.tick"
    }
}
