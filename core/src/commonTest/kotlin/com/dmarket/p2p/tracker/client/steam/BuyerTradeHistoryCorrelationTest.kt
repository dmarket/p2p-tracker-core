package com.dmarket.p2p.tracker.client.steam

import com.dmarket.p2p.tracker.engine.TransferCorrelation
import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.TradeId
import com.dmarket.p2p.tracker.support.fixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * `GetTradeHistory` read by the **buyer** (DMA-664): the deal's skin is in `assets_received`, under the seller's
 * `assetid`, and Steam adds `new_assetid` for the id it now has in the buyer's inventory. The deal's asset ref names
 * the seller's `assetid`, so the row correlates by it, and a later trade of the same skin by the buyer (where it is
 * given under the `new_assetid`) is never taken for the deal.
 */
class BuyerTradeHistoryCorrelationTest {
    private val rows = SteamReadResponses.transfers(fixture("steam_trade_history_buyer.json"))

    /** The live layout of a deal's asset ref, `instanceid:classid:assetid:appid`, with the seller's `assetid`. */
    private val completedRef = AssetId("143865972:8490849127:51978272400:730")
    private val reversedRef = AssetId("188530139:5222000111:51978272357:730")

    @Test
    fun a_received_skin_correlates_by_the_sellers_asset_id() {
        val row = TransferCorrelation.select(rows, completedRef)

        assertEquals(3, row?.status)
        assertEquals(TradeId("731422815690180200"), row?.tradeId)
        assertEquals(SteamId("76561198336610283"), row?.partnerSteamId)
        assertEquals(Instant.fromEpochSeconds(1_786_405_000), row?.settlementAt)
    }

    @Test
    fun a_received_skin_correlates_by_trade_id() {
        val row = TransferCorrelation.selectByTradeId(rows, TradeId("731422815690180200"))

        assertEquals(3, row?.status)
        assertEquals(setOf(AssetId("51978272400")), row?.assetIds)
    }

    @Test
    fun the_new_asset_id_is_not_the_deals_asset() {
        // Only `assetid` is a row's asset: `new_assetid` is not read. The buyer later gave the skin away under its new
        // id, which is that later row's `assetid`; the deal's ref (the seller's `assetid`) still picks the purchase.
        assertEquals(TradeId("594063027056180300"), TransferCorrelation.select(rows, AssetId("52011111111"))?.tradeId)
        assertEquals(TradeId("731422815690180200"), TransferCorrelation.select(rows, completedRef)?.tradeId)
        assertNull(TransferCorrelation.select(rows, AssetId("52033333333")))
    }

    @Test
    fun a_rollback_seen_by_the_buyer_is_12_not_the_compensating_completion() {
        // Steam's compensating record mirrors the original's direction, so on the buyer's side it too lists the skin as
        // received; it carries `rollback_trade` and never wins.
        val byRef = TransferCorrelation.select(rows, reversedRef)
        val byTradeId = TransferCorrelation.selectByTradeId(rows, TradeId("731422815690175777"))

        assertEquals(12, byRef?.status)
        assertEquals(TradeId("731422815690175777"), byRef?.tradeId)
        assertEquals(Instant.fromEpochSeconds(1_785_760_511), byRef?.modifiedAt)
        assertEquals(byRef, byTradeId)
    }
}
