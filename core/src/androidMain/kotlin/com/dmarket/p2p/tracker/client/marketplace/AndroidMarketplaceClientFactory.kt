// PHASE 3 SCAFFOLD — reference only, NOT compiled until the Android target is enabled in
// build-logic/.../dmarket.kmp.library.gradle.kts (the `androidTarget { ... }` block). KMP ignores this
// source set until then; it is linted by spotless but not type-checked. Finalize against a real
// Android build.
package com.dmarket.p2p.tracker.client.marketplace

import com.dmarket.p2p.tracker.client.sanitizeHttpFailures
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import okhttp3.OkHttpClient

/**
 * Android factory for the DMarket [KtorMarketplaceClient].
 *
 * **Auth is the host's, not ours.** The Android app already owns its OkHttp `Authenticator` + a token
 * interceptor that inject and refresh the DMarket access token. We reuse that single auth path verbatim:
 * the host hands us its configured [OkHttpClient], we wrap it as the Ktor engine, and the marketplace
 * client uses the **default** [TransportManagedMarketplaceAuthenticator] — so the library attaches no
 * header and never retries on 401 (the OkHttp `Authenticator` already did, inside the engine). There is
 * exactly one refresh mechanism, and it is the app's.
 *
 * Contrast with web, where the library owns auth via [CredentialMarketplaceAuthenticator] over the
 * cookie scraper + page-load refresher.
 *
 * Wiring note: pass `marketplaceCredentials = null` to `TradeTrackerCore.createLoop` on Android —
 * there is no library-side credential provider, so `TradeTrackerLoop.needsMarketplaceReLogin` stays
 * `false` and the host drives re-login natively.
 *
 * The Steam reader keeps its **own** separate [HttpClient] (see `createSteamHttpClient`); only the
 * DMarket transport is host-supplied.
 */
fun androidMarketplaceClient(hostOkHttp: OkHttpClient, baseUrl: String, requestTimeoutMs: Long = 30_000L): KtorMarketplaceClient {
    val httpClient = HttpClient(OkHttp) {
        engine { preconfigured = hostOkHttp }
        // This factory builds its own client, so it does NOT inherit createHttpClient's sanitizing — it
        // must opt in explicitly, or Ktor's non-2xx exceptions carry the full request URL plus the entire
        // response body, and KtorMarketplaceClient's status mapping stops matching.
        sanitizeHttpFailures()
        install(HttpTimeout) { requestTimeoutMillis = requestTimeoutMs }
    }
    return KtorMarketplaceClient(httpClient = httpClient, baseUrl = baseUrl)
}
