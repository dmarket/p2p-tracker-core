package com.dmarket.p2p.tracker.port.steam

import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.port.SessionRefreshOutcome

/**
 * Keeps the **Steam web session cookie** (`steamLoginSecure`) alive so background work survives
 * without forcing the user to re-login.
 *
 * The tracker authenticates its Steam reads/writes against the browser's logged-in cookie session
 * (see `KtorSteamSessionScraper` / `FetchSteamOfferCanceller`). In the foreground the browser
 * re-mints that ~24h cookie itself on navigation; in an MV3 **background** service worker nothing
 * navigates, so the cookie silently expires and the next scrape reports "logged out". This port
 * re-mints the session ahead of that, **without ever reading the durable refresh token**
 * (`steamRefresh_steam`) — the highest-sensitivity credential, kept out of this client by design
 * (the audit boundary). The web actual triggers Steam's own refresh flow and mirrors the resulting
 * cookie across the Steam web domains; a future native actual mints from an app-held refresh token
 * and writes the cookie via the platform cookie store.
 *
 * Contract: best-effort and idempotent. Implementations **must not throw** on the common paths —
 * a refresh failure must never break credential acquisition; it just leaves the session as-is for
 * the next attempt.
 */
interface SteamSessionRefresher {
    /**
     * Renew the Steam web session. Self-gating: with plenty of life left this must do nothing and report
     * [SessionRefreshOutcome.NOT_NEEDED].
     *
     * @param force run the handshake even though the session is [SteamWebSessionState.GONE] — i.e. ask
     *   Steam to mint a NEW session from the durable "remember me" credential the platform still holds,
     *   rather than renew a live one. This is the only way back from a session that expired while nothing
     *   was running; callers must bound how often they ask, since a refused mint would otherwise repeat
     *   on every wake.
     */
    suspend fun refreshSession(force: Boolean = false): SessionRefreshOutcome

    /**
     * Cheap, **zero-network** read of the session's own state (see [SteamWebSessionState]) — one cookie
     * lookup, judged from the token embedded in it.
     *
     * Three jobs, and the reason this exists rather than a bare "is the cookie there":
     * 1. **Liveness.** The Steam analogue of the DMarket side's cached-credential check (see
     *    `DefaultMarketplaceCredentialProvider`): a Steam logout deletes the session
     *    cookie without expiring the scraped access token already in the vault, so a provider that
     *    trusts a still-fresh cached credential would keep acting on a dead session for the remainder of
     *    that token's ~24h life — and the host would never learn about it.
     * 2. **Scheduling the keep-alive.** [SteamWebSessionState.NEEDS_REFRESH] tells the caller when to
     *    call [refreshSession], so the renewal happens on the *session's* clock. Hanging it off the
     *    scraped credential's staleness instead lets the session expire un-renewed whenever the two
     *    clocks drift apart, which loses the session outright — and once it is gone, nothing here can
     *    bring it back.
     * 3. **Identity.** Expiry alone cannot tell an account *switch* from a renewal: Steam re-`Set-Cookie`s
     *    `steamLoginSecure` for whoever signs in next, so after a re-login the cookie reads perfectly
     *    healthy while the caller's cached credential still belongs to the previous account. Pass
     *    [expectedSteamId] and a divergence is reported as [SteamWebSessionState.OTHER_ACCOUNT], which is
     *    the only signal that can invalidate a credential that has not yet expired. Omit it (the default)
     *    for a pure liveness reading — what the re-scrape and mint paths want, since they learn the
     *    identity first-hand from the page they fetch.
     *
     * @param expectedSteamId the Steam id the caller believes the session belongs to (its cached
     *   credential's subject). `null` means "don't judge identity", and implementations must then never
     *   report [SteamWebSessionState.OTHER_ACCOUNT].
     *
     * Deliberately NOT expressed as [refreshSession]'s outcome: that call is only cheap while the
     * session clears its own headroom, and its `NOT_LOGGED_IN` verdict is also reachable for a *live*
     * session whose re-mint keeps failing (e.g. a host whose anti-CSRF header rewrite regressed), which
     * would report a signed-in user as signed out.
     *
     * Contract: best-effort and **fail-open** — report [SteamWebSessionState.ALIVE] whenever the answer
     * is unknown, so a cookie-store hiccup can never manufacture a logged-out verdict (and
     * [refreshSession] still self-gates). Must not throw. Defaults to `ALIVE` for hosts that cannot
     * inspect the session store.
     *
     * TODO(mobile): the deferred iOS/Android actuals inherit this default, so they answer `ALIVE` and never
     *  report `OTHER_ACCOUNT` — a wrong-account credential would stay cached there for its full life. Wire
     *  their cookie stores through `SteamWebSessionGateway` when those targets are enabled.
     */
    suspend fun sessionState(expectedSteamId: SteamId? = null): SteamWebSessionState = SteamWebSessionState.ALIVE
}
