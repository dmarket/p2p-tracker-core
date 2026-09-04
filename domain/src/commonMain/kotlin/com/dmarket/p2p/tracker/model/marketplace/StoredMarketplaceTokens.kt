package com.dmarket.p2p.tracker.model.marketplace

import kotlin.time.Instant

/**
 * What a platform's token store currently holds — the input side of
 * [com.dmarket.p2p.tracker.port.marketplace.MarketplaceTokenStore].
 *
 * Deliberately NOT the same type as [MarketplaceTokenPair]: a store can legitimately hold an access token
 * with no refresh token beside it (the refresh cookie was deleted, or a platform keeps only the short
 * token), and the two expiries come from different places. Modelling that as the same 4-tuple would force
 * every store to invent values it does not have.
 *
 * @param accessToken the DMarket bearer, or `null` when the store has none.
 * @param refreshToken the durable refresh credential, or `null` when the store has none. With no refresh
 *   token there is nothing to refresh *from*, which is the store's way of saying "interactive login".
 * @param refreshTokenExpiresAt the refresh token's expiry as the platform knows it. On web this is the
 *   refresh cookie's own `expirationDate`, which IS truthful for the refresh token (unlike the access
 *   cookie's — see [MarketplaceTokenJwt]). `null` means "can't tell", treated as usable.
 *
 * The access token's expiry is intentionally absent: it is derived in the shared algorithm from the token
 * itself via [MarketplaceTokenJwt], so no platform can reintroduce the cookie-expiry bug.
 */
data class StoredMarketplaceTokens(val accessToken: String?, val refreshToken: String?, val refreshTokenExpiresAt: Instant?) {
    /** Redacted for the same reason as [MarketplaceTokenPair]; presence is the diagnostic, not the value. */
    override fun toString(): String =
        "StoredMarketplaceTokens(accessToken=${present(accessToken)}, refreshToken=${present(refreshToken)}, " +
            "refreshTokenExpiresAt=$refreshTokenExpiresAt)"

    private fun present(value: String?): String = if (value.isNullOrBlank()) "absent" else "<redacted>"
}
