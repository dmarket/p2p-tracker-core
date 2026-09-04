package com.dmarket.p2p.tracker.game

import com.dmarket.p2p.tracker.model.GameKey

/**
 * The per-game seam. Everything Steam encodes differently per title — the inventory context id and
 * which `appid` the game owns — lives behind this interface, so turning on a new game is implementing
 * this interface and registering it, never editing the engine.
 *
 * Note: under the v2 contract the client forwards **raw** Steam status codes and the backend maps
 * them, so this adapter no longer decodes offer/trade-status integers. It now matters for the
 * `create_offer` write path (inventory context id).
 */
interface GameAdapter {
    /** The game this adapter handles. */
    val game: GameKey

    /** Steam inventory context id for tradable items in this game (CS2 uses 2). */
    val inventoryContextId: Int

    /** True if [appId] belongs to this game — replaces the reference's hardcoded `=== 730`. */
    fun belongsToGame(appId: Int): Boolean = appId == game.appId
}
