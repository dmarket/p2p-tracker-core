package com.dmarket.p2p.tracker.client.marketplace

import com.dmarket.p2p.tracker.port.marketplace.MarketplaceCredentialProvider

/**
 * The DMarket-call auth strategy for [KtorMarketplaceClient]. Decouples *how* a request is
 * authenticated from the client, so each platform supplies the single auth path that fits it:
 *
 * - **Web** ([CredentialMarketplaceAuthenticator]): the library owns auth — it reads the `dm-trade-token`
 *   cookie and refreshes it through the DMarket refresh API (see `DefaultMarketplaceCredentialProvider`),
 *   attaching the JWT raw in `Authorization` (no `Bearer ` scheme — the live gateway parses the header value
 *   as the token itself).
 * - **Mobile**: the host app already owns the token and its refresh, so it supplies its own
 *   [MarketplaceCredentialProvider] (`HostTokenMarketplaceCredentialProvider`) and this same web-shaped
 *   authenticator reads through it — one auth source of truth, no duplicated refresh. A host that instead
 *   authenticates inside its own transport stack uses [TransportManagedMarketplaceAuthenticator], which
 *   attaches nothing and never retries on 401.
 * - **Headless** ([TradingApiAuthenticator]): every request is signed with a DMarket Trading API key; nothing
 *   is refreshed.
 *
 * Deliberately free of Ktor types so it stays trivially unit-testable and the audit surface is obvious.
 */
interface MarketplaceAuthenticator {
    /**
     * The bearer token to attach to the next request, or `null` to send it unauthenticated — either
     * because the transport authenticates itself (mobile) or because no session is available (logged out).
     */
    suspend fun tokenOrNull(): String?

    /**
     * The auth headers for one request. Called once per attempt, a retry included, so a signature is never
     * reused.
     *
     * The default sends [tokenOrNull] raw in `Authorization` (no `Bearer ` scheme), and nothing when it is
     * `null`. A request-signing strategy ([TradingApiAuthenticator]) overrides this: it sees the method, the path
     * with its query and the exact body it has to sign.
     */
    suspend fun headersFor(request: MarketplaceRequest): Map<String, String> =
        tokenOrNull()?.let { mapOf(AUTHORIZATION_HEADER to it) }.orEmpty()

    /**
     * Invoked once after an HTTP 401. Return `true` to retry the request a single time (the implementation
     * has just refreshed the credential), or `false` to let the 401 propagate — the latter when the
     * transport already refreshed+retried inside the engine, or when the user is logged out.
     */
    suspend fun refreshOnUnauthorized(): Boolean
}

/**
 * One DMarket request as an authenticator sees it: [method] in upper case, [pathAndQuery] exactly as it goes
 * on the wire (the encoded path plus `?query` when there is one, no scheme or host) and the [body] string sent,
 * empty for a GET.
 */
data class MarketplaceRequest(val method: String, val pathAndQuery: String, val body: String)

private const val AUTHORIZATION_HEADER = "Authorization"

/**
 * Authenticator backed by a [MarketplaceCredentialProvider]: the token comes from whichever provider the
 * host wired (the library's own, or the host's own token layer), and a 401 asks it once for a forced
 * refresh. Surfacing of the logged-out state (`needsMarketplaceReLogin`) is unchanged — the provider owns
 * that flag.
 *
 * Both methods are null checks on the provider's return value, which is why
 * [MarketplaceCredentialProvider] requires `null` rather than a known-dead token: a non-null dead token
 * here turns one clean missing-connection verdict into `1 + maxRetries` rejected requests per wake.
 */
class CredentialMarketplaceAuthenticator(private val provider: MarketplaceCredentialProvider) : MarketplaceAuthenticator {
    override suspend fun tokenOrNull(): String? = provider.current()?.token

    override suspend fun refreshOnUnauthorized(): Boolean = provider.forceRefresh() != null
}

/**
 * The default authenticator: attaches no header and never retries, because the **HTTP transport** owns
 * auth. This is the mobile path — the host app's OkHttp `Authenticator` + token interceptor inject and
 * refresh the DMarket token before Ktor ever sees the request — and the right default for any host that
 * authenticates at the transport layer.
 */
object TransportManagedMarketplaceAuthenticator : MarketplaceAuthenticator {
    override suspend fun tokenOrNull(): String? = null

    override suspend fun refreshOnUnauthorized(): Boolean = false
}
