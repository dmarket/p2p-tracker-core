package com.dmarket.p2p.tracker.engine

import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.steam.OfferDirection
import com.dmarket.p2p.tracker.model.steam.SteamSentOffer

/** Which of the account's sent offers a `create_offer` to a partner for a set of assets made. */
object CreateOfferMatch {
    /**
     * The `ETradeOfferState`s an offer a re-leased create binds to can be in: Active (2), Accepted (3),
     * CreatedNeedsConfirmation (9) and InEscrow (11). A declined, expired or cancelled offer is no delivery.
     */
    val BINDABLE_STATES: Set<Int> = setOf(2, 3, 9, 11)

    /** [offer] goes to [partner] and gives every one of [assetIds], maybe more. */
    fun givesAll(offer: SteamSentOffer, partner: SteamId, assetIds: Set<AssetId>): Boolean =
        offer.partner == partner && offer.assetIdsGiven.containsAll(assetIds)

    /** [offer] goes to [partner] and gives exactly [assetIds]. */
    fun givesExactly(offer: SteamSentOffer, partner: SteamId, assetIds: Set<AssetId>): Boolean =
        offer.partner == partner && offer.assetIdsGiven == assetIds

    /**
     * The offer a re-leased `create_offer` to [partner] for [assetIds] reports instead of creating one: a sent offer
     * giving exactly those assets, in one of [BINDABLE_STATES]. The newest wins; `null` when there is none.
     */
    fun bindable(offers: List<SteamSentOffer>, partner: SteamId, assetIds: Set<AssetId>): SteamSentOffer? = offers
        .filter { it.direction == OfferDirection.SENT && it.state in BINDABLE_STATES && givesExactly(it, partner, assetIds) }
        .maxByOrNull { it.createdAt }
}
