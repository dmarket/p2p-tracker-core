package com.dmarket.p2p.tracker.model.steam

import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.SteamId

/**
 * The inputs the seller plugin needs to send a Steam trade offer for a committed deal.
 * The loop builds it from a [Deal] in
 * [P2PDealState.AWAITING_TRADE] and hands it to [com.dmarket.p2p.tracker.port.steam.SteamOfferCreator].
 *
 * @property partner the buyer's Steam id (the trade-offer recipient).
 * @property assetsToGive the seller's item(s) to send (the deal's [Deal.assetId]).
 * @property tradeToken the buyer's trade-offer access token, when the offer needs one.
 */
data class TradeDraft(val partner: SteamId, val assetsToGive: List<AssetId>, val tradeToken: String? = null) {
    /**
     * Redacted: [tradeToken] is a bearer capability — it is what lets anyone holding it send that Steam
     * account a trade offer. A generated `toString()` would print it wherever a create is diagnosed, and
     * the create's failure string is POSTed to DMarket and handed to the web page. `null` vs present is
     * kept, because "was a token supplied at all" is the usual question.
     */
    override fun toString(): String = "TradeDraft(partner=$partner, assetsToGive=$assetsToGive, " +
        "tradeToken=${if (tradeToken == null) "null" else "<redacted>"})"
}
