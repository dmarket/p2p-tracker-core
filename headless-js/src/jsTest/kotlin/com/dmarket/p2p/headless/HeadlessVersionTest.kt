package com.dmarket.p2p.headless

import com.dmarket.p2p.tracker.runtime.TradeTrackerCore
import kotlin.test.Test
import kotlin.test.assertEquals

class HeadlessVersionTest {
    @Test
    fun reports_the_core_version() {
        assertEquals(TradeTrackerCore.VERSION, headlessVersion())
    }
}
