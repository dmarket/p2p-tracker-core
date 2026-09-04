package com.dmarket.p2p.tracker.credential.steam

import com.dmarket.p2p.tracker.adapter.steam.NoOpSteamSessionRefresher
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.port.host.Clock
import com.dmarket.p2p.tracker.port.host.CredentialVault
import com.dmarket.p2p.tracker.port.steam.SteamSessionRefresher
import com.dmarket.p2p.tracker.port.steam.SteamSessionScraper
import com.dmarket.p2p.tracker.port.steam.SteamWebSessionState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration

/**
 * The single authority for the current [SteamCredential].
 *
 * **Proactive refresh:** [current] checks [SteamCredential.isFresh] against [clock]. If stale (or
 * absent), it re-scrapes and stores the result before returning.
 *
 * **Reactive refresh:** [forceRefresh] always re-scrapes, regardless of freshness. Called by
 * [com.dmarket.p2p.tracker.client.steam.RefreshingSteamReadClient] when Steam returns HTTP 401 — an
 * early-revoked token still has a future `exp` so proactive checks alone won't catch it.
 *
 * **Single-flight:** a [Mutex] ensures only one coroutine scrapes at a time. A second concurrent
 * call blocks, then re-reads the vault under lock and returns the freshly-written credential
 * without triggering a second scrape. A cached [Deferred] is intentionally avoided — a
 * cancelled `Deferred` would silently hang all subsequent callers.
 *
 * **Degradation:**
 * - No usable session ([SteamWebSessionState.GONE]) → nothing can be acquired and nothing here can fix
 *   it, so **no request is made**: both flags are set and `null` is returned (see [doRefresh]).
 * - `scraper.scrape()` returns `null` → user is logged out → [lastRefreshFailedLoggedOut] = `true`,
 *   returns `null`. [sessionMissing] is additionally set only when the session cookie is gone too.
 * - `scraper.scrape()` throws (transient error) → flag stays `false`, returns whatever is in the
 *   vault (possibly stale or `null`). **Never throws**.
 *
 * **Account switch:** guarded on both halves of the acquisition.
 * - *After* a scrape: if the scraped Steam ID differs from the cached one the old entry is cleared before
 *   writing the new one, so the vault is never polluted.
 * - *Before* one: the fresh-cache fast path asks [SteamSessionRefresher.sessionState] whose session the
 *   browser is actually holding and drops the cache on a [SteamWebSessionState.OTHER_ACCOUNT] verdict. Both
 *   halves are needed — without this one the fast path never reaches the other. A cached token stays fresh
 *   by its own JWT clock for ~24h regardless of who is signed in, and a re-login re-`Set-Cookie`s a
 *   perfectly healthy session, so an expiry-only liveness check reads `ALIVE` and keeps serving the
 *   *previous* account's token until it lapses. Callers act on that identity (the loop compares it against
 *   the backend's `linkedSteamId`), so a wrong-account block would outlive the re-login that fixed it.
 *
 * Account binding / validation itself remains the backend's responsibility; this only keeps the credential
 * honest about which session produced it.
 *
 * **Session keep-alive:** the session is renewed on **its own** clock, not this credential's: every
 * [current] reads [SteamSessionRefresher.sessionState] (one cookie lookup, no network) and calls
 * [SteamSessionRefresher.refreshSession] exactly when the session enters its renewal window — including
 * on the fresh-cache fast path, which is the case that matters. Driving it off this credential's
 * staleness alone (the only trigger before) means that whenever the two ~24h clocks drift apart the
 * session expires un-renewed, and a session that has expired cannot be renewed at all. This is what lets
 * a background (no-navigation) host stay logged in. Fire-and-forget: any failure is swallowed and the
 * scrape proceeds regardless, so session-refresh can never break credential acquisition. Defaults to a
 * no-op refresher, which reports `ALIVE` and never renews.
 *
 * **Audit boundary — lib-internal, do NOT export:** [current]/[forceRefresh] hand back the raw
 * [SteamCredential] (with its plaintext token). This type is library machinery wired internally by
 * `TradeTrackerCore.createLoop`; it must stay out of every host-facing surface. The JS facade already
 * enforces this (`SteamCredential` is never `@JsExport`ed — see `JsApi`). When the Android/iOS targets
 * are enabled, this class **MUST** be excluded from the exported AAR/XCFramework API (e.g. `internal`
 * once the debug-harness no longer needs cross-module access, or kept behind the published facade), so
 * a host can never call it to read `.token`. (The `debug-harness` module deliberately exposes the token
 * to exercise the acquisition path — it is a non-shipping debug tool, not part of any published API.)
 */
