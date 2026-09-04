package com.dmarket.p2p.tracker.loop

import kotlin.test.Test
import kotlin.test.assertEquals

class CyclePolicyTest {

    @Test
    fun a_due_heartbeat_always_wins() {
        // heartbeatDue outranks everything — a first start (no persisted schedule) is inherently due,
        // and forceHeartbeatNow() works by making the heartbeat due.
        assertEquals(CycleAction.HEARTBEAT, CyclePolicy.decide(heartbeatDue = true, hasTrackingList = false))
        assertEquals(CycleAction.HEARTBEAT, CyclePolicy.decide(heartbeatDue = true, hasTrackingList = true))
    }

    @Test
    fun between_heartbeats_a_live_tracking_list_is_watched() {
        assertEquals(CycleAction.WATCH_ONLY, CyclePolicy.decide(heartbeatDue = false, hasTrackingList = true))
    }

    @Test
    fun a_fresh_instance_with_nothing_to_watch_idles_until_the_due_tick() {
        // The backend orchestrates: no self-initiated heartbeat, no watching from stale local state.
        assertEquals(CycleAction.IDLE, CyclePolicy.decide(heartbeatDue = false, hasTrackingList = false))
    }
}
