package com.dmarket.p2p.tracker.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Cs2GameAdapterTest {
    private val adapter = Cs2GameAdapter()

    @Test
    fun uses_the_cs2_inventory_context_id() {
        assertEquals(2, adapter.inventoryContextId)
    }

    @Test
    fun belongs_to_game_uses_appid_not_a_hardcoded_constant() {
        assertTrue(adapter.belongsToGame(730))
        assertFalse(adapter.belongsToGame(570))
    }
}
