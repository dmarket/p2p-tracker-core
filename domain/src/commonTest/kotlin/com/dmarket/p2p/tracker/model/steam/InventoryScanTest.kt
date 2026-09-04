package com.dmarket.p2p.tracker.model.steam

import com.dmarket.p2p.tracker.model.AssetId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InventoryScanTest {

    @Test
    fun incomplete_constant_is_empty_and_not_complete() {
        // The safe default whenever nothing dependable could be read: an empty *complete* scan would tell
        // the backend every on-sale asset is stale.
        assertTrue(InventoryScan.INCOMPLETE.assetIds.isEmpty())
        assertFalse(InventoryScan.INCOMPLETE.complete)
    }

    @Test
    fun carries_asset_ids_and_completeness_independently() {
        val ids = setOf(AssetId("111"), AssetId("222"))
        val cases = listOf(
            InventoryScan(ids, complete = true) to true,
            InventoryScan(ids, complete = false) to false,
            InventoryScan(emptySet(), complete = true) to true,
            InventoryScan(emptySet(), complete = false) to false,
        )
        for ((scan, expectedComplete) in cases) {
            assertEquals(expectedComplete, scan.complete, "completeness must not be inferred from emptiness: $scan")
        }
    }

    @Test
    fun a_complete_empty_scan_is_representable() {
        // A seller who genuinely owns nothing is a real, complete answer — distinct from "we could not read".
        val scan = InventoryScan(emptySet(), complete = true)
        assertTrue(scan.assetIds.isEmpty())
        assertTrue(scan.complete)
        assertFalse(scan == InventoryScan.INCOMPLETE, "an empty complete scan must not equal INCOMPLETE")
    }
}
