package com.dmarket.p2p.tracker.client.marketplace

import com.dmarket.p2p.tracker.adapter.platformKeyValueStore
import com.dmarket.p2p.tracker.client.createHttpClient
import com.dmarket.p2p.tracker.config.TrackerConfig
import com.dmarket.p2p.tracker.credential.marketplace.DefaultMarketplaceCredentialProvider
import com.dmarket.p2p.tracker.credential.marketplace.PersistedMarketplaceRefreshStateStore
import com.dmarket.p2p.tracker.model.ExchangeOrigin
import com.dmarket.p2p.tracker.port.host.Clock
import com.dmarket.p2p.tracker.port.host.NetworkObserver
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceCredentialProvider

/**
 * The one place the browser's DMarket credential chain is assembled, so the three wiring sites (the loop
 * factory, the standalone marketplace-client factory, and the debug harness) cannot drift apart — they did
 * before, each repeating a four-argument refresher construction.
 *
 * @param baseUrl the marketplace API base the refresh path is appended to.
 * @param networkObserver passed to the refresh transport so the exchange shows up in the host's network log
 *   like every other call (redacted — see `NetworkRedaction`, which knows this endpoint's field names).
 */
fun createBrowserMarketplaceCredentials(
    baseUrl: String,
    config: TrackerConfig,
    clock: Clock,
    networkObserver: NetworkObserver,
): MarketplaceCredentialProvider {
    val scrape = config.marketplaceScrape
    // Resolution + origin allow-listing happen here, once — see `resolveRefreshUrl`, which is total and owns
    // its own fallback, so a bad config value degrades to the compiled endpoint instead of failing the boot.
    val refreshUrl = resolveRefreshUrl(
        apiBaseUrl = baseUrl,
        siteUrl = scrape.refreshUrl,
        path = scrape.tokenRefreshPath,
        override = scrape.tokenRefreshUrl,
    )

    return DefaultMarketplaceCredentialProvider(
        store = FetchMarketplaceCookieTokenStore(scrape),
        refreshClient = KtorMarketplaceTokenRefreshClient(
            // A transport of its own, deliberately: no authenticator and no 401 retry, so the call the 401
            // path depends on can never re-enter it.
            httpClient = {
                createHttpClient(
                    requestTimeoutMs = config.http.requestTimeoutMs.toLong(),
                    observer = networkObserver,
                    origin = ExchangeOrigin.MARKETPLACE,
                )
            },
            refreshUrl = refreshUrl,
        ),
        clock = clock,
        config = DefaultMarketplaceCredentialProvider.Config(
            usableSkew = config.credentials.marketplaceSkew,
            refreshHeadroom = config.credentials.marketplaceSessionGateHeadroom,
            refreshTokenMinLife = config.credentials.marketplaceRefreshMinLife,
            minRefreshInterval = config.credentials.marketplaceRefreshMinInterval,
            deferWhileOtherActorActive = scrape.deferRefreshWhileSiteTabOpen,
        ),
        state = PersistedMarketplaceRefreshStateStore(platformKeyValueStore()),
    )
}
