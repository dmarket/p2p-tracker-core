package com.dmarket.p2p.tracker.model.steam

import com.dmarket.p2p.tracker.model.TradeId

/**
 * One tracked trade offer as read from `GetTradeOffers` / `GetTradeOffer` — the **offer axis**
 * (`ETradeOfferState`).
 *
 * @property state raw `ETradeOfferState` integer, forwarded as-is (the backend maps it).
 * @property tradeId Steam's `tradeid`, which it sets on the offer **once the offer is accepted** — the id of
 *   the resulting transfer record. This is the primary key of the corresponding `GetTradeHistory` row, so it
 *   is what correlates a watched deal to its transfer: an exact 1:1 join, read from a call the loop already
 *   makes every cycle, and free of any assumption about how DMarket spells an asset ref. `null` until the
 *   offer is accepted (and on offers that never were), which is precisely when no transfer record exists.
 */
data class SteamOfferSnapshot(val state: Int, val tradeId: TradeId? = null)
