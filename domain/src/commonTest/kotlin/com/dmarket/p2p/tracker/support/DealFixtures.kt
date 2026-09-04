package com.dmarket.p2p.tracker.support

import com.dmarket.p2p.tracker.model.AccountId
import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.marketplace.Deal
import com.dmarket.p2p.tracker.model.marketplace.Money
import com.dmarket.p2p.tracker.model.marketplace.P2PDealState

/** Test fixtures for the C1 deal model (`Deal`, `P2PDealState`). */

val SELLER_ACCOUNT: AccountId = AccountId("seller-acct-1")
val BUYER_ACCOUNT: AccountId = AccountId("buyer-acct-1")

fun deal(
    id: String = "deal-1",
    state: P2PDealState = P2PDealState.AWAITING_TRADE,
    offerId: String = "offer-1",
    assetId: String = "asset-1",
    steamOfferId: String? = null,
): Deal = Deal(
    dealId = DealId(id),
    state = state,
    buyerAccountId = BUYER_ACCOUNT,
    sellerAccountId = SELLER_ACCOUNT,
    offerId = OfferId(offerId),
    assetId = AssetId(assetId),
    price = Money("USD", 1000L),
    steamOfferId = steamOfferId?.let(::OfferId),
    reasonCode = null,
    createdAt = T0,
    updatedAt = T0,
)