class SteamCredentialProvider(
    private val vault: CredentialVault,
    private val scraper: SteamSessionScraper,
    private val clock: Clock,
    private val sessionRefresher: SteamSessionRefresher = NoOpSteamSessionRefresher,
    private val skew: Duration = SteamCredential.DEFAULT_SKEW,
) {
    private val mutex = Mutex()

    /**
     * `true` if the last refresh attempt ended with `scraper.scrape()` returning `null` — i.e. no
     * authenticated Steam session was available for a silent background acquisition. Reset to
     * `false` on a successful scrape.
     *
     * This is the cross-platform "interactive login required" signal. The core never prompts the
     * user itself; the host application (extension popup / Android / iOS) observes this (via
     * `TradeTrackerLoop.needsReLogin`) and presents the login UI. Transient errors leave it `false`.
     */
    var lastRefreshFailedLoggedOut: Boolean = false
        private set

    /**
     * `true` when there is **corroborated** proof that no Steam web session exists at all: the
     * `steamLoginSecure` cookie is gone or already expired (see
     * [SteamSessionRefresher.sessionState]) on a path that
     * could not produce a credential. Reset to `false` on any successful acquisition, including the
     * cheap fresh-cache confirmation below.
     *
     * Distinct from [lastRefreshFailedLoggedOut] **on purpose**, and stricter: `scraper.scrape()`
     * returns `null` for a Steam non-2xx (rate limit, 5xx, interstitial), an HTML/regex drift and a
     * malformed JWT just as it does for a real logout, so that flag stays the historical, signal-only
     * "interactive login required" hint. Only this one is fit to drive a user-facing *blocking* state
     * (`TrackerBlock.STEAM_SESSION_MISSING`), where a false positive would tell a logged-in user to
     * sign into Steam.
     */
    var sessionMissing: Boolean = false
        private set

    /**
     * Returns the current credential. If the cached credential is fresh (per
     * [SteamCredential.isFresh]) it is returned immediately — after a cheap confirmation that the
     * Steam web session behind it still exists; otherwise a refresh is performed.
     *
     * Returns `null` when the user is logged out or when no credential has been acquired yet.
     * Never throws.
     */
    suspend fun current(): SteamCredential? {
        val cached = vault.readSteamCredential()
        // A fresh cached token is trusted only while the session cookie behind it still exists — a
        // Steam logout deletes `steamLoginSecure` without expiring the ~24h token already in the vault,
        // and nothing clears the vault on logout, so without this the loop would keep acting on a dead
        // session (and the host would keep claiming tracking is live) for the rest of that token's life.
        // Mirrors DefaultMarketplaceCredentialProvider's store re-read on the DMarket axis.
        if (cached != null && cached.isFresh(clock.now(), skew)) return confirmLiveOrLoggedOut(cached)
        return doRefresh()
    }

    /**
     * Forces a credential refresh regardless of freshness. Used as a reactive fallback after
     * receiving HTTP 401 from Steam. Returns the fresh credential, or `null` if logged out.
     * Never throws.
     */
    suspend fun forceRefresh(): SteamCredential? = doRefresh(force = true)

    /**
     * Ask Steam to mint a NEW web session from the durable "remember me" credential the platform holds —
     * the only way back from a session that expired while nothing was running. Returns `true` when a
     * usable session exists afterwards.
     *
     * Separate from [current] on purpose: it is the one call here that spends requests on a session
     * already known to be gone, so the caller decides *when* — the loop asks once per episode, because a
     * refused mint would otherwise repeat on every wake. Never throws.
     *
     * @param expected the account the caller is entitled to act as (the backend's `linkedSteamId`), when it
     *   knows one. The durable credential names whichever account the browser last remembered, which is not
     *   necessarily that one — so a session minted for **somebody else** is reported as **not** recovered:
     *   the flags are left as they were, nothing is cached, and the host keeps prompting. Without this a mint
     *   could quietly re-establish the very account the user is signing out of. `null` means "no opinion"
     *   (the historical behaviour: any usable session counts).
     */
    suspend fun mintSession(expected: SteamId? = null): Boolean = mutex.withLock {
        runCatching { sessionRefresher.refreshSession(force = true) }
        // One zero-network read answers both questions at once: is there a session, and is it ours.
        val recovered = when (sessionState(expected)) {
            SteamWebSessionState.GONE, SteamWebSessionState.OTHER_ACCOUNT -> false
            SteamWebSessionState.ALIVE, SteamWebSessionState.NEEDS_REFRESH -> true
        }
        if (recovered) sessionMissing = false
        recovered
    }

    /**
     * `true` while the browser's Steam **web session** — the `steamLoginSecure` cookie — belongs to
     * [expected], the account the caller is about to act as.
     *
     * The **write** axis's identity check, and the reason it is exposed separately from [current]: the two
     * Steam writes (`create_offer` / `cancel_offer`) POST with the ambient cookie session and never use the
     * [SteamCredential] they are handed, so a check on the *token's* id — the backend's `linkedSteamId`
     * against `subjectSteamId` — can read MATCH while the cookie is another account's and the write lands
     * there. [current] already refuses to hand out a credential the cookie has moved away from, which is
     * what converges the client; this is for the write sites that need the answer *at the moment of
     * writing*, since an account switch can happen after the credential was acquired. One cookie read,
     * no network.
     *
     * Fail-open, per the port's contract: an unreadable cookie or an unknown session reports `true`, so a
     * cookie-store hiccup can never block a legitimate write. Only a session positively identified as
     * somebody else's returns `false`.
     */
    suspend fun sessionBelongsTo(expected: SteamId): Boolean = sessionState(expected) != SteamWebSessionState.OTHER_ACCOUNT

    // ---- private -----------------------------------------------------------------------------------

    /**
     * The fresh-cache path: one zero-network read of the session's own state
     * ([SteamSessionRefresher.sessionState]) decides whether the cached credential can be trusted — and
     * whether the session behind it is due for renewal.
     *
     * - `ALIVE` → the fresh cache stands, [sessionMissing] clears, nothing else happens.
     * - `OTHER_ACCOUNT` → the browser signed into a different Steam account. Nothing about the cached
     *   credential looks wrong (its JWT is still fresh) and no other signal will ever say otherwise, so this
     *   is the only place the switch can be caught before the token lapses ~24h later. Clear and re-scrape
     *   inline, so the caller gets the new account's credential on this very call.
     * - `NEEDS_REFRESH` → renew the session **now**, on the session's own clock. This is the only place
     *   that can: hanging the keep-alive off the scraped credential's staleness (the sole trigger before)
     *   means that whenever the two ~24h clocks drift apart, the session expires un-renewed and is lost
     *   for good. The cached credential is returned either way — it is still fresh, and a failed re-mint
     *   costs nothing but is retried on the next cycle, bounded by the headroom window.
     * - `GONE` → report logged-out so the loop surfaces it; no request, nothing here can restore it.
     *
     * Locked so the verdict can't race a concurrent refresh: if the vault moved on while we waited, we
     * trust the new value.
     */
    private suspend fun confirmLiveOrLoggedOut(fresh: SteamCredential): SteamCredential? = mutex.withLock {
        val stored = vault.readSteamCredential()
        if (stored != null && stored != fresh) return@withLock stored
        when (sessionState(fresh.subjectSteamId)) {
            SteamWebSessionState.GONE -> {
                sessionMissing = true
                vault.clearSteamCredential() // see refreshLocked's GONE branch — same reason.
                null
            }

            // The session is healthy but belongs to somebody else: the user signed into another Steam
            // account. The cached token is not stale by its own clock (it has up to ~24h left) and nothing
            // else will ever notice — this is the ONE signal that can invalidate it. Discard and re-acquire
            // from the session now in place, on this same call, so the caller's very next decision is made
            // on the right account instead of a cycle later.
            SteamWebSessionState.OTHER_ACCOUNT -> {
                // Cleared BEFORE the re-scrape, deliberately: reads authenticate with this token while the
                // Steam writes ride the browser cookie, so the two must never point at different accounts.
                // If the scrape fails we hand back null — never the previous account's token.
                vault.clearSteamCredential()
                sessionMissing = false
                refreshLocked(force = true)
            }

            SteamWebSessionState.NEEDS_REFRESH -> {
                sessionMissing = false
                runCatching { sessionRefresher.refreshSession() }
                fresh
            }

            SteamWebSessionState.ALIVE -> {
                sessionMissing = false
                fresh
            }
        }
    }

    /** [SteamSessionRefresher.sessionState] with the port's fail-open contract enforced locally. */
    private suspend fun sessionState(expectedSteamId: SteamId? = null): SteamWebSessionState =
        runCatching { sessionRefresher.sessionState(expectedSteamId) }.getOrDefault(SteamWebSessionState.ALIVE)

    /**
     * @param force When `true` (forceRefresh path) skip the freshness re-check under lock so
     *   the scraper is always called. When `false` (current path) a second concurrent caller
     *   that acquired the lock after a refresh can short-circuit.
     */
    private suspend fun doRefresh(force: Boolean = false): SteamCredential? = mutex.withLock { refreshLocked(force) }

    /**
     * [doRefresh]'s body, split out so a caller that **already holds** [mutex] can re-scrape without
     * deadlocking: [Mutex] is not reentrant, and [confirmLiveOrLoggedOut] runs under the same lock.
     * Every entry point into this must hold [mutex].
     */
    private suspend fun refreshLocked(force: Boolean): SteamCredential? {
        // Re-check under lock: another coroutine may have already refreshed while we waited.
        // Skipped on the force path so forceRefresh always scrapes.
        val cached = vault.readSteamCredential()
        if (!force && cached != null && cached.isFresh(clock.now(), skew)) return cached

        // No usable session ⇒ neither call below can succeed, so spend no requests on them (one free
        // cookie read decides). [sessionRefresher] RENEWS a live session — that is what its own expiry
        // self-gate is for — and Steam answers a renew for a session that is already gone with
        // `InvalidParam`, even when the durable refresh cookie is still valid (verified on real traffic).
        // The scrape then reads its token from a page Steam only serves to a logged-in session, so it can
        // only ever come back empty. Getting out of this state needs Steam's own login flow, which the host
        // prompts for; the host also watches the session cookie, so the loop is woken the moment one
        // reappears — retrying on every wake buys no recovery speed, it just hammers Steam.
        val state = sessionState()
        if (state == SteamWebSessionState.GONE) {
            lastRefreshFailedLoggedOut = true
            sessionMissing = true
            // Drop the cached token with the session it belonged to. A token whose session is gone is
            // unusable by definition, and keeping it only preserves a stale *identity*: if the user comes
            // back as a different account, this entry is what the fresh-cache path would otherwise hand out
            // for the rest of its ~24h life.
            vault.clearSteamCredential()
            return null
        }

        // Best-effort: keep the Steam web session cookie alive before the scrape that relies on it — only
        // when it is actually due (an ALIVE session would just re-read the cookie and report NOT_NEEDED).
        // Never let a refresh failure abort credential acquisition — the scrape still runs.
        if (state == SteamWebSessionState.NEEDS_REFRESH) runCatching { sessionRefresher.refreshSession() }

        val scraped = try {
            scraper.scrape()
        } catch (_: Exception) {
            // Transient infra error: don't flip the logged-out flag; return stale-or-null vault.
            return vault.readSteamCredential()
        }

        if (scraped == null) {
            // Scraper explicitly signalled logged-out.
            lastRefreshFailedLoggedOut = true
            // Corroborate before claiming the *session* is gone: a null scrape is also what a Steam
            // non-2xx / HTML drift / bad token regex looks like. Read AFTER the refresh attempt above, so
            // a successful re-mint counts. Cookie still there → signal-only, no blocking state (and a
            // returned cookie clears an earlier logged-out verdict, so it can't pin a stale prompt).
            sessionMissing = sessionState() == SteamWebSessionState.GONE
            return null
        }

        // Account switch: clear the old entry before writing the new one.
        val existing = vault.readSteamCredential()
        if (existing != null && existing.subjectSteamId != scraped.subjectSteamId) {
            vault.clearSteamCredential()
        }

        vault.writeSteamCredential(scraped)
        lastRefreshFailedLoggedOut = false
        sessionMissing = false
        return scraped
    }
}
