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
 *   authenticator reads through it — one auth source of truth, no duplicated refresh.
 * - **Headless** ([TradingApiAuthenticator]): every request is signed with a DMarket Trading API key; nothing
 *   is refreshed.
 *
 * Deliberately free of Ktor types so it stays trivially unit-testable and the audit surface is obvious.
 */
interface MarketplaceAuthenticator {
    /**
     * The token to attach to the next request, or `null` to send it unauthenticated — either because the
     * transport authenticates itself or because no session is available (logged out).
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
     * Invoked after an HTTP 401 with [rejectedToken], the `Authorization` value of the refused request (`null` when
     * it carried none). Return `true` to retry the request (the implementation now holds a credential to send), or
     * `false` to let the 401 propagate — the latter when the transport already refreshed+retried inside the engine,
     * or when the user is logged out.
     */
    suspend fun refreshOnUnauthorized(rejectedToken: String?): Boolean
}

/**
 * One DMarket request as an authenticator sees it: [method] in upper case, [pathAndQuery] exactly as it goes
 * on the wire (the encoded path plus `?query` when there is one, no scheme or host) and the [body] string sent,
 * empty for a GET.
 */
data class MarketplaceRequest(val method: String, val pathAndQuery: String, val body: String)

internal const val AUTHORIZATION_HEADER = "Authorization"

/**
 * Authenticator backed by a [MarketplaceCredentialProvider]: the token comes from whichever provider the
 * host wired (the library's own, or the host's own token layer), and a 401 hands it the refused token, so a host
 * that refreshed in the meantime answers with its newer token instead of refreshing a second time. Surfacing of the logged-out state (`needsMarketplaceReLogin`) is unchanged — the provider owns
 * that flag.
 *
 * Both methods are null checks on the provider's return value, which is why
 * [MarketplaceCredentialProvider] requires `null` rather than a known-dead token: a non-null dead token
 * here turns one clean missing-connection verdict into `1 + maxRetries` rejected requests per wake.
 */
class CredentialMarketplaceAuthenticator(private val provider: MarketplaceCredentialProvider) : MarketplaceAuthenticator {
    override suspend fun tokenOrNull(): String? = provider.current()?.token

    override suspend fun refreshOnUnauthorized(rejectedToken: String?): Boolean {
        val refreshed = provider.forceRefresh(rejectedToken)
        // A refresh that failed for a temporary reason is no "unauthorized" verdict: the call fails as a network blip would.
        if (refreshed == null && provider.lastFailureTransient) throw MarketplaceTokenUnavailableException()
        return refreshed != null
    }
}

/**
 * Thrown by [CredentialMarketplaceAuthenticator] when the refresh after a 401 failed for a temporary reason
 * ([MarketplaceCredentialProvider.lastFailureTransient]): the request fails like a network error, not as
 * unauthorized, so it raises no sign-in prompt.
 */
class MarketplaceTokenUnavailableException : RuntimeException("DMarket token temporarily unavailable")

/**
 * The default authenticator: attaches no header and never retries. Only for a transport that authenticates
 * every request itself; no shipped host does. The Android app attaches `Authorization` per Retrofit method and has
 * no auth interceptor, so it goes through [CredentialMarketplaceAuthenticator] like the others.
 */
object TransportManagedMarketplaceAuthenticator : MarketplaceAuthenticator {
    override suspend fun tokenOrNull(): String? = null

    override suspend fun refreshOnUnauthorized(rejectedToken: String?): Boolean = false
}
