// PHASE 3 SCAFFOLD — reference only, NOT compiled until the Android target is enabled in
// build-logic/.../dmarket.kmp.library.gradle.kts (the `androidTarget { ... }` block). KMP ignores this
// source set until then; it is linted by spotless but not type-checked. Finalize against a real Android
// build. It implements ONLY the Scheduler port — the cadence/decision logic stays shared in commonMain
// (CadencePolicy + TradeTrackerLoop), so mobile and web stay consistent.
package com.dmarket.p2p.tracker.adapter

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.dmarket.p2p.tracker.port.host.Scheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/**
 * Android [Scheduler] backed by WorkManager — the wake-up that survives process death and Doze on
 * Android (a plain coroutine `delay`, see [CoroutineScheduler], dies with the process).
 *
 * [schedule] enqueues a **unique** one-time [SchedulerWorker] (replacing any pending one, so a re-arm
 * never stacks) with an initial delay. The worker re-emits onto [ticks] when WorkManager runs it; the
 * loop driver then runs a cycle and calls [schedule] again for the next wake — the same self-rearming
 * feedback loop the web `startTracker` runs with `chrome.alarms`.
 *
 * **Floor:** WorkManager batches background work; a one-time request's delay is a *minimum*, and
 * periodic work floors at ~15 minutes — the Android analogue of Chrome's ~60s alarm floor, already
 * accounted for by `CadencePolicy` being the authoritative-but-clamped source.
 *
 * **Context:** WorkManager needs an application [Context]. The host provides it once via an
 * `androidx.startup` `Initializer` that stashes `WorkManager.getInstance(context)` (kept out of this
 * scaffold to avoid a hard dependency on app wiring); [workManager] is that instance.
 */
class WorkManagerScheduler(private val workManager: WorkManager, private val uniqueName: String = DEFAULT_WORK_NAME) : Scheduler {

    override fun schedule(delay: Duration) {
        val request = OneTimeWorkRequestBuilder<SchedulerWorker>()
            .setInitialDelay(delay.inWholeMilliseconds.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .build()
        // REPLACE = single pending wake, mirroring the single named chrome.alarms entry.
        workManager.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancel() {
        workManager.cancelUniqueWork(uniqueName)
    }

    override val ticks: Flow<Unit> = ticksFlow.asSharedFlow()

    /** Runs in the WorkManager thread pool on each scheduled wake; pings [ticks] for the loop driver. */
    class SchedulerWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
        override fun doWork(): Result {
            ticksFlow.tryEmit(Unit)
            return Result.success()
        }
    }

    companion object {
        const val DEFAULT_WORK_NAME: String = "dmarket_p2p_tracker_tick"

        // Process-wide doorbell: the Worker is instantiated by WorkManager, so it can't hold a
        // back-reference to the scheduler instance — they meet on this shared flow.
        private val ticksFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    }
}
