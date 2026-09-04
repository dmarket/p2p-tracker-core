package com.dmarket.p2p.tracker.adapter

import com.dmarket.p2p.tracker.adapter.host.CoroutineScheduler
import com.dmarket.p2p.tracker.model.TrackerMode
import com.dmarket.p2p.tracker.port.host.Scheduler
import kotlinx.coroutines.CoroutineScope

/**
 * The single place that **decides which [Scheduler] to use** — the cross-platform selection
 * mechanism. The platform axis is resolved by which `actual` compiles (web / JVM / iOS / Android);
 * the runtime axis is [mode]:
 *
 * - [TrackerMode.Foreground] → an in-process [CoroutineScheduler]: the process is alive, so a plain
 *   coroutine `delay` is enough and honours the exact (sub-floor) delay the engine asks for.
 * - [TrackerMode.Background] → the platform's OS wake-up survivor (`WebExtAlarmsScheduler` on web,
 *   WorkManager on Android, BGTaskScheduler on iOS): survives process / service-worker teardown, but
 *   the OS floors and coalesces it (chrome ~60s, WorkManager ~15min periodic, BGTask OS-decided).
 *
 * Either way [Scheduler.schedule] is a *request* the OS may coalesce or delay — the same caveat
 * `CadencePolicy` already clamps for. [scope] is used only by the foreground [CoroutineScheduler];
 * background actuals ignore it.
 */
expect fun platformScheduler(scope: CoroutineScope, mode: TrackerMode): Scheduler
