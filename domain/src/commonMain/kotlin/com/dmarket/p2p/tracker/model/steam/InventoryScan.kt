package com.dmarket.p2p.tracker.model.steam

import com.dmarket.p2p.tracker.model.AssetId

/**
 * The result of one own-inventory scan for the `report_inventory` directive: the asset ids read, plus
 * whether the read is a **complete** enumeration of the account's inventory for the active game.
 *
 * [complete] is the honest answer to "did we see the whole inventory", not "did the fetch succeed".
 * Steam pages the community inventory endpoint and signals a truncated page with `more_items` +
 * `last_assetid`; a scan that stops early for any reason — truncation, an unusable body, a failed
 * request, or an exhausted page budget — is [complete] = `false`.
 *
 * The distinction is load-bearing: the loop forwards it as `scan_complete`, and the backend treats
 * `scan_complete=true` as licence to cancel every on-sale asset missing from the snapshot. Reporting a
 * partial scan as complete would mass-cancel the offers whose assets simply were not read.
 */
data class InventoryScan(val assetIds: Set<AssetId>, val complete: Boolean) {
    companion object {
        /** No asset ids and an explicitly incomplete scan — the safe answer when nothing could be read. */
        val INCOMPLETE: InventoryScan = InventoryScan(emptySet(), complete = false)
    }
}
