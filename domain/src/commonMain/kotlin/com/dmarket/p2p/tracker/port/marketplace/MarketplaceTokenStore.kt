package com.dmarket.p2p.tracker.port.marketplace

import com.dmarket.p2p.tracker.model.marketplace.MarketplaceTokenPair
import com.dmarket.p2p.tracker.model.marketplace.StoredMarketplaceTokens

/**
 * Where the platform keeps the DMarket token pair — the thin IO seam under
 * `DefaultMarketplaceCredentialProvider`. Only hosts that use the library's own refresh algorithm need
 * this; a host that owns refresh replaces [MarketplaceCredentialProvider] instead and never sees this port.
 *
 * **On web this store is SHARED with the dmarket.com single-page app**: it is the browser cookie jar, and
 * the site's own JavaScript reads and writes the very same two cookies. Everything unusual about this port
 * follows from that one fact:
 * - [write] is not bookkeeping, it is how the *site* keeps working. The refresh endpoint rotates the
 *   refresh token and does **not** `Set-Cookie`; the frontend writes both cookies from the response body.
 *   A refresh that does not write both back leaves the page holding a superseded refresh token.
 * - [read] is called again immediately before every write, because another writer may have moved in
 *   between (compare-and-swap).
 * - [otherActorLikelyActive] exists so the algorithm can decline to rotate at all while the other writer
 *   is plausibly live.
 *
 * A private, single-writer store (a mobile keychain) satisfies this interface trivially — but note that
 * the CAS and the deference above then guard against nothing, which is a hint that such a host probably
 * wants [MarketplaceCredentialProvider] instead.
 *
 * Implementations must be best-effort: return `null` / [WriteOutcome.BLIND] rather than throwing.
 */
interface MarketplaceTokenStore {

    /** What the store holds now, or `null` if it cannot be read at all (not the same as "empty"). */
    suspend fun read(): StoredMarketplaceTokens?

    /**
     * Persist [tokens] so a subsequent [read] observes them — and, where the store is shared, so the other
     * reader does too.
     *
     * Implementations must verify by reading back, because "the write was accepted" and "the value is now
     * there" are different claims when a second writer exists.
     */
    suspend fun write(tokens: MarketplaceTokenPair): WriteOutcome

    /**
     * `true` when another writer of this store is plausibly active right now (web: a dmarket.com tab is
     * open, so its SPA will refresh the session itself).
     *
     * Lets the algorithm avoid rotating a credential somebody else is about to rotate. Advisory and racy by
     * nature — it can only ever reduce collisions, never eliminate them — so it is consulted only when the
     * configuration asks for it. Defaults to `false`: a platform that cannot tell must not claim there is a
     * competitor, or it would decline to refresh forever.
     *
     * **Currently unused in every shipping configuration** — `MarketplaceScrapeConfig.deferRefreshWhileSiteTabOpen`
     * defaults to `false`, and the mechanism that actually resolves the two-writer race is the locked re-read plus
     * the compare-and-swap on [write]. Do not assume it is load-bearing.
     *
     * It is the containment lever for a confirmed backend behaviour — rotation voids the predecessor refresh
     * token — whose only real fixes are on the frontend (re-read the cookie instead of an in-memory copy) and the
     * backend (a grace window). Note it must gate proactive refreshes only: an open tab that is idle never
     * refreshes, so deferring a *forced* refresh to it would strand a 401.
     */
    suspend fun otherActorLikelyActive(): Boolean = false

    /** The outcome of [write]. */
    enum class WriteOutcome {
        /** Written and verified: a read-back returned what we wrote. */
        WRITTEN,

        /**
         * The write went out but a read-back returned something else — another writer won. The caller must
         * adopt what the store now holds rather than retrying, since the other value is at least as new.
         */
        LOST_RACE,

        /**
         * The store could not be written or could not be verified — a missing permission, an unavailable
         * API. Distinct from [LOST_RACE] on purpose: this is a broken *client*, and reporting it as a lost
         * race (or, worse, as a logged-out user) hides a build that can never refresh.
         */
        BLIND,
    }
}
