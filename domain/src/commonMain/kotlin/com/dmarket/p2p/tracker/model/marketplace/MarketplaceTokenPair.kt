package com.dmarket.p2p.tracker.model.marketplace

import kotlin.time.Instant

/**
 * A DMarket access/refresh token pair, exactly as the refresh endpoint returns it.
 *
 * The wire shape is `{AuthToken, AuthTokenExpiresAt, RefreshToken, RefreshTokenExpiresAt}` with the two
 * expiries as int64 epoch **seconds serialised as JSON strings**; the mapper normalises them to
 * [Instant] and leaves them `null` when absent or unparseable.
 *
 * The response's `AuthTokenExpiresAt` is deliberately NOT carried here. The authority on the access token's
 * life is [MarketplaceTokenJwt] reading its `exp` claim, and a second, server-supplied opinion would only let
 * the refresh trigger and the post-write anti-loop check disagree. The value the cookies are written with is
 * [refreshTokenExpiresAt] — the frontend gives *both* cookies the refresh token's expiry, and mirroring that
 * byte-for-byte is what keeps the site session alive (see `MarketplaceTokenStore`).
 */
data class MarketplaceTokenPair(val accessToken: String, val refreshToken: String, val refreshTokenExpiresAt: Instant?) {
    init {
        require(accessToken.isNotBlank()) { "accessToken must not be blank" }
        require(refreshToken.isNotBlank()) { "refreshToken must not be blank" }
    }

    /**
     * Redacted. BOTH fields are live credentials and the refresh token is the worse of the two — a ~30-day
     * bearer for the whole DMarket account. A generated `toString()` prints them, and this type travels
     * through exception messages, lifecycle events and the host's crash reporter. Expiries are kept: they
     * are the entire diagnostic value of seeing this object at all.
     */
    override fun toString(): String =
        "MarketplaceTokenPair(accessToken=<redacted>, refreshToken=<redacted>, refreshTokenExpiresAt=$refreshTokenExpiresAt)"
}
