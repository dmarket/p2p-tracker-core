package com.dmarket.p2p.tracker.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CoreVersionTest {
    @Test
    fun the_version_is_the_one_gradle_properties_publishes() {
        // Pins the generation, not a literal: until 1.0.3-beta VERSION was a hand-written constant that
        // still said 0.1.0-SNAPSHOT three releases after it stopped being true.
        val published = assertNotNull(System.getProperty("dmarket.versionName"), "jvmTest must receive VERSION_NAME")
        assertEquals(published, TradeTrackerCore.VERSION)
    }
}
