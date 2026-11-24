package com.dmarket.p2p.tracker.runtime

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dmarket.p2p.tracker.model.WakeCause
import com.dmarket.p2p.tracker.model.WakeOutcome
import com.dmarket.p2p.tracker.wire.parsePushEnvelope
import com.dmarket.p2p.tracker.wire.toWakeOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * Runs one wake: [P2pTracker.onPush] with the push payload it was enqueued with, within [WAKE_DEADLINE] — the ack, the
 * sync, the final ack. The app enqueues it from its push service ([enqueue]) and has no Worker of its own. The
 * [WakeOutcome] goes in the result's output data ([KEY_OUTCOME], [KEY_CAUSE]); none for a payload that is no wake.
 *
 * Unique work with [ExistingWorkPolicy.APPEND_OR_REPLACE]: wakes queue behind each other, so one runs at a time, and a
 * failed or cancelled one does not hold back the next. The tracker's own lock serialises the Worker against the
 * app's foreground polling.
 *
 * A process WorkManager starts for this Worker runs `Application.onCreate` first, and the app builds the tracker
 * ([startTracker]) from there, often after the Worker has started: a wake waits for it up to [HOST_WAIT] ([runWake]).
 * It waits in place rather than returning `Result.retry()`: a retry goes back under its backoff and constraints, holds
 * every wake queued behind it, and leaves no outcome to read.
 */
class P2pTrackerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val payload = inputData.getString(KEY_PAYLOAD) ?: return Result.success()
        val outcome = runWake(payload, AndroidTracker.trackers) ?: return Result.success()
        return Result.success(workDataOf(KEY_OUTCOME to outcome.kind.wireName, KEY_CAUSE to outcome.cause?.wireName))
    }

    companion object {
        /** The unique work every wake is enqueued under. */
        const val UNIQUE_WORK: String = "dmarket_p2p_tracker_wake"

        /** The input key of the push payload (the raw data JSON). */
        const val KEY_PAYLOAD: String = "payload"

        /** The output key of the wake's outcome: `done` | `deferred` | `failed`. */
        const val KEY_OUTCOME: String = "outcome"

        /** The output key of a failed or deferred wake's cause, as the ack spells it. */
        const val KEY_CAUSE: String = "cause"

        /** WorkManager stops a Worker after 10 minutes; one is left for the final ack. */
        val WAKE_DEADLINE: Duration = 9.minutes

        /**
         * How long a wake waits for the app to build the tracker; the wait comes out of [WAKE_DEADLINE]. Every wake
         * queued behind waits it in turn when the app builds none (logged out, the app's gate closed).
         */
        val HOST_WAIT: Duration = 2.minutes

        /** Enqueues one wake with the push's data [payload]. */
        fun enqueue(context: Context, payload: String) {
            val request = OneTimeWorkRequestBuilder<P2pTrackerWorker>().setInputData(workDataOf(KEY_PAYLOAD to payload)).build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}

/**
 * The wake in [payload] on the first tracker [trackers] gives within [hostWait], with what is left of [deadline];
 * `null` for a payload that is no wake, at once. No tracker in time ends `failed` / `other` with no ack: only a tracker
 * sends one.
 */
internal suspend fun runWake(
    payload: String,
    trackers: Flow<P2pTracker?>,
    hostWait: Duration = P2pTrackerWorker.HOST_WAIT,
    deadline: Duration = P2pTrackerWorker.WAKE_DEADLINE,
    time: TimeSource = TimeSource.Monotonic,
): WakeOutcome? {
    parsePushEnvelope(payload)?.toWakeOrNull() ?: return null
    val started = time.markNow()
    val tracker = withTimeoutOrNull(hostWait) { trackers.filterNotNull().first() } ?: return WakeOutcome.failed(WakeCause.OTHER)
    return tracker.onPush(payload, deadline - started.elapsedNow())
}
