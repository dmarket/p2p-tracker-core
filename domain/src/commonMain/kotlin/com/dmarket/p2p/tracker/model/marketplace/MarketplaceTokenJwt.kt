package com.dmarket.p2p.tracker.model.marketplace

import com.dmarket.p2p.tracker.model.JwtPayload
import kotlin.time.Instant

/**
 * The DMarket access token's own expiry, read from the `exp` claim of the JWT.
 *
 * **Why this exists at all — the defect it fixes.** The DMarket web frontend writes the access token and
 * the refresh token into two cookies and gives *both* the **refresh** token's expiry (~30 days), on
 * purpose, so a user who does nothing for a day is not logged out
 * (`RefreshableJwtInterceptor.setTokens`: `new Date(+tokens.RefreshTokenExpiresAt * MS_IN_S)` for each).
 * So the `expirationDate` of the cookie carrying the access token says nothing about the access token,
 * which actually lives ~24h. Deriving freshness from the cookie — which is what this library did before
 * — makes every token look fresh for a month: the proactive refresh never fires and only the reactive
 * HTTP-401 path works.
 *
 * The token itself is the only truthful source, and it is a plain JWT (the frontend decodes it the same
 * way to read `attributes.account_id`). Signature is **not** verified: the token was handed to us by the
 * platform that already authenticated the user — see [JwtPayload].
 *
 * Total and non-throwing by design: every caller's answer to "unreadable" is the same as its answer to
 * "expired" — refresh it — so a `null` here must not be distinguishable from a failure.
 */
object MarketplaceTokenJwt {

    /**
     * The `exp` claim as an [Instant], or `null` when [token] is not a readable JWT or carries no numeric
     * `exp`. Never throws.
     *
     * A quoted numeric `exp` (`"exp":"1781697600"`) is accepted: the DMarket APIs serialise int64 epochs
     * as JSON strings elsewhere in the same contract, so tolerating it here costs nothing and removes a
     * whole class of "token looks unreadable" incident.
     */
    fun expiresAtOrNull(token: String): Instant? {
        val payload = JwtPayload.decodeOrNull(token) ?: return null
        return JwtPayload.expiresAtSecondsOrNull(payload)?.let { Instant.fromEpochSeconds(it) }
    }
}
