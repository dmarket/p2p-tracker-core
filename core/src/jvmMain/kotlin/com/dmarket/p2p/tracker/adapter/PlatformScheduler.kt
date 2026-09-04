package com.dmarket.p2p.tracker.adapter

import com.dmarket.p2p.tracker.adapter.host.CoroutineScheduler
import com.dmarket.p2p.tracker.model.TrackerMode
import com.dmarket.p2p.tracker.port.host.Scheduler
import kotlinx.coroutines.CoroutineScope

/**
 * JVM has no OS background wake-up mechanism (it's used for tests and foreground/manual composition),
 * so both modes resolve to the in-process [CoroutineScheduler]. [mode] is advisory here.
 */
actual fun platformScheduler(scope: CoroutineScope, mode: TrackerMode): Scheduler = CoroutineScheduler(scope)
