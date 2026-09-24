package com.dmarket.p2p.tracker.runtime

import kotlin.test.Test
import kotlin.test.assertEquals

class ClientVersionTest {
    @Test
    fun the_host_build_is_what_the_heartbeat_reports() {
        assertEquals("1.0.5-beta", resolveClientVersion("1.0.5-beta"))
    }

    @Test
    fun surrounding_whitespace_is_not_part_of_the_version() {
        assertEquals("1.0.5-beta", resolveClientVersion(" 1.0.5-beta\n"))
    }

    @Test
    fun an_absent_or_blank_host_version_falls_back_to_this_library() {
        // Blank must not reach the wire: the backend reads an empty clientVersion as a client that
        // predates the field, which says less than the library version does.
        assertEquals(TradeTrackerCore.VERSION, resolveClientVersion(null))
        assertEquals(TradeTrackerCore.VERSION, resolveClientVersion(""))
        assertEquals(TradeTrackerCore.VERSION, resolveClientVersion("   "))
    }
}
