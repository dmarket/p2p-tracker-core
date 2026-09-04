package com.dmarket.p2p.tracker.client

import com.dmarket.p2p.tracker.adapter.host.NoOpNetworkObserver
import com.dmarket.p2p.tracker.model.ExchangeOrigin
import com.dmarket.p2p.tracker.net.NetworkRedaction
import com.dmarket.p2p.tracker.port.host.NetworkObserver
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout

/**
 * Creates a bare [HttpClient] configured with a 30-second request timeout and [sanitizeHttpFailures]
 * (a non-2xx throws [HttpStatusException], a request timeout throws [HttpTransportException] — neither
 * carrying a URL query secret or a response body; see that function for why Ktor's own `expectSuccess`
 * exceptions are unusable here).
 *
 * No [io.ktor.client.plugins.contentnegotiation.ContentNegotiation] plugin is installed: both Ktor
 * clients in this module use manual [com.dmarket.p2p.tracker.wire.TrackerJson] encode/decode so the
 * exact wire shape is transparent and auditable at the call site.
 *
 * @param engine Defaults to [platformHttpEngine] (OkHttp on JVM, Js/fetch on JS). Inject
 *   [io.ktor.client.engine.mock.MockEngine] in tests.
 * @param requestTimeoutMs The per-request timeout in milliseconds (default 30s). Sourced from
 *   `TrackerConfig.http.requestTimeoutMs` at the wiring site.
 * @param observer An optional passive [NetworkObserver]; when non-no-op the [NetworkObservation]
 *   plugin is installed and reports one redacted [com.dmarket.p2p.tracker.model.NetworkExchange] per
 *   call. Defaults to [NoOpNetworkObserver] (no plugin, zero overhead).
 * @param origin Tags every observed exchange so Steam vs marketplace traffic is never conflated.
 */
fun createHttpClient(
    engine: HttpClientEngine = platformHttpEngine(),
    requestTimeoutMs: Long = 30_000L,
    observer: NetworkObserver = NoOpNetworkObserver,
    origin: ExchangeOrigin = ExchangeOrigin.MARKETPLACE,
    secretParamNames: Set<String> = NetworkRedaction.DEFAULT_SECRET_PARAM_NAMES,
): HttpClient = HttpClient(engine) {
    sanitizeHttpFailures(secretParamNames)
    install(HttpTimeout) {
        requestTimeoutMillis = requestTimeoutMs
    }
    if (observer !== NoOpNetworkObserver) {
        install(NetworkObservation) {
            this.observer = observer
            this.origin = origin
            this.secretParamNames = secretParamNames
        }
    }
}

/**
 * The Steam-facing transport (IEconService reads, inventory, session refresh, offer create/cancel).
 * Kept **separate** from the marketplace client so the two auth models never share a connection or
 * interceptor stack, and so the Steam transport can never carry a DMarket call (and vice-versa) —
 * reinforcing the audit boundary.
 *
 * On the browser (JS) the engine defaults to `credentials:"include"` (see [platformHttpEngine]) so
 * the logged-in Steam cookie session rides along; a cookie-less Steam read is what Steam 403s.
 *
 * @param secretParamNames redaction set for the [NetworkObservation] plugin. Defaults to
 *   [NetworkRedaction.DEFAULT_SECRET_PARAM_NAMES]; a caller that renames the Steam `access_token`
 *   query param augments this so the renamed param is still scrubbed from observed URLs.
 */
fun createSteamHttpClient(
    engine: HttpClientEngine = platformHttpEngine(credentialsInclude = true),
    requestTimeoutMs: Long = 30_000L,
    observer: NetworkObserver = NoOpNetworkObserver,
    secretParamNames: Set<String> = NetworkRedaction.DEFAULT_SECRET_PARAM_NAMES,
): HttpClient = createHttpClient(engine, requestTimeoutMs, observer, ExchangeOrigin.STEAM, secretParamNames)

/**
 * The DMarket-facing transport for [com.dmarket.p2p.tracker.client.marketplace.KtorMarketplaceClient]. Separate from the Steam client (see
 * [createSteamHttpClient]). On mobile the host supplies its own pre-authenticated `HttpClient` instead
 * of this factory (its OkHttp `Authenticator` owns token injection + refresh) — **that client must call
 * [sanitizeHttpFailures] itself**, or Ktor's leaky non-2xx exceptions apply and
 * `KtorMarketplaceClient`'s status mapping stops matching.
 *
 * @param secretParamNames redaction set, forwarded to [createHttpClient]. Present for symmetry with
 *   [createSteamHttpClient]: no DMarket URL carries a query secret today, but the parameter means the
 *   redaction set can never silently diverge between the two transports.
 */
fun createMarketplaceHttpClient(
    engine: HttpClientEngine = platformHttpEngine(),
    requestTimeoutMs: Long = 30_000L,
    observer: NetworkObserver = NoOpNetworkObserver,
    secretParamNames: Set<String> = NetworkRedaction.DEFAULT_SECRET_PARAM_NAMES,
): HttpClient = createHttpClient(engine, requestTimeoutMs, observer, ExchangeOrigin.MARKETPLACE, secretParamNames)
