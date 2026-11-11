package com.dmarket.p2p.tracker.runtime

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf

/**
 * Runs one wake: [P2pTracker.onPush] with the push payload it was enqueued with — the ack, then the sync. The app
 * enqueues it from its push service ([enqueue]) and has no Worker of its own.
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
        AndroidTracker.tracker?.onPush(payload)
        return Result.success()
    }

    companion object {
        /** The unique work every wake is enqueued under. */
        const val UNIQUE_WORK: String = "dmarket_p2p_tracker_wake"

        /** The input key of the push payload (the raw data JSON). */
        const val KEY_PAYLOAD: String = "payload"

        /** Enqueues one wake with the push's data [payload]. */
        fun enqueue(context: Context, payload: String) {
            val request = OneTimeWorkRequestBuilder<P2pTrackerWorker>().setInputData(workDataOf(KEY_PAYLOAD to payload)).build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
