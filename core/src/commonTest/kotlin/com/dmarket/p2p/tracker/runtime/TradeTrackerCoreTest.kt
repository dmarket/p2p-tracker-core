package com.dmarket.p2p.tracker.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TradeTrackerCoreTest {
    @Test
    fun v1_enables_a_single_game() {
        assertEquals(1, TradeTrackerCore().enabledGameCount())
    }

    @Test
    fun exposes_a_version() {
        // A shape check, not a pinned literal, so version bumps don't break this test.
        // The exact value tracks VERSION_NAME in gradle.properties.
        val version = TradeTrackerCore.VERSION
        assertTrue(
            Regex("""^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$""").matches(version),
            "VERSION should be SemVer-shaped, was: $version",
        )
    }
}
