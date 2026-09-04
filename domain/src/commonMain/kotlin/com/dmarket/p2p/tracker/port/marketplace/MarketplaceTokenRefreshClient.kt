package com.dmarket.p2p.tracker.port.marketplace

import com.dmarket.p2p.tracker.model.marketplace.MarketplaceTokenPair

/**
 * The DMarket token-refresh call — `POST {base}/marketplace-api/v1/refresh-token` with
 * `{"RefreshToken": "…"}` — as a port, so the shared refresh algorithm has no Ktor dependency and can be
 * unit-tested without a transport.
 *
 * **Deliberately NOT a method on [MarketplaceClient].** Two reasons, and the first is structural:
 * `MarketplaceClient` calls are authenticated through `MarketplaceAuthenticator`, which asks
 * [MarketplaceCredentialProvider] for a token — so routing refresh through it would close a cycle
 * (client → authenticator → provider → client) whose 401 handler would re-enter the very refresh that is
 * failing. The DMarket Android application solved the identical problem the identical way: its refresh
 * runs on a separate HTTP client that deliberately has no `Authenticator` installed. Second, this endpoint
 * lives outside the audited C1 surface (`/exchange/v1/p2p/ext/`) and needs no bearer of its own — the
 * refresh token in the body *is* the credential — so keeping it off that interface keeps the audit
 * boundary legible.
 *
 * Implementations therefore must **not** install an authenticator and must **not** retry on 401.
 */
interface MarketplaceTokenRefreshClient {

    /**
     * Exchange [refreshToken] for a fresh pair.
     *
     * @param accessTokenOrNull the access token currently held, attached as `Authorization` exactly as the
     *   web frontend does (raw, no `Bearer ` prefix) even when it is already expired — that is the only
     *   wire shape observed to work. `null` when the store holds none, in which case no header is sent.
     *
     * @throws MarketplaceRefreshRejectedException when the server refused the refresh token itself — the
     *   only outcome that may be read as "interactive login required".
     * @throws Exception for everything else (transport failure, 5xx, 429, an unexpected status). The caller
     *   classifies these as transient and must never turn one into a logged-out verdict.
     */
    suspend fun refresh(refreshToken: String, accessTokenOrNull: String?): MarketplaceTokenPair
}

/**
 * The server refused the presented refresh token: HTTP 401, or a 200 whose body carries an error `Code`
 * instead of an `AuthToken` (the DMarket APIs do answer that way — the Android client's response entity
 * models `Code`/`Message` alongside the tokens).
 *
 * Separate from every other failure because it is the **only** one that may be surfaced to the user as
 * "sign in again". A 403 from a WAF, a 404 from a gateway that does not mount the route, a 502 — all of
 * those must stay transient, or a single infrastructure change becomes a mass false logout.
 *
 * Carries no body and no cause: the request that produced it contained a ~30-day account credential.
 */
class MarketplaceRefreshRejectedException(val statusCode: Int, val code: String? = null) :
    Exception("DMarket refused the refresh token (status=$statusCode, code=${code ?: "none"})")
