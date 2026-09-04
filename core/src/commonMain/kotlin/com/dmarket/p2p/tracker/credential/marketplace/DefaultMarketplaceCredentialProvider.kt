package com.dmarket.p2p.tracker.credential.marketplace

import com.dmarket.p2p.tracker.adapter.host.InMemoryMarketplaceRefreshStateStore
import com.dmarket.p2p.tracker.model.TokenFingerprint
import com.dmarket.p2p.tracker.model.marketplace.MarketplaceCredential
import com.dmarket.p2p.tracker.model.marketplace.MarketplaceTokenJwt
import com.dmarket.p2p.tracker.model.marketplace.MarketplaceTokenPair
import com.dmarket.p2p.tracker.model.marketplace.StoredMarketplaceTokens
import com.dmarket.p2p.tracker.port.host.Clock
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceCredentialProvider
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceRefreshRejectedException
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceRefreshStateStore
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceTokenRefreshClient
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceTokenStore
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceTokenStore.WriteOutcome
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The library's own DMarket credential authority: reads the token pair from the platform's store, refreshes
 * it through the DMarket refresh API once the access token nears expiry, and writes the rotated pair back.
 *
 * Used by hosts that do **not** already own a refresh mechanism (the browser extension). A host that does —
 * the DMarket mobile apps — implements [MarketplaceCredentialProvider] by delegation instead
 * ([HostTokenMarketplaceCredentialProvider]), so there is exactly one refresh authority per platform.
 *
 * ## Writing the pair back IS the feature
 *
 * On web the store is the browser cookie jar, **shared with the dmarket.com SPA**. The refresh endpoint
 * rotates the refresh token and does not `Set-Cookie` — the site's own JavaScript writes both cookies from
 * the response body. A refresh that does not write both back therefore leaves the page holding a superseded
 * refresh token, i.e. the extension's background activity signs the user out of the website. That is why
 * [MarketplaceTokenStore.write] takes the whole pair and why its verdict is acted on.
 *
 * ## Collision handling (two writers, one rotating credential)
 *
 * 1. **Single-flight** — one [Mutex] held across the network call, so this client never races itself.
 * 2. **Re-read under the lock** — if the store became fresh while we waited, return it and make no request.
 *    Most collisions die here: the SPA's own refresh completes in ~100 ms.
 * 3. **Compare-and-swap on write**, three ways — see [applyRefreshed]. A store that has become *empty* is
 *    not "somebody rotated", and treating it as such would discard the only live pair in existence.
 * 4. **One retry on refusal**, because the value we presented may simply have been superseded mid-flight.
 * 5. **Rate limit** on our own rotations, persisted ([Config.minRefreshInterval]).
 * 6. **A persisted refusal latch** keyed on the refused token's fingerprint, so a signed-out state costs zero
 *    requests across worker restarts and un-latches itself the moment the store holds a different token.
 * 7. **Optional deference** to a live competing writer ([Config.deferWhileOtherActorActive]), proactive only.
 *
 * **What none of this closes — and it is confirmed, not hypothetical.** The backend DOES void the predecessor
 * refresh token on rotation (answer from the auth team). So a dmarket.com tab that has already refreshed at
 * least once holds its own copy of the refresh token *in memory* rather than re-reading the cookie, and will
 * present that dead copy once its access token expires; the site's interceptor signs the user out on a 401 from
 * the refresh endpoint. Writing both cookies back cannot help that tab, because it never looks at them again.
 *
 * Nothing on this side fixes it. Both fixes are elsewhere, and both are requested:
 * - **frontend** — `SessionService.getRefreshToken()` should read the cookie instead of trusting its in-memory
 *   copy. This is the real fix, and it also closes the multi-tab race the site already has without us.
 * - **backend** — a grace window on the predecessor, which additionally covers the one window no client can
 *   close: a worker killed between the server rotating and our cookie write.
 *
 * Until then this class keeps rotations as rare as it usefully can — one per access-token lifetime, so roughly
 * daily — and re-reads the store on every acquire, so whoever rotated last simply wins.
 *
 * ## Never throws; `null` is load-bearing
 *
 * Every path returns a credential or `null`. `null` means "do not send a DMarket request": the loop's
 * pre-heartbeat guard and the 401 authenticator are both null checks, so returning a token known to be dead
 * would produce `1 + maxRetries` rejected requests on every wake instead of one clean missing-connection
 * verdict.
 */
