package com.dmarket.p2p.tracker.model.steam

import com.dmarket.p2p.tracker.model.SteamId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The parsed contents of Steam's `steamLoginSecure` web session cookie.
 *
 * The cookie value is `steamid64 || access_token`, where the `||` separator is URL-encoded as
 * `%7C%7C`. The embedded access token is a short-lived (~24h) `web`-audience JWT; its `exp` claim
 * gives [expiresAt]. Distinct from [SteamCredential] (the `data-loyalty_webapi_token` used for
 * IEconService reads) — this is the cookie that authenticates the browser's Steam session.
 *
 * Pure and IO-free: [parse] is used to inspect a cookie read elsewhere, and [cookieValue] builds the
 * wire value when a platform mints its own access token and must write the cookie itself (the native
 * path). The web path never builds it — Steam's `settoken` response sets it directly.
 */
data class SteamSessionCookie(val steamId: SteamId, val accessToken: String, val expiresAt: Instant) {
    init {
        require(accessToken.isNotBlank()) { "accessToken must not be blank" }
    }

    /** True if the session still has [skew] of headroom before the embedded access token expires. */
    fun isFresh(now: Instant, skew: Duration = DEFAULT_SKEW): Boolean = now < expiresAt - skew

    /** The `steamLoginSecure` cookie value: `steamid64 %7C%7C access_token` (URL-encoded `||`). */
    fun cookieValue(): String = "${steamId.value}$ENCODED_SEPARATOR$accessToken"

    /** Redacted: the embedded access token authenticates the whole Steam web session. */
    override fun toString(): String = "SteamSessionCookie(steamId=$steamId, accessToken=<redacted>, expiresAt=$expiresAt)"

    companion object {
        val DEFAULT_SKEW: Duration = 60.seconds

        private const val ENCODED_SEPARATOR = "%7C%7C"
        private const val RAW_SEPARATOR = "||"

        /**
         * Parses a `steamLoginSecure` cookie value (raw `||` or URL-encoded `%7C%7C`) into its parts,
         * deriving [expiresAt] from the access token's JWT `exp` claim. Returns `null` if the value is
         * not in `steamid||token` shape or the token is not a parseable JWT. Never throws.
         */
        fun parse(cookieValue: String): SteamSessionCookie? {
            val decoded = cookieValue.replace(ENCODED_SEPARATOR, RAW_SEPARATOR, ignoreCase = true)
            val sep = decoded.indexOf(RAW_SEPARATOR)
            if (sep <= 0) return null
            val steamIdPart = decoded.substring(0, sep)
            val token = decoded.substring(sep + RAW_SEPARATOR.length)
            if (steamIdPart.isBlank() || token.isBlank()) return null
            val expiresAt = runCatching { SteamTokenJwt.parseExp(token) }.getOrNull() ?: return null
            return runCatching { SteamSessionCookie(SteamId(steamIdPart), token, expiresAt) }.getOrNull()
        }
    }
}
