package com.dmarket.p2p.tracker.adapter

import com.dmarket.p2p.tracker.adapter.host.CoroutineScheduler
import com.dmarket.p2p.tracker.adapter.webext.WebExtAlarmsScheduler
import com.dmarket.p2p.tracker.model.TrackerMode
import com.dmarket.p2p.tracker.port.host.Scheduler
import kotlinx.coroutines.CoroutineScope

/**
 * Web selection: a foreground (popup) context stays alive, so the in-process [CoroutineScheduler]
 * gives exact, sub-minute delays; a background (MV3 service worker) context is killable, so only
 * [WebExtAlarmsScheduler] survives teardown (Chrome floors the period at ~60s).
 */
actual fun platformScheduler(scope: CoroutineScope, mode: TrackerMode): Scheduler = when (mode) {
    TrackerMode.Foreground -> CoroutineScheduler(scope)
    TrackerMode.Background -> WebExtAlarmsScheduler()
}
