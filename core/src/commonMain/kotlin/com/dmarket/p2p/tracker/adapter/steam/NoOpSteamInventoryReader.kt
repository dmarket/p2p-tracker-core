package com.dmarket.p2p.tracker.adapter.steam

import com.dmarket.p2p.tracker.model.steam.InventoryScan
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.port.steam.SteamInventoryReader

/**
 * The default [SteamInventoryReader]: reports that it saw nothing, and says so honestly.
 *
 * Keeps platforms without a concrete actual compiling (mobile is deferred). The web path wires
 * `KtorSteamInventoryReader`. It returns [InventoryScan.INCOMPLETE] — not an empty *complete* scan —
 * so a `report_inventory` directive reports `scan_complete=false` and the backend skips the stale diff.
 * That is the safe direction: an empty-but-"complete" scan would read as "every on-sale asset is
 * stale" and invite a mass cancel. Incompleteness rides the port's own return value, so the loop needs
 * no special case for this implementation.
 */
object NoOpSteamInventoryReader : SteamInventoryReader {
    override suspend fun scanOwnInventory(credential: SteamCredential): InventoryScan = InventoryScan.INCOMPLETE
}
