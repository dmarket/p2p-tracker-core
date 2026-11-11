package com.dmarket.p2p.tracker.client.marketplace

import com.dmarket.p2p.tracker.client.sanitizeHttpFailures
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import okhttp3.OkHttpClient

/**
 * Android factory for the DMarket [KtorMarketplaceClient].
 *
 * Auth goes through [authenticator], which on Android is `AndroidTrackerPlatform.marketplaceAuthenticator`: a
 * [CredentialMarketplaceAuthenticator] over the host's token layer (`HostTokenMarketplaceCredentialProvider`). It
 * attaches the host's token raw in `Authorization` and asks the host for one forced refresh on a 401. The host keeps
 * the only refresh mechanism; the library adds none. [hostOkHttp] is only the transport (the app's interceptors,
 * pinning, proxy): the DMarket app attaches `Authorization` per Retrofit method, so its `OkHttpClient` adds no auth.
 */
fun androidMarketplaceClient(
    hostOkHttp: OkHttpClient,
    baseUrl: String,
    authenticator: MarketplaceAuthenticator,
    requestTimeoutMs: Long = 30_000L,
): KtorMarketplaceClient {
    val httpClient = HttpClient(OkHttp) {
        engine { preconfigured = hostOkHttp }
        // This factory builds its own client, so it does NOT inherit createHttpClient's sanitizing — it
        // must opt in explicitly, or Ktor's non-2xx exceptions carry the full request URL plus the entire
        // response body, and KtorMarketplaceClient's status mapping stops matching.
        sanitizeHttpFailures()
        install(HttpTimeout) { requestTimeoutMillis = requestTimeoutMs }
    }
    return KtorMarketplaceClient(httpClient = httpClient, baseUrl = baseUrl, authenticator = authenticator)
}
