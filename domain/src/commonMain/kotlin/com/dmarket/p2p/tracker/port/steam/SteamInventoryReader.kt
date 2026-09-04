package com.dmarket.p2p.tracker.port.steam

import com.dmarket.p2p.tracker.model.steam.InventoryScan
import com.dmarket.p2p.tracker.model.steam.SteamCredential

/**
 * Reads the seller's **own** Steam inventory asset ids for the current game, for the
 * `report_inventory` directive's `POST /inventory` snapshot (R6). The client sends the **present**
 * asset ids; the backend computes the stale diff.
 *
 * Like the other Steam-facing ports, this takes a device-only [SteamCredential] and never touches the
 * marketplace — it sits on the Steam side of the audit boundary.
 */
interface SteamInventoryReader {
    /**
     * Scan the seller's Steam inventory for the active game.
     *
     * [InventoryScan.complete] is `true` **only** on a full enumeration (Steam's paging cursor
     * exhausted). A truncated, unusable or failed read returns the asset ids gathered so far with
     * `complete = false`, which the loop forwards as `scan_complete=false` so the backend skips the
     * stale-diff cancel (the mass-cancel guard). Implementations must never report a partial read as
     * complete, and must never substitute an empty set for a failure — an empty complete scan reads as
     * "every on-sale asset is stale".
     */
    suspend fun scanOwnInventory(credential: SteamCredential): InventoryScan
}
