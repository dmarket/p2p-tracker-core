package com.dmarket.p2p.tracker.model

import kotlin.jvm.JvmInline

/**
 * Identifies a Steam game (its `appid`) the tracker can operate on.
 *
 * The reference extension hardcodes `730` (CS2) in dozens of places; that maintenance debt is
 * deliberately not inherited. Every game-specific decision is routed through a [GameKey] so that
 * enabling Dota 2 / TF2 / Rust later is a registry/config change, never a rewrite.
 */
@JvmInline
value class GameKey(val appId: Int) {
    init {
        require(appId > 0) { "appId must be positive, was $appId" }
    }

    companion object {
        /** Counter-Strike 2 — the only game enabled at v1. */
        val CS2: GameKey = GameKey(730)

        /** Reserved for v1.1 (not enabled at runtime yet). */
        val DOTA2: GameKey = GameKey(570)

        /** Reserved for v1.1 (not enabled at runtime yet). */
        val TF2: GameKey = GameKey(440)

        /** Reserved for v1.1 (not enabled at runtime yet). */
        val RUST: GameKey = GameKey(252490)
    }
}
