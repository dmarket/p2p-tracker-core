package com.dmarket.p2p.tracker.adapter

import com.dmarket.p2p.tracker.adapter.host.CoroutineScheduler
import com.dmarket.p2p.tracker.adapter.webext.WebExtAlarmsScheduler
import com.dmarket.p2p.tracker.model.TrackerMode
import kotlinx.coroutines.GlobalScope
import kotlin.test.Test
import kotlin.test.assertTrue

class PlatformSchedulerTest {

    @Test
    fun foreground_picks_in_process_coroutine_scheduler() {
        @Suppress("OPT_IN_USAGE")
        val scheduler = platformScheduler(GlobalScope, TrackerMode.Foreground)
        assertTrue(scheduler is CoroutineScheduler, "foreground must use the in-process CoroutineScheduler")
    }

    @Test
    fun background_picks_chrome_alarms_scheduler() {
        @Suppress("OPT_IN_USAGE")
        val scheduler = platformScheduler(GlobalScope, TrackerMode.Background)
        assertTrue(scheduler is WebExtAlarmsScheduler, "background must use the teardown-surviving WebExtAlarmsScheduler")
    }
}
