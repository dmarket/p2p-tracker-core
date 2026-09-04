package com.dmarket.p2p.tracker.engine

import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.TradeId
import com.dmarket.p2p.tracker.model.steam.SteamTransfer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * The shapes here are the live `GetTradeHistory` payload of a real trade-protection rollback, ids and
 * timestamps included, so the fixtures cannot drift into a shape Steam does not actually return.
 */
class TransferCorrelationTest {

    private val asset = AssetId("51978272357")
    private val partner = SteamId("76561198336610283")

    /** The deal's own record, flipped to `status 12` by the rollback: `time_mod` set, no `rollback_trade`. */
    private val reversedOriginal = SteamTransfer(
        partnerSteamId = partner,
        assetIds = setOf(asset),
        status = 12,
        tradeId = TradeId("731422815690175777"),
        initiatedAt = Instant.fromEpochSeconds(1_785_760_349),
        modifiedAt = Instant.fromEpochSeconds(1_785_760_511),
    )

    /** What the rollback ADDS: a new completed record mirroring the original's assets, naming what it undid. */
    private val compensating = SteamTransfer(
        partnerSteamId = partner,
        assetIds = setOf(asset),
        status = 3,
        tradeId = TradeId("594063027056175076"),
        initiatedAt = Instant.fromEpochSeconds(1_785_760_511),
        rollbackTradeId = TradeId("731422815690175777"),
    )

    @Test
    fun the_rollback_wins_over_the_compensating_record_steam_lists_first() {
        // THE regression this whole object exists for. Steam lists newest first, so the compensating
        // status-3 record precedes the status-12 original it undoes; a first-match read the reversal as an
        // ordinary completion and the backend never learned the trade had been reversed.
        val selected = TransferCorrelation.select(listOf(compensating, reversedOriginal), asset)
        assertEquals(12, selected?.status)
        assertEquals(TradeId("731422815690175777"), selected?.tradeId)
    }

    @Test
    fun the_selected_rollback_carries_the_attribution_inputs() {
        // Only the original bears `time_mod`; picking the compensating record loses the correlation input
        // and silently makes the reversal unattributable as well as unreported.
        val selected = TransferCorrelation.select(listOf(compensating, reversedOriginal), asset)
        assertEquals(Instant.fromEpochSeconds(1_785_760_511), selected?.modifiedAt)
        assertEquals(partner, selected?.partnerSteamId)
    }

    @Test
    fun payload_order_does_not_decide_the_verdict() {
        // The old code carried no ordering assumption at all — it just took the first match — so it
        // happened to be right under one order and wrong under the other. Both must now agree.
        val listed = TransferCorrelation.select(listOf(compensating, reversedOriginal), asset)
        val reversedListing = TransferCorrelation.select(listOf(reversedOriginal, compensating), asset)
        assertEquals(12, listed?.status)
        assertEquals(listed, reversedListing)
    }

    @Test
    fun an_asset_resold_after_a_rollback_correlates_to_the_newer_trade() {
        // An item returns under its original asset id, so the same asset can legitimately carry several
        // real rows. The deal in hand is the most recently initiated one — NOT simply "any status 12".
        val resold = SteamTransfer(
            partnerSteamId = SteamId("76561199497281579"),
            assetIds = setOf(asset),
            status = 3,
            tradeId = TradeId("744933614571231457"),
            initiatedAt = Instant.fromEpochSeconds(1_785_800_000),
        )
        val selected = TransferCorrelation.select(listOf(resold, compensating, reversedOriginal), asset)
        assertEquals(TradeId("744933614571231457"), selected?.tradeId)
        assertEquals(3, selected?.status)
    }

    @Test
    fun a_compensating_record_alone_yields_no_observation_at_all() {
        // Its original fell outside the history window. Reporting the compensating record's status 3 would
        // assert the exact opposite of what happened (Complete = the backend's payout condition), so the
        // honest answer is "nothing observed" and the deal keeps being watched.
        assertNull(TransferCorrelation.select(listOf(compensating), asset))
    }

    @Test
    fun an_ordinary_completion_is_unaffected() {
        val settled = SteamTransfer(
            partnerSteamId = partner,
            assetIds = setOf(asset),
            status = 3,
            tradeId = TradeId("723541516341221347"),
            initiatedAt = Instant.fromEpochSeconds(1_785_747_967),
        )
        assertEquals(settled, TransferCorrelation.select(listOf(settled), asset))
    }

    @Test
    fun rows_for_other_assets_are_never_borrowed() {
        val other = SteamTransfer(
            partnerSteamId = partner,
            assetIds = setOf(AssetId("50827622827")),
            status = 12,
            tradeId = TradeId("731422815690175778"),
            initiatedAt = Instant.fromEpochSeconds(1_785_760_350),
        )
        assertNull(TransferCorrelation.select(listOf(other), asset))
        assertEquals(12, TransferCorrelation.select(listOf(other), AssetId("50827622827"))?.status)
    }

