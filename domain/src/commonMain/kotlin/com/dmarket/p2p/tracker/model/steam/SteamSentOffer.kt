package com.dmarket.p2p.tracker.model.steam

import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.SteamId
import kotlin.time.Instant

/** Which `GetTradeOffers` list an offer came from: one this account sent, or one sent to it. */
enum class OfferDirection { SENT, RECEIVED }

/**
 * One offer of this account, as `GetTradeOffers` lists it: enough to recognise an offer a create made when the
 * create's own answer never came back, and for the suspicious-offer check.
 *
 * The sides are named from **this account's** side in both directions: Steam reports `items_to_give`,
 * `items_to_receive` and `accountid_other` relative to whoever reads the list, on the sent and the received list
 * alike (DMarket's backend confirmed it in production, 2026-09-15). So a received offer that takes this account's
 * items has them in [assetIdsGiven], and [partner] is the sender.
 *
 * @property partner the counterparty, as a steamid64.
 * @property state raw `ETradeOfferState`.
 * @property assetIdsGiven the asset ids this account gives in it.
 * @property createdAt Steam's `time_created`, by Steam's clock.
 * @property assetIdsReceived the asset ids this account gets back. Empty on a give-only offer, which is what the
 *   suspicious-offer check keys on (see `com.dmarket.p2p.tracker.engine.SuspiciousOfferCheck`).
 * @property direction which list carried it.
 */
data class SteamSentOffer(
    val offerId: OfferId,
    val partner: SteamId,
    val state: Int,
    val assetIdsGiven: Set<AssetId>,
    val createdAt: Instant,
    val assetIdsReceived: Set<AssetId> = emptySet(),
    val direction: OfferDirection = OfferDirection.SENT,
)
