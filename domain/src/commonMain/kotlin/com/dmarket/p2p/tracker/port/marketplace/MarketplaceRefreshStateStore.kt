package com.dmarket.p2p.tracker.port.marketplace

import kotlin.time.Instant

/**
 * The little bit of refresh bookkeeping that must survive a process restart.
 *
 * Every entry exists for the same reason: on the web target the service worker dies between wakes, so
 * anything held in memory is forgotten roughly once a minute. A guard that resets that often is not a
 * guard — it is a per-wake retry, which is precisely the defect this library already fixed once on the
 * Steam side (a refused session mint repeating on every wake, forever).
 *
 * **Nothing here is a credential.** A refused token is remembered by
 * [com.dmarket.p2p.tracker.model.TokenFingerprint], not verbatim, because the backing store is
 * contractually non-secret ([com.dmarket.p2p.tracker.port.host.DeviceKeyValueStore]).
 *
 * Every member is defaulted to a no-op so a host supplying its own store keeps compiling; such a host
 * simply loses the restart-durability of these guards.
 */
interface MarketplaceRefreshStateStore {

    /**
     * Fingerprint of the refresh token the server most recently **refused**, or `null` if none.
     *
     * Keyed on the token rather than on a boolean so the latch falsifies itself: the moment the store holds
     * a *different* refresh token — the site's own SPA re-authenticated, or the user signed back in — the
     * fingerprints differ and the next attempt proceeds with no explicit reset. That is why nothing needs to
     * "clear" it on recovery, and why it must not be cleared by an unrelated forced cycle.
     */
    suspend fun rejectedRefreshFingerprint(): String? = null

    suspend fun setRejectedRefreshFingerprint(fingerprint: String?) {
        // no-op by default
    }

    /** When this client last completed a refresh, used to rate-limit our own rotations. */
    suspend fun lastRefreshedAt(): Instant? = null

    suspend fun setLastRefreshedAt(at: Instant) {
        // no-op by default
    }

    /**
     * Consecutive refresh failures that were **not** a refusal of the token — a gateway 404 because the
     * route is not mounted, a WAF 403, a 502, a timeout.
     *
     * Counted, and persisted, so a permanently broken endpoint stops being retried and surfaces as a
     * connection error instead of costing one futile request per wake indefinitely. Kept separate from the
     * refusal latch because the two must never be confused: a refusal means "sign in again", while these
     * mean "something on the way there is broken" and must never produce a logged-out verdict.
     */
    suspend fun transientFailureCount(): Int = 0

    suspend fun setTransientFailureCount(count: Int) {
        // no-op by default
    }

    /**
     * The access token DMarket last refused with a 401, while its own `exp` still lay ahead (a session revoked
     * server-side), or `null`. The provider does not hand that token out again until a hold has passed.
     */
    suspend fun refusedAccess(): RefusedAccessToken? = null

    suspend fun setRefusedAccess(value: RefusedAccessToken?) {
        // no-op by default
    }
}

/** An access token DMarket refused, by [fingerprint] ([com.dmarket.p2p.tracker.model.TokenFingerprint]), and when. */
data class RefusedAccessToken(val fingerprint: String, val at: Instant)
