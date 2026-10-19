package com.dmarket.p2p.tracker.model.steam

import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.SteamId
import kotlin.time.Instant

/**
 * One offer this account sent, as `GetTradeOffers` lists it: enough to recognise an offer a create made when the
 * create's own answer never came back.
 *
 * @property partner the counterparty, as a steamid64.
 * @property state raw `ETradeOfferState`.
 * @property assetIdsGiven the asset ids this account gives in it.
 * @property createdAt Steam's `time_created`, by Steam's clock.
 */
data class SteamSentOffer(
    val offerId: OfferId,
    val partner: SteamId,
    val state: Int,
    val assetIdsGiven: Set<AssetId>,
    val createdAt: Instant,
)
