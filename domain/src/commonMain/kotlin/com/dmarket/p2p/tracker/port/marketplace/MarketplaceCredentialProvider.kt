package com.dmarket.p2p.tracker.port.marketplace

import com.dmarket.p2p.tracker.model.marketplace.MarketplaceCredential

/**
 * The single authority on "what DMarket bearer token should the next call use" — and the **one seam a
 * host that already owns token refresh replaces wholesale**.
 *
 * That is the point of this being a port rather than a class. The DMarket mobile applications already
 * have a complete, battle-tested refresh mechanism (Android: a `TokenManager` with a mutex-serialised
 * `getToken(forceRefresh)`, its own storage, its own rotation and 401 handling). Re-implementing any of
 * that inside this library for those hosts would create a *second* refresh authority racing the first
 * over the same rotating credential — the worst possible outcome. So such a host implements this
 * interface by delegation (see `HostTokenMarketplaceCredentialProvider`) and the library's own
 * implementation is never constructed.
 *
 * Hosts with no refresh mechanism of their own (the browser extension) get
 * `DefaultMarketplaceCredentialProvider`, which implements the whole algorithm once in `commonMain` over
 * two thin ports: [MarketplaceTokenStore] (where the platform keeps the pair) and
 * [MarketplaceTokenRefreshClient] (the one HTTP call).
 *
 * ### Contract
 *
 * - **Never throws.** A transient failure is reported by returning the still-usable token, or `null`.
 * - **`null` means "do not send a DMarket request"** — and it is load-bearing, not advisory. The loop's
 *   pre-heartbeat guard is `current() == null`, and `MarketplaceAuthenticator.refreshOnUnauthorized()` is
 *   a null check on [forceRefresh]. An implementation that returns a token it knows is dead (because a
 *   retry latch or a rate limit stopped it from getting a live one) therefore does not degrade
 *   gracefully: it produces `1 + maxRetries` rejected requests on every single wake, forever. When in
 *   doubt, return `null`.
 * - **Fail closed.** [lastRefreshFailedLoggedOut] must be true whenever the implementation knows an
 *   interactive login is required, and that verdict should survive a process restart where the platform
 *   can persist it.
 */
interface MarketplaceCredentialProvider {

    /**
     * The credential to use now, refreshing first if it is close enough to expiry to be worth renewing.
     *
     * Returns `null` when no usable credential can be obtained — logged out, or a refusal the caller must
     * not paper over (see the contract above).
     */
    suspend fun current(): MarketplaceCredential?

    /**
     * Re-acquire regardless of how fresh the current credential looks. Called once after an HTTP 401 from
     * DMarket, via `MarketplaceAuthenticator.refreshOnUnauthorized()`.
     *
     * Returns `null` to let the 401 propagate — which is the right answer both when the user is logged out
     * and when the implementation has already established that refreshing cannot currently succeed.
     */
    suspend fun forceRefresh(): MarketplaceCredential?

    /**
     * `true` when the implementation knows there is no DMarket session a silent background refresh could
     * recover — i.e. interactive login is required.
     *
     * This is the cross-platform "show the login prompt" signal: the core never prompts, the host observes
     * it through `TradeTrackerLoop.needsMarketplaceReLogin` and renders its own UI. A transient error must
     * leave it `false`, or an outage turns into a false "you are signed out" for the whole install base.
     */
    val lastRefreshFailedLoggedOut: Boolean
}
