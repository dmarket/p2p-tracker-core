package com.dmarket.p2p.tracker.runtime

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dmarket.p2p.tracker.model.WakeOutcome
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Runs one wake: [P2pTracker.onPush] with the push payload it was enqueued with, within [WAKE_DEADLINE] — the ack, the
 * sync, the final ack. The app enqueues it from its push service ([enqueue]) and has no Worker of its own. The
 * [WakeOutcome] goes in the result's output data ([KEY_OUTCOME], [KEY_CAUSE]); none for a payload that is no wake.
 *
 * Unique work with [ExistingWorkPolicy.APPEND_OR_REPLACE]: wakes queue behind each other, so one runs at a time, and a
 * failed or cancelled one does not hold back the next. The tracker's own lock serialises the Worker against the
 * app's foreground polling.
 *
 * A process WorkManager starts for this Worker runs `Application.onCreate` first; the app starts the tracker there
 * ([startTracker]) when a user is logged in. Without a started tracker the wake is dropped.
 */
class P2pTrackerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val payload = inputData.getString(KEY_PAYLOAD) ?: return Result.success()
        val outcome = AndroidTracker.tracker?.onPush(payload, WAKE_DEADLINE) ?: return Result.success()
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

        /** Enqueues one wake with the push's data [payload]. */
        fun enqueue(context: Context, payload: String) {
            val request = OneTimeWorkRequestBuilder<P2pTrackerWorker>().setInputData(workDataOf(KEY_PAYLOAD to payload)).build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
