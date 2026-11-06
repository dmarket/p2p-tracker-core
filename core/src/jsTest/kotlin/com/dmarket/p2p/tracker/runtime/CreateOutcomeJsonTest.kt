package com.dmarket.p2p.tracker.runtime

import com.dmarket.p2p.tracker.port.steam.CreateOfferResult
import kotlin.test.Test
import kotlin.test.assertEquals

class CreateOutcomeJsonTest {
    @Test
    fun a_paused_create_is_a_failure_with_its_own_status() {
        assertEquals("""{"ok":false,"status":"creates_paused"}""", CreateOfferResult.CreatesPaused.toOutcomeJson())
    }
}