class DefaultMarketplaceCredentialProvider(
    private val store: MarketplaceTokenStore,
    private val refreshClient: MarketplaceTokenRefreshClient,
    private val clock: Clock,
    private val config: Config,
    private val state: MarketplaceRefreshStateStore = InMemoryMarketplaceRefreshStateStore(),
) : MarketplaceCredentialProvider {

    /**
     * @param usableSkew a token with less than this much life left is not sent at all — the "still usable
     *   right now" floor.
     * @param refreshHeadroom a token with less than this much life left gets refreshed — the trigger. Must
     *   exceed [usableSkew]; otherwise the trigger only fires once the token is already unusable, which is
     *   exactly how the previous arrangement (a 1 h headroom behind a 60 s freshness check) came to never
     *   refresh anything. Enforced in `CredentialConfig`.
     * @param refreshTokenMinLife a refresh token with less than this left counts as spent.
     * @param minRefreshInterval minimum spacing between two of our own refreshes.
     * @param maxTransientFailures after this many consecutive non-refusal failures, stop attempting, so a
     *   permanently broken endpoint surfaces as a connection error instead of costing one futile request per
     *   wake forever.
     * @param deferWhileOtherActorActive consult [MarketplaceTokenStore.otherActorLikelyActive] before a
     *   *proactive* refresh (never before a forced one). Off by default: the API refresh is the mechanism, and
     *   making it conditional on the user's browsing would be the tab dependency this design exists to remove.
     *   It is the containment lever if site-logout reports appear before the frontend fix lands.
     */
    data class Config(
        val usableSkew: Duration,
        val refreshHeadroom: Duration,
        val refreshTokenMinLife: Duration,
        val minRefreshInterval: Duration,
        val maxTransientFailures: Int = 5,
        val deferWhileOtherActorActive: Boolean = false,
    )

    private val mutex = Mutex()

    override var lastRefreshFailedLoggedOut: Boolean = false
        private set

    override suspend fun current(): MarketplaceCredential? = acquire(force = false)

    override suspend fun forceRefresh(): MarketplaceCredential? = acquire(force = true)

    // ---- acquisition -------------------------------------------------------------------------------

    private suspend fun acquire(force: Boolean): MarketplaceCredential? {
        val view = (readStore() ?: return null).view()
        val now = clock.now()

        // The happy path, and the only one that touches nothing but the token store.
        if (!force && !view.needsRefresh(now)) return view.aliveOrNull(now)

        // Nothing to refresh from, or the durable half is spent: interactive login, zero network.
        //
        // This also covers the completely empty store. Note it is NOT the ordinary signed-out shape on web:
        // the site gives the ACCESS cookie the REFRESH token's ~30-day expiry, so a signed-out user usually
        // still has both cookies present with a long-dead token inside — that case reaches the exchange below
        // and is settled by the server.
        val refreshToken = view.refreshToken ?: return loggedOut()
        if (view.refreshTokenSpent(now)) return loggedOut()

        // Guards that decline to make a request. Each returns the current token only while it is genuinely
        // still sendable — never one already past `usableSkew`.
        //
        // The latch is the one that must also **fail closed**: it was armed by a corroborated refusal, and it
        // is persisted precisely so that verdict survives a restart. Once the access token beside it dies
        // there is nothing left to be optimistic about, and the host's re-login signal
        // (`needsMarketplaceReLogin`) reads this flag — leaving it false would show a signed-out user a
        // "cannot reach DMarket" state forever instead of a sign-in prompt. The other guards deliberately do
        // NOT set it: a rate limit or a broken gateway is no evidence about the session.
        //
        // Read the latch once and thread it down: the locked re-check needs the same recorded value, and
        // re-reading it would be a second `storage.local` round trip on every refresh.
        val rejected = state.rejectedRefreshFingerprint()
        if (rejected != null && rejected == TokenFingerprint.of(refreshToken)) {
            return view.usableOrNull(now) ?: loggedOut()
        }
        if (!force && throttled(now)) return view.usableOrNull(now)
        // `force` is exempt from the failure cap on purpose: the cap's own resets all sit downstream of it, so
        // without an exemption a client that once hit it could never try again — not after a restart, not on a
        // 401, not on a debug force tick. The 401 path and force tick are the escape hatch.
        val failures = state.transientFailureCount()
        if (!force && failures >= config.maxTransientFailures) return view.usableOrNull(now)
        // `!force` is load-bearing here, not tidiness: deference is a PROACTIVE-only courtesy. The 401 path
        // and the debug force tick must always be able to insist, because the site's own refresh is driven by
        // its response interceptor — an open but IDLE tab never refreshes, so deferring to it unconditionally
        // would leave a 401 unrecoverable for exactly as long as the user keeps that tab open.
        if (!force && config.deferWhileOtherActorActive && otherActorActive()) return view.usableOrNull(now)

        return refreshUnderLock(force, rejected, failures)
    }

    /**
     * The refresh exchange, serialised.
     *
     * `NonCancellable` because a rotation cancelled between "the server minted a new pair" and "the store
     * holds it" strands that pair while its predecessor may already be void. It covers **cooperative**
     * cancellation only — a `stopTracker` / endpoint-switch cancelling the scope, which happens routinely
     * here. It cannot cover a service worker being terminated, and neither can anything else client-side:
     * that window is dominated by the network round trip.
     */
    private suspend fun refreshUnderLock(force: Boolean, rejectedFingerprint: String?, failures: Int): MarketplaceCredential? =
        mutex.withLock {
            // Re-read: the competing writer, or our own concurrent caller, may have rotated while we waited.
            val view = (readStore() ?: return@withLock null).view()
            val now = clock.now()
            if (!force && !view.needsRefresh(now)) return@withLock view.aliveOrNull(now)

            val presented = view.refreshToken ?: return@withLock loggedOut()
            // Re-check the latch against the fingerprint the caller already read, without another store round trip:
            // a queued caller must see the refusal its predecessor just recorded, and a refusal does not rotate the
            // store — so an unchanged token is exactly the case to bail on.
            if (rejectedFingerprint != null && rejectedFingerprint == TokenFingerprint.of(presented)) {
                return@withLock view.usableOrNull(now) ?: loggedOut()
            }

            withContext(NonCancellable) { exchange(presented, view, failures, allowRetry = true) }
        }

    /**
     * One refresh exchange, plus at most one retry when the refusal turns out to be about a token the store
     * has already replaced — which means what we sent was superseded, not that the session is gone.
     */
    private suspend fun exchange(presented: String, view: TokenView, failures: Int, allowRetry: Boolean): MarketplaceCredential? {
        val pair = try {
            refreshClient.refresh(presented, view.accessToken)
        } catch (_: MarketplaceRefreshRejectedException) {
            val latest = readStore()?.view()
            val nowHeld = latest?.refreshToken
            if (allowRetry && nowHeld != null && nowHeld != presented) {
                // Somebody rotated while our request was in flight — almost always the site's own SPA, which is
                // the other writer of this jar. It has therefore just written a pair, and since rotation voids
                // the predecessor, THAT pair is the only live one.
                //
                // Adopt it rather than rotating again. Retrying unconditionally here would take the one case
                // where the collision resolved itself for free and turn it into a second rotation, voiding the
                // token the site is now using — i.e. manufacturing the exact sign-out this class is trying to
                // avoid. Only retry when the new pair still would not satisfy us, which means the write was not
                // a completed refresh.
                val nowSettled = clock.now()
                if (!latest.needsRefresh(nowSettled)) {
                    state.resetTransientFailures(failures)
                    return latest.aliveOrNull(nowSettled)
                }
                return exchange(nowHeld, latest, failures, allowRetry = false)
            }
            // Corroborated refusal: the store still holds exactly the token the server refused.
            state.setRejectedRefreshFingerprint(TokenFingerprint.of(presented))
            state.resetTransientFailures(failures)
            return loggedOut()
        } catch (_: Throwable) {
            // Everything else is transient by construction — 5xx, 429, a timeout, a WAF 403, a gateway that
            // does not mount the route. None of it is evidence about the session, so the logged-out flag is
            // left alone; the counter is what stops a permanently broken endpoint from being retried on every
            // wake forever. Written from the count the caller already read — no second round trip.
            state.setTransientFailureCount(failures + 1)
            return view.usableOrNull(clock.now())
        }
        return applyRefreshed(pair, presented, failures)
    }

    // ---- write-back --------------------------------------------------------------------------------

    /**
     * Write the rotated pair back, resolving the three ways the store can have moved underneath us.
     *
     * The **absent** branch is the subtle one. It is not "somebody rotated": the same signature is produced by
     * the user explicitly signing out on the website, which deletes both cookies. We restore anyway, because
     * the two outcomes are not symmetric — discarding costs a guaranteed lost session in the common (racing)
     * case, while restoring costs a briefly resurrected one in the rare case, and that self-corrects: if the
     * site's own logout revokes server-side, the restored pair is refused on the next cycle and the refusal
     * latch reports signed-out for real. The asymmetry is now the stronger argument, not the weaker one: since
     * rotation voids the predecessor, once we have rotated OUR pair is the only live credential in existence —
     * discarding it would strand a session nobody else can recover. A user who signed out on the site is not
     * signed back in by this either: no UI changes, and the next 401 settles it.)
     */
    private suspend fun applyRefreshed(pair: MarketplaceTokenPair, presented: String, failures: Int): MarketplaceCredential? {
        val now = clock.now()
        val held = readStore()?.view()
        if (held?.refreshToken != null && held.refreshToken != presented) {
            // Another writer won while we were in flight. Its pair is at least as new as ours, and
            // overwriting it could void the very token the site has just started using. The view we just read
            // IS that pair, so nothing needs re-reading.
            state.resetTransientFailures(failures)
            return held.aliveOrNull(now)
        }

        when (store.writeSafely(pair)) {
            WriteOutcome.WRITTEN -> Unit
            // Verified read-back returned something else: adopt whatever is there now.
            WriteOutcome.LOST_RACE -> {
                state.resetTransientFailures(failures)
                return readStore()?.view()?.aliveOrNull(clock.now())
            }
            // The client cannot write the store at all (a missing permission, an unavailable API). Count it as
            // transient so a build that can never refresh stops hammering — and never report it as a
            // signed-out user, which is what it would look like otherwise.
            WriteOutcome.BLIND -> {
                state.setTransientFailureCount(failures + 1)
                return usableOrNull(pair.accessToken, pair.expiry(), now)
            }
        }

        // Stamped before the check below so the rate limit holds off the next attempt either way — a useless
        // refresh still consumed a rotation.
        state.setLastRefreshedAt(now)
        state.setRejectedRefreshFingerprint(null)

        // Anti-loop: the pair we just stored must actually clear the trigger. If the server handed back a
        // token we would immediately want to refresh again (a past expiry, an unreadable `exp`, a skewed
        // clock), calling this a success re-enters the refresh on the very next call — and on web our own
        // cookie write wakes a cycle, so that is unbounded. Report it as a transient failure instead; the rate
        // limit (just stamped above) then holds the next attempt off.
        //
        // The streak is settled here rather than above, so this branch and the success branch write the count
        // exactly once between them instead of resetting it and immediately bumping it again.
        val expiry = pair.expiry()
        if (needsRefresh(expiry, now)) {
            state.setTransientFailureCount(failures + 1)
            return usableOrNull(pair.accessToken, expiry, now)
        }
        state.resetTransientFailures(failures)

        lastRefreshFailedLoggedOut = false
        return MarketplaceCredential(pair.accessToken, expiry)
    }

    // ---- small helpers -----------------------------------------------------------------------------

    /**
     * A store read plus the derived access-token expiry, so no branch re-derives it (or forgets to).
     *
     * [accessExpiry] is `by lazy` because decoding the JWT is the most expensive thing on this path (base64 +
     * JSON) and two of the views built per refresh are consulted only for their `refreshToken`.
     */
    private inner class TokenView(val accessToken: String?, val refreshToken: String?, private val refreshExpiry: Instant?) {
        val accessExpiry: Instant? by lazy { accessToken?.let { MarketplaceTokenJwt.expiresAtOrNull(it) } }

        fun needsRefresh(now: Instant): Boolean = accessToken == null || needsRefresh(accessExpiry, now)

        fun refreshTokenSpent(now: Instant): Boolean {
            val at = refreshExpiry ?: return false // unknown expiry → assume usable
            return at - config.refreshTokenMinLife <= now
        }

        /** The token if it can still legitimately be sent, else `null` — never one we know is dead. */
        fun usableOrNull(now: Instant): MarketplaceCredential? = usableOrNull(accessToken, accessExpiry, now)

        /**
         * [usableOrNull], and clear the host's re-login signal when it yields something.
         *
         * The single accessor for "hand this token out": it owns both the usable floor and the flag, so the
         * two can never be applied inconsistently across the dozen return sites they used to be spelled out
         * at. A `null` deliberately does NOT set the flag — only a corroborated refusal does.
         */
        fun aliveOrNull(now: Instant): MarketplaceCredential? = usableOrNull(now)?.also { lastRefreshFailedLoggedOut = false }
    }

    private fun StoredMarketplaceTokens.view() = TokenView(
        accessToken = accessToken?.takeIf { it.isNotBlank() },
        refreshToken = refreshToken?.takeIf { it.isNotBlank() },
        refreshExpiry = refreshTokenExpiresAt,
    )

    /**
     * The access token's expiry, from the token itself — never from what the server said alongside it.
     *
     * No fallback to the response's `AuthTokenExpiresAt` on purpose: the anti-loop check in [applyRefreshed]
     * and the trigger in [TokenView] must judge by the same rule, and a token whose `exp` we cannot read is
     * exactly the one to treat as "refresh again", not one to trust a second opinion about.
     */
    private fun MarketplaceTokenPair.expiry(): Instant? = MarketplaceTokenJwt.expiresAtOrNull(accessToken)

    /**
     * `true` when [expiry] is close enough to warrant a refresh. An absent/unreadable expiry counts as
     * "yes": a token whose life cannot be established is precisely the one we must stop trusting.
     */
    private fun needsRefresh(expiry: Instant?, now: Instant): Boolean {
        val at = expiry ?: return true
        return at - config.refreshHeadroom <= now
    }

    private fun usableOrNull(access: String?, expiry: Instant?, now: Instant): MarketplaceCredential? {
        if (access.isNullOrBlank()) return null
        val at = expiry ?: return MarketplaceCredential(access, null)
        return if (at - config.usableSkew <= now) null else MarketplaceCredential(access, expiry)
    }

    private suspend fun throttled(now: Instant): Boolean {
        val last = state.lastRefreshedAt() ?: return false
        return now < last + config.minRefreshInterval
    }

    private fun loggedOut(): MarketplaceCredential? {
        lastRefreshFailedLoggedOut = true
        return null
    }

    private suspend fun readStore(): StoredMarketplaceTokens? = runCatching { store.read() }.getOrNull()

    private suspend fun otherActorActive(): Boolean = runCatching { store.otherActorLikelyActive() }.getOrDefault(false)

    private suspend fun MarketplaceTokenStore.writeSafely(pair: MarketplaceTokenPair): WriteOutcome =
        runCatching { write(pair) }.getOrDefault(WriteOutcome.BLIND)

    /**
     * Clear the transient-failure streak — but only when there is one, since on the persisted store a write is
     * an IPC plus a `storage.onChanged` broadcast to every extension context, and the streak is zero on
     * essentially every successful refresh.
     */
    private suspend fun MarketplaceRefreshStateStore.resetTransientFailures(current: Int) {
        if (current != 0) setTransientFailureCount(0)
    }
}
