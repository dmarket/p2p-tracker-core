package com.dmarket.p2p.tracker.port.steam

import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.TradeId
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.model.steam.SteamOfferSnapshot
import com.dmarket.p2p.tracker.model.steam.SteamTransfer

/**
 * Read-only access to Steam's trade surfaces (the stable JSON `IEconService` API). All calls are
 * authorised by the device-local [SteamCredential]; this port only ever *reads*.
 *
 * It returns **raw** Steam status integers — the seller plugin watches both Steam axes of a deal's
 * trade (`ETradeOfferState` + `ETradeStatus`) to decide when a trade is terminal (R9), then submits a
 * TLSN-signed TERMINAL trade-event; the backend re-derives the money outcome from the proof.
 */
interface SteamReadClient {
    /**
     * The offer axis for each tracked [offerIds], keyed by [OfferId]: the raw `ETradeOfferState` plus the
     * `tradeid` Steam attaches once the offer is accepted (see [SteamOfferSnapshot]). To minimise Steam
     * requests (and rate-limit risk), a single tracked offer is read **targeted** via `GetTradeOffer`, while
     * more than one is read with a single account-wide `GetTradeOffers` list matched to the tracked ids, then
     * a targeted `GetTradeOffer` for each id the list omits — the switch point is
     * `SteamEndpointsConfig.bulkOfferThreshold` (default `1`). An offer missing from the result is unknown to
     * Steam (skipped, like Steam's empty response).
     */
    suspend fun offerSnapshots(credential: SteamCredential, offerIds: Set<OfferId>): Map<OfferId, SteamOfferSnapshot>

    /**
     * Recent transfers from the account-wide `GetTradeHistory`, capped at [maxTrades] — the transfer
     * axis (`ETradeStatus`). Keyed by Steam's own `tradeid`, which the offer axis hands over on an accepted
     * offer, so a watched deal correlates to its row by that id; the disclosed asset ids are the fallback for
     * a deal whose offer Steam no longer lists (see [SteamTransfer]).
     */
    suspend fun recentTransfers(credential: SteamCredential, maxTrades: Int): List<SteamTransfer>

    /**
     * The transfer rows Steam holds for the single trade [tradeId] (`GetTradeStatus`) — the per-deal fallback
     * for a watched deal whose row the windowed [recentTransfers] did not cover, mirroring the offer axis's
     * bulk→targeted shape.
     *
     * **Returns rows, not a row**, and that is deliberate: Steam may answer with the rollback partner
     * alongside the trade asked for, and the order is undocumented. Selecting among them stays in the pure
     * correlation (`TransferCorrelation.selectByTradeId`), which keys on the id — a single-transfer signature
     * would have to pick here, and picking wrong reports a reversal as a completion.
     *
     * An empty list means Steam knows nothing of that trade (the same reading [recentTransfers] gives an
     * absent row); a read failure throws, so the axis fails closed rather than reporting a wrong code.
     */
    suspend fun transfersByTradeId(credential: SteamCredential, tradeId: TradeId): List<SteamTransfer>
}
