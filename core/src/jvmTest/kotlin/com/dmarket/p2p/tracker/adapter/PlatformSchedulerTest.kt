package com.dmarket.p2p.tracker.adapter

import com.dmarket.p2p.tracker.adapter.host.CoroutineScheduler
import com.dmarket.p2p.tracker.model.TrackerMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertTrue

class PlatformSchedulerTest {

    private val scope = CoroutineScope(Job())

    @Test
    fun jvm_has_no_os_scheduler_so_both_modes_use_coroutine_scheduler() {
        assertTrue(platformScheduler(scope, TrackerMode.Foreground) is CoroutineScheduler)
        assertTrue(platformScheduler(scope, TrackerMode.Background) is CoroutineScheduler)
    }
}
