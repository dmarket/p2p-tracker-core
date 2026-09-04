package com.dmarket.p2p.tracker.adapter.steam

import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.port.steam.SteamOfferCanceller

/**
 * The default [SteamOfferCanceller]: does nothing.
 *
 * This keeps platforms without a concrete actual compiling (mobile is deferred), mirroring
 * [NoOpPushChannel]. The web path wires `FetchSteamOfferCanceller`; a platform that relies on
 * auto-cancel of stale/dangling offers **must** supply a real canceller via
 * [com.dmarket.p2p.tracker.runtime.TradeTrackerCore.createLoop] — otherwise those offers are never
 * wound down on Steam.
 */
object NoOpSteamOfferCanceller : SteamOfferCanceller {
    override suspend fun cancelOffer(credential: SteamCredential, offerId: OfferId) { /* no-op: no Steam write wired */ }
}
