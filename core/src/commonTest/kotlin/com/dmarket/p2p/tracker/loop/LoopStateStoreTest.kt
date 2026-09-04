package com.dmarket.p2p.tracker.loop

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class LoopStateStoreTest {
    private val t1 = Instant.fromEpochMilliseconds(1_781_611_200_000L)
    private val t2 = Instant.fromEpochMilliseconds(1_781_611_260_000L)

    @Test
    fun next_heartbeat_at_roundtrips_and_defaults_to_null() = runTest {
        val store = InMemoryLoopStateStore()
        assertNull(store.nextHeartbeatAt())
        store.setNextHeartbeatAt(t1)
        assertEquals(t1, store.nextHeartbeatAt())
        store.setNextHeartbeatAt(t2)
        assertEquals(t2, store.nextHeartbeatAt())
    }

    @Test
    fun expedited_until_roundtrips_and_defaults_to_null() = runTest {
        val store = InMemoryLoopStateStore()
        assertNull(store.expeditedUntil())
        store.setExpeditedUntil(t1)
        assertEquals(t1, store.expeditedUntil())
        store.setExpeditedUntil(t2)
        assertEquals(t2, store.expeditedUntil())
    }

    @Test
    fun server_error_count_roundtrips_and_defaults_to_zero() = runTest {
        val store = InMemoryLoopStateStore()
        assertEquals(0, store.serverErrorCount())
        store.setServerErrorCount(1)
        assertEquals(1, store.serverErrorCount())
        store.setServerErrorCount(0)
        assertEquals(0, store.serverErrorCount())
    }
}
