package com.dmarket.p2p.tracker.model.marketplace

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The DMarket marketplace credential (a bearer JWT) used for client↔backend calls under
 * `/exchange/v1/p2p/ext/`.
 *
 * Read from the platform's token store and refreshed through the DMarket refresh API — see
 * `MarketplaceTokenStore` / `DefaultMarketplaceCredentialProvider`. Distinct from [SteamCredential]: this token **is** sent to the DMarket
 * backend (raw in the `Authorization` header); the Steam credential never is.
 *
 * Unlike [SteamCredential], [expiresAt] is **nullable**: the web actual derives it from the session
 * cookie's `expirationDate`, which may be absent (a session cookie with no explicit expiry). A null
 * expiry means "can't tell" and is treated as fresh — the reactive HTTP 401 path is the backstop that
 * catches a revoked-but-not-yet-expired token.
 */
data class MarketplaceCredential(val token: String, val expiresAt: Instant?) {
    init {
        require(token.isNotBlank()) { "token must not be blank" }
    }

    /**
     * True if the credential is still safely usable at [now], leaving [skew] of headroom before
     * expiry (default 60s). A null [expiresAt] is always considered fresh — see the class KDoc.
     */
    fun isFresh(now: Instant, skew: Duration = DEFAULT_SKEW): Boolean = expiresAt == null || now < expiresAt - skew

    /** Redacted: the bearer token must never reach a log line or an exception message. */
    override fun toString(): String = "MarketplaceCredential(token=<redacted>, expiresAt=$expiresAt)"

    companion object {
        val DEFAULT_SKEW: Duration = 60.seconds
    }
}
