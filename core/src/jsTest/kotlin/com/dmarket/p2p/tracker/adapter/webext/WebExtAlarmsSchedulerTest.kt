package com.dmarket.p2p.tracker.adapter.webext

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * [WebExtAlarmsScheduler] against an in-memory `chrome.alarms` shim on `globalThis` (Node has no
 * real chrome APIs). The shim records create/clear calls and can fire `onAlarm`.
 */
class WebExtAlarmsSchedulerTest {

    @BeforeTest
    fun installChromeMock() {
        js(
            """
            (function () {
                globalThis.chrome = {
                    alarms: {
                        created: [],
                        cleared: [],
                        _listeners: [],
                        create: function (name, opts) { this.created.push({ name: name, opts: opts }); },
                        clear: function (name) { this.cleared.push(name); },
                        onAlarm: {
                            addListener: function (cb) { globalThis.chrome.alarms._listeners.push(cb); },
                            removeListener: function (cb) {
                                var i = globalThis.chrome.alarms._listeners.indexOf(cb);
                                if (i >= 0) globalThis.chrome.alarms._listeners.splice(i, 1);
                            }
                        },
                        _fire: function (name) {
                            this._listeners.slice().forEach(function (cb) { cb({ name: name }); });
                        }
                    }
                };
            })()
            """,
        )
    }

    @Test
    fun schedule_creates_a_repeating_alarm_at_the_requested_period() {
        WebExtAlarmsScheduler().schedule(3.minutes)

        val created: dynamic = js("globalThis.chrome.alarms.created")
        assertEquals(1, created.length.unsafeCast<Int>())
        assertEquals(WebExtAlarmsScheduler.DEFAULT_ALARM_NAME, created[0].name.unsafeCast<String>())
        assertEquals(3.0, created[0].opts.periodInMinutes.unsafeCast<Double>())
    }

    @Test
    fun schedule_clamps_below_one_minute_up_to_the_mv3_floor() {
        WebExtAlarmsScheduler().schedule(30.seconds)

        val created: dynamic = js("globalThis.chrome.alarms.created")
        assertEquals(1.0, created[0].opts.periodInMinutes.unsafeCast<Double>())
    }

    @Test
    fun schedule_is_idempotent_for_the_same_period() {
        val scheduler = WebExtAlarmsScheduler()
        scheduler.schedule(3.minutes)
        scheduler.schedule(3.minutes)

        val created: dynamic = js("globalThis.chrome.alarms.created")
        assertEquals(1, created.length.unsafeCast<Int>())
    }

    @Test
    fun cancel_clears_the_alarm() {
        WebExtAlarmsScheduler().cancel()

        val cleared: dynamic = js("globalThis.chrome.alarms.cleared")
        assertEquals(WebExtAlarmsScheduler.DEFAULT_ALARM_NAME, cleared[0].unsafeCast<String>())
    }

    @Test
    fun ticks_emits_when_the_matching_alarm_fires() = runTest {
        val scheduler = WebExtAlarmsScheduler()
        val received = mutableListOf<Unit>()
        val job = launch { scheduler.ticks.collect { received += it } }
        testScheduler.runCurrent() // let the callbackFlow register its onAlarm listener

        js("globalThis.chrome.alarms._fire('other')") // non-matching name is ignored
        js("globalThis.chrome.alarms._fire('${WebExtAlarmsScheduler.DEFAULT_ALARM_NAME}')")
        testScheduler.advanceUntilIdle()

        assertEquals(1, received.size)
        job.cancel()
    }
}
