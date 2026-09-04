package com.dmarket.p2p.tracker.game

import com.dmarket.p2p.tracker.config.GameConfig
import com.dmarket.p2p.tracker.model.GameKey

/** Raised when a deal references a game that is registered but not enabled (or unknown entirely). */
class GameNotEnabledException(val game: GameKey) : IllegalStateException("Game appid=${game.appId} is not enabled in this build")

/**
 * Holds the per-game adapters and the set that is actually switched on.
 *
 * At v1 only CS2 is enabled. Dota 2 / TF2 / Rust adapters can be registered as they are written; a
 * v1.1 launch flips them into [enabled] — a config change, not a rewrite. The scaffolding (registry,
 * adapter interface, decoded enums) is in place precisely so that stays true.
 */
class GameRegistry private constructor(private val adapters: Map<GameKey, GameAdapter>, private val enabled: Set<GameKey>) {
    val enabledGames: Set<GameKey> get() = enabled

    fun isEnabled(game: GameKey): Boolean = game in enabled

    /** The adapter for [game], or throws [GameNotEnabledException] if it is not enabled. */
    fun adapterFor(game: GameKey): GameAdapter {
        if (game !in enabled) throw GameNotEnabledException(game)
        return adapters.getValue(game)
    }

    /** Find the enabled adapter that owns [appId], if any. */
    fun adapterForAppId(appId: Int): GameAdapter? =
        enabled.asSequence().map { adapters.getValue(it) }.firstOrNull { it.belongsToGame(appId) }

    companion object {
        /** The v1 registry: CS2 registered and enabled, nothing else. */
        fun v1(gameConfig: GameConfig = GameConfig()): GameRegistry {
            val cs2 = Cs2GameAdapter(gameConfig)
            return GameRegistry(
                adapters = mapOf(cs2.game to cs2),
                enabled = setOf(cs2.game),
            )
        }

        /**
         * Build a custom registry — used by tests and, later, to wire additional games. [enabled]
         * must be a subset of the registered adapter keys.
         */
        fun of(adapters: List<GameAdapter>, enabled: Set<GameKey>): GameRegistry {
            val byKey = adapters.associateBy { it.game }
            require(enabled.all { it in byKey }) { "enabled games must all be registered" }
            return GameRegistry(byKey, enabled)
        }
    }
}
