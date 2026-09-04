package com.dmarket.p2p.tracker.adapter.webext

import com.dmarket.p2p.tracker.port.host.Scheduler
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.time.Duration

/**
 * [Scheduler] backed by `chrome.alarms` (Chrome MV3) / `browser.alarms` (Firefox).
 *
 * `chrome.alarms` is the only web wake-up that **survives MV3 service-worker teardown** — the
 * browser persists the alarm and respawns the dead worker to deliver `onAlarm`, where a plain
 * coroutine `delay` (see [CoroutineScheduler]) would die with the worker after ~30 s idle. This is
 * what `platformScheduler(scope, Background)` returns on web.
 *
 * [schedule] creates a **repeating** alarm (self-healing: a thrown cycle never stops future wakes),
 * clamped to the MV3 1-minute floor — which matches `CadencePolicy.pollFloor(WebChrome)`. The
 * actual per-wake work is driven by a **top-level** `onAlarm` listener registered by the web
 * `startTracker` driver, because a flow collected here would not survive a worker respawn; [ticks]
 * is provided for completeness (foreground `start()` use) and registers its listener lazily.
 *
 * Requires the `"alarms"` permission in `manifest.json`.
 */
class WebExtAlarmsScheduler(private val alarmName: String = DEFAULT_ALARM_NAME) : Scheduler {

    /** The period currently armed, so a same-cadence reschedule is a no-op instead of churn. */
    private var currentPeriodMinutes: Double? = null

    override fun schedule(delay: Duration) {
        val minutes = maxOf(delay.inWholeMilliseconds / MILLIS_PER_MINUTE, MIN_PERIOD_MINUTES)
        if (minutes == currentPeriodMinutes) return
        currentPeriodMinutes = minutes
        val opts: dynamic = js("({})")
        opts.periodInMinutes = minutes
        opts.delayInMinutes = minutes
        alarms().create(alarmName, opts)
    }

    override fun cancel() {
        currentPeriodMinutes = null
        alarms().clear(alarmName)
    }

    override val ticks: Flow<Unit> = callbackFlow {
        val area = alarms()
        // Convert the listener to a JS function exactly once so add/remove share one reference.
        val cb: dynamic = { alarm: dynamic ->
            if (alarm.name == alarmName) {
                trySend(Unit)
            }
        }
        area.onAlarm.addListener(cb)
        awaitClose { area.onAlarm.removeListener(cb) }
    }

    /** Returns `chrome.alarms` (Chrome) or `browser.alarms` (Firefox). */
    private fun alarms(): dynamic {
        val ns: dynamic = js("typeof chrome !== 'undefined' ? chrome : browser")
        return ns.alarms
    }

    companion object {
        const val DEFAULT_ALARM_NAME: String = "dmarket_p2p_tracker_tick"
        private const val MILLIS_PER_MINUTE: Double = 60_000.0

        /** MV3 floors a periodic alarm at 1 minute; asking for less is silently clamped by Chrome. */
        private const val MIN_PERIOD_MINUTES: Double = 1.0
    }
}
