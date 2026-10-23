package com.dmarket.p2p.tracker.adapter.steam

import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.port.steam.CancelOfferResult
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
    /** Answers as a cancel that landed, as this no-op always has: see the class note on supplying a real one. */
    override suspend fun cancelOffer(credential: SteamCredential, offerId: OfferId): CancelOfferResult = CancelOfferResult.Cancelled
}
