package com.dmarket.p2p.tracker.game

import com.dmarket.p2p.tracker.config.GameConfig
import com.dmarket.p2p.tracker.model.GameKey

/**
 * Counter-Strike 2 adapter — the only game enabled at v1.
 *
 * Decoding of Steam's `ETradeOfferState` / `ETradeStatus` integers is intentionally absent: the v2
 * client forwards raw codes and the backend maps them (the undocumented status `12` reversal
 * included). This adapter supplies the CS2-specific inventory context id, sourced from [config]
 * (defaults to the in-code baseline).
 */
class Cs2GameAdapter(private val config: GameConfig = GameConfig()) : GameAdapter {
    override val game: GameKey = GameKey.CS2

    override val inventoryContextId: Int = config.cs2InventoryContextId
}
