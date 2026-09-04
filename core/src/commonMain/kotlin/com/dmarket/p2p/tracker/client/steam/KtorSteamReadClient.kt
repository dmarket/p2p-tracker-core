package com.dmarket.p2p.tracker.client.steam

import com.dmarket.p2p.tracker.config.SteamEndpointsConfig
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.model.steam.SteamOfferSnapshot
import com.dmarket.p2p.tracker.model.steam.SteamTransfer
import com.dmarket.p2p.tracker.port.steam.SteamReadClient
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText

/**
 * Ktor-backed implementation of [SteamReadClient] that calls Steam's `IEconService` endpoints and
 * forwards **raw** status codes (the v2 client does no decoding — the backend maps them).
 *
 * Auth is via the `access_token` query parameter (Steam Web API convention), not an Authorization
 * header.
 *
 * unit-tested (`KtorSteamReadClientTest`, `SteamOfferStatusStrategyTest`) but not yet confirmed
 * against reality:
 *  1. single-offer `GetTradeOffer` response shape (`response.offer.trade_offer_state`) against real
 *     Steam;
 *  2. the bulk `GetTradeOffers` list + per-offer fallback for missing ids is correct with several
 *     concurrent tracked offers against live Steam;
 *  3. confirm the account-wide `GetTradeOffers` list actually returns every tracked sent offer (so
 *     the per-offer fallback stays rare), and revisit `SteamEndpointsConfig.bulkOfferThreshold`.
 */
class KtorSteamReadClient(private val httpClient: HttpClient, private val endpoints: SteamEndpointsConfig = SteamEndpointsConfig()) :
    SteamReadClient {

    override suspend fun offerSnapshots(credential: SteamCredential, offerIds: Set<OfferId>): Map<OfferId, SteamOfferSnapshot> =
        SteamOfferStatusStrategy.resolve(
            offerIds = offerIds,
            bulkThreshold = endpoints.bulkOfferThreshold,
            fetchSingle = { singleOfferSnapshot(credential, it) },
            fetchAllBulk = { allBulkOfferSnapshots(credential) },
        )

    /**
     * Single-offer `GetTradeOffer?tradeofferid=`; `null` if Steam doesn't know the id (empty response).
     *
     * **`get_descriptions=0`, like both of its siblings below.** It was the only read on this client without
     * it, and item descriptions cost ~2.3 KB *per item* against an offer stub of ~550 B — measured 4,734 B
     * for a single-item offer on 2026-08-28, of which [SteamReadResponses.singleOfferSnapshot] reads exactly
     * two fields (`trade_offer_state` and `tradeid`) and discards the rest. On the watch cadence that is
     * ~4 KB parsed and thrown away per deal per minute, and it scales with the item count of the trade.
     *
     * It also makes this read the *same document* the offer-axis proven read fetches
     * (`NotaryConfig.offerRead` sets the identical flag), which is what lets a session log's watch body stand
     * in for the proven response when sizing `NotaryConfig.maxRecvDataOnline`. While the two query strings
     * disagreed, the body in the log was ~8.6× the one the prover actually has to decrypt.
     */
    private suspend fun singleOfferSnapshot(credential: SteamCredential, offerId: OfferId): SteamOfferSnapshot? {
        val responseText = httpClient.get("${endpoints.steamApiBaseUrl}${endpoints.getTradeOfferPath}") {
            parameter(endpoints.paramTradeOfferId, offerId.value)
            parameter(endpoints.paramGetDescriptions, 0)
            parameter(endpoints.paramAccessToken, credential.token)
        }.bodyAsText()
        return SteamReadResponses.singleOfferSnapshot(responseText)
    }

    /**
     * Bulk `GetTradeOffers` — the batch read used when many offers are watched at once.
     *
     * **Both directions** are requested: a watched deal this account is buying has a *received* offer, and
     * asking only for sent ones sent every such offer down the per-offer fallback (N extra Steam calls per
     * tick, indefinitely). One list covers both sides for the same single request, and `get_descriptions=0`
     * keeps it to offer stubs rather than item metadata.
     */
    private suspend fun allBulkOfferSnapshots(credential: SteamCredential): Map<OfferId, SteamOfferSnapshot> {
        val responseText = httpClient.get("${endpoints.steamApiBaseUrl}${endpoints.getTradeOffersPath}") {
            parameter(endpoints.paramAccessToken, credential.token)
            parameter(endpoints.paramGetSentOffers, 1)
            parameter(endpoints.paramGetReceivedOffers, 1)
            parameter(endpoints.paramActiveOnly, 0)
            parameter(endpoints.paramGetDescriptions, 0)
        }.bodyAsText()
        return SteamReadResponses.bulkOfferSnapshots(responseText)
    }

    override suspend fun recentTransfers(credential: SteamCredential, maxTrades: Int): List<SteamTransfer> {
        val responseText = httpClient.get("${endpoints.steamApiBaseUrl}${endpoints.getTradeHistoryPath}") {
            parameter(endpoints.paramAccessToken, credential.token)
            parameter(endpoints.paramMaxTrades, maxTrades)
            parameter(endpoints.paramGetDescriptions, 0)
        }.bodyAsText()
        return SteamReadResponses.transfers(responseText)
    }
}
