package com.dmarket.p2p.tracker.port.host

import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

/**
 * Platform wake-up scheduling: `chrome.alarms` (web), WorkManager (Android), BGTaskScheduler (iOS).
 * The engine decides the delay (via the cadence policy); this port just realises it and emits a tick
 * when the platform wakes the client.
 *
 * Which concrete implementation runs is chosen by `platformScheduler(scope, mode)` (in `:core`'s
 * `adapter` package) — the single cross-platform selection mechanism (foreground → in-process
 * `CoroutineScheduler`; background → the OS wake-up survivor).
 */
interface Scheduler {
    /** Request the next wake-up after [delay]. */
    fun schedule(delay: Duration)

    /** Cancel any pending wake-up. */
    fun cancel()

    /** Emits once each time the platform wakes the client for a scheduled tick. */
    val ticks: Flow<Unit>
}
