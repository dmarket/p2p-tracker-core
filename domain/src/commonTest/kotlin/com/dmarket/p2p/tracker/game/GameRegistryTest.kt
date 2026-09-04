package com.dmarket.p2p.tracker.game

import com.dmarket.p2p.tracker.model.GameKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameRegistryTest {
    private val registry = GameRegistry.v1()

    @Test
    fun v1_enables_only_cs2() {
        assertEquals(setOf(GameKey.CS2), registry.enabledGames)
        assertTrue(registry.isEnabled(GameKey.CS2))
        assertEquals(false, registry.isEnabled(GameKey.DOTA2))
    }

    @Test
    fun adapter_for_enabled_game_is_returned() {
        assertTrue(registry.adapterFor(GameKey.CS2) is Cs2GameAdapter)
    }

    @Test
    fun adapter_for_disabled_game_throws() {
        val error = assertFailsWith<GameNotEnabledException> { registry.adapterFor(GameKey.DOTA2) }
        assertEquals(GameKey.DOTA2, error.game)
    }

    @Test
    fun adapter_for_appid_resolves_only_enabled_games() {
        assertTrue(registry.adapterForAppId(730) is Cs2GameAdapter)
        assertNull(registry.adapterForAppId(570))
    }

    @Test
    fun custom_registry_requires_enabled_to_be_registered() {
        assertFailsWith<IllegalArgumentException> {
            GameRegistry.of(adapters = listOf(Cs2GameAdapter()), enabled = setOf(GameKey.DOTA2))
        }
    }
}
