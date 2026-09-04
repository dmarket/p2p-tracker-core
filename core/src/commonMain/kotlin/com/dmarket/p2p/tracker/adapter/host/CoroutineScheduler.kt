package com.dmarket.p2p.tracker.adapter.host

import com.dmarket.p2p.tracker.port.host.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * [Scheduler] implementation that uses coroutine [delay] to fire the next tick.
 *
 * Suitable for tests and **foreground** use where the process stays alive. For background/web use it
 * is replaced by platform wake-up mechanisms — `WebExtAlarmsScheduler` (web), and WorkManager /
 * BGTaskScheduler on mobile — because pure `delay` does not survive MV3 service-worker teardown or
 * mobile background suspension. The foreground-vs-background choice is made by `platformScheduler`.
 *
 * @param scope The coroutine scope in which delay jobs are launched. Use the loop driver's scope
 *   in production, `TestScope` in tests (so `advanceTimeBy` works correctly).
 */
class CoroutineScheduler(private val scope: CoroutineScope) : Scheduler {
    private val _ticks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val ticks: Flow<Unit> = _ticks.asSharedFlow()

    private var pending: Job? = null

    override fun schedule(delay: Duration) {
        pending?.cancel()
        pending = scope.launch {
            delay(delay)
            _ticks.emit(Unit)
        }
    }

    override fun cancel() {
        pending?.cancel()
        pending = null
    }
}
