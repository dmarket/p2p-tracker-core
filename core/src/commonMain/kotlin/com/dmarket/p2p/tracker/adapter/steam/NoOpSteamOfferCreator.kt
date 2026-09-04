package com.dmarket.p2p.tracker.adapter.steam

import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.model.steam.TradeDraft
import com.dmarket.p2p.tracker.port.steam.CreateOfferResult
import com.dmarket.p2p.tracker.port.steam.SteamOfferCreator

/**
 * The default [SteamOfferCreator]: refuses to create.
 *
 * Keeps platforms without a concrete actual compiling (mobile is deferred). The web path wires
 * `FetchSteamOfferCreator`. A platform that must execute `create_offer` directives **must** supply a
 * real creator — otherwise create directives report back as failed.
 */
object NoOpSteamOfferCreator : SteamOfferCreator {
    override suspend fun createOffer(credential: SteamCredential, draft: TradeDraft): CreateOfferResult =
        CreateOfferResult.Failed("no Steam offer-create surface wired on this platform")
}
