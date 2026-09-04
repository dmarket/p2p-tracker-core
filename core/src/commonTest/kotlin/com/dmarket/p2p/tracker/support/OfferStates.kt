package com.dmarket.p2p.tracker.support

import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.port.steam.SteamReadClient

/**
 * The offer axis reduced to its raw states.
 *
 * Most offer-axis cases are about *which* Steam calls get made and how their states merge, not about the
 * `tradeid` that rides along — so they read better against a plain `Map<OfferId, Int>`. The cases that ARE
 * about the trade id assert on [SteamReadClient.offerSnapshots] directly.
 */
suspend fun SteamReadClient.offerStates(credential: SteamCredential, offerIds: Set<OfferId>): Map<OfferId, Int> =
    offerSnapshots(credential, offerIds).mapValues { (_, snapshot) -> snapshot.state }
