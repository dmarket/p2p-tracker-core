package com.dmarket.p2p.tracker.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GameKeyTest {
    @Test
    fun cs2_uses_the_canonical_appid() {
        assertEquals(730, GameKey.CS2.appId)
    }

    @Test
    fun non_positive_appid_is_rejected() {
        assertFailsWith<IllegalArgumentException> { GameKey(0) }
        assertFailsWith<IllegalArgumentException> { GameKey(-1) }
    }
}
