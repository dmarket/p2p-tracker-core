package com.dmarket.p2p.tracker.adapter

import com.dmarket.p2p.tracker.adapter.host.CoroutineScheduler
import com.dmarket.p2p.tracker.model.TrackerMode
import com.dmarket.p2p.tracker.port.host.Scheduler
import kotlinx.coroutines.CoroutineScope

/**
 * Android: an in-process [CoroutineScheduler] in either mode. The Android tracker (`P2pTracker` via
 * `AndroidTracker`) schedules no background wake at all: in the background a push runs the core's
 * `P2pTrackerWorker`, and only the app on screen polls.
 */
actual fun platformScheduler(scope: CoroutineScope, mode: TrackerMode): Scheduler = CoroutineScheduler(scope)