    @Test
    fun a_row_with_no_time_init_loses_to_one_that_has_it() {
        val undated = reversedOriginal.copy(tradeId = TradeId("undated"), initiatedAt = null, status = 4)
        val selected = TransferCorrelation.select(listOf(undated, reversedOriginal), asset)
        assertEquals(TradeId("731422815690175777"), selected?.tradeId)
    }

    @Test
    fun undated_candidates_resolve_to_the_first_listed_rather_than_arbitrarily() {
        // All-null `time_init` must still be deterministic: Steam lists newest first, so the first listed
        // is the best available guess and repeated reads must not flip between rows.
        val first = reversedOriginal.copy(tradeId = TradeId("first"), initiatedAt = null)
        val second = reversedOriginal.copy(tradeId = TradeId("second"), initiatedAt = null)
        assertEquals(TradeId("first"), TransferCorrelation.select(listOf(first, second), asset)?.tradeId)
    }

    @Test
    fun an_empty_history_matches_nothing() {
        assertNull(TransferCorrelation.select(emptyList(), asset))
    }

    @Test
    fun a_compound_dmarket_asset_ref_correlates_to_the_steam_row_it_names() {
        // THE live defect: `Deal.assetId` is a compound of Steam's identity numbers, not a bare asset id, so
        // the ref matched nothing and every history-watched deal's transfer axis was silently blind. These are
        // the three refs dev2 served, with the `status 12` rows Steam held for them at the same moment.
        val cases = listOf(
            Triple("143865972:8490849127:51978272353:730", "51978272353", setOf("143865972", "8490849127", "730")),
            Triple("302028390:4901046679:47285262716:730", "47285262716", setOf("302028390", "4901046679", "730")),
            Triple("302028390:1989275999:44977997680:730", "44977997680", setOf("302028390", "1989275999", "730")),
        )
        for ((ref, steamAssetId, others) in cases) {
            val row = reversedOriginal.copy(assetIds = setOf(AssetId(steamAssetId)), assetTokens = others + steamAssetId)
            assertEquals(12, TransferCorrelation.select(listOf(compensating, row), AssetId(ref))?.status, "ref $ref")
        }
    }

    @Test
    fun the_refs_layout_is_never_assumed() {
        // The point of matching on the ref's PARTS: reading the asset id out of a fixed position would break —
        // silently, or by lifting a neighbouring field — the moment the backend reorders the ref, drops a part
        // or adds one. Every one of these still names the same asset, so every one must still correlate.
        val row = reversedOriginal.copy(
            assetIds = setOf(AssetId("44977997680")),
            assetTokens = setOf("44977997680", "302028390", "1989275999", "730", "2"),
        )
        val layouts = listOf(
            "302028390:1989275999:44977997680:730", // live shape: instanceid:classid:assetid:appid
            "730:2:1989275999:302028390:44977997680", // reordered, with contextid added
            "44977997680", // a bare asset id
            "44977997680:730",
        )
        for (ref in layouts) {
            assertEquals(12, TransferCorrelation.select(listOf(row), AssetId(ref))?.status, "layout $ref")
        }
    }

    @Test
    fun a_ref_part_steam_does_not_publish_still_correlates() {
        // Corroboration must never turn a match into NO match: an unknown part (a DMarket-internal number, a
        // field Steam stopped sending) would otherwise silence the axis — the exact failure being fixed here.
        val row = reversedOriginal.copy(assetIds = setOf(AssetId("44977997680")), assetTokens = setOf("44977997680", "730"))
        assertEquals(12, TransferCorrelation.select(listOf(row), AssetId("999000111:44977997680:730"))?.status)
    }

    @Test
    fun a_row_that_merely_shares_a_number_loses_to_the_one_the_ref_describes() {
        // The only thing corroboration is for: two rows are named by the ref because one of them happens to
        // carry another of its parts as ITS asset id. The fully-described row wins even though it is older.
        val ref = AssetId("302028390:1989275999:44977997680:730")
        val described = reversedOriginal.copy(
            tradeId = TradeId("739304749312013446"),
            assetIds = setOf(AssetId("44977997680")),
            assetTokens = setOf("44977997680", "302028390", "1989275999", "730"),
            initiatedAt = Instant.fromEpochSeconds(1_785_929_600),
        )
        val coincidence = reversedOriginal.copy(
            tradeId = TradeId("coincidence"),
            assetIds = setOf(AssetId("302028390")),
            assetTokens = setOf("302028390", "8490849127", "730"),
            initiatedAt = Instant.fromEpochSeconds(1_785_999_999),
        )
        assertEquals(TradeId("739304749312013446"), TransferCorrelation.select(listOf(coincidence, described), ref)?.tradeId)
    }

    @Test
    fun a_transfer_is_only_due_once_the_offer_was_accepted() {
        // The discriminator between the two reasons `select` answers null: nothing to correlate yet, versus a
        // join that failed. Only `3 Accepted` means Steam must already hold a row for this deal.
        assertEquals(true, TransferCorrelation.isTransferDue(3))
        for (state in listOf(null, 1, 2, 4, 5, 6, 7, 8, 9, 10, 11)) {
            assertEquals(false, TransferCorrelation.isTransferDue(state), "offer state $state must not be transfer-due")
        }
    }
}
