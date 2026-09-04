package com.dmarket.p2p.tracker.port

/**
 * Thrown by [com.dmarket.p2p.tracker.port.steam.SteamWebSessionGateway.getWithSession] when the Steam
 * refresh endpoint is momentarily unreachable (5xx / 429 / 403 / network error), as opposed to
 * reachable-but-logged-out. The orchestration maps this to [SessionRefreshOutcome.FAILED] so a
 * transient blip never triggers a spurious interactive-re-login prompt.
 */
class TransientSessionException(message: String? = null, cause: Throwable? = null) : Exception(message, cause)

/**
 * A cookie as seen by [com.dmarket.p2p.tracker.port.steam.SteamWebSessionGateway].
 * [expiresAtEpochSeconds] is the absolute expiry in epoch seconds, or `null` for a session cookie (one with
 * no persistent expiry).
 *
 * Steam-only now: the marketplace token store needs a cookie's full write attributes (domain/host-only, path,
 * secure, same-site), so it keeps its own richer record rather than widening this one.
 */
data class WebCookie(val value: String, val expiresAtEpochSeconds: Long?) {
    /**
     * Redacted: for the cookies this carries, the value *is* the credential — `steamLoginSecure` embeds the
     * Steam access token.
     */
    override fun toString(): String = "WebCookie(value=<redacted>, expiresAtEpochSeconds=$expiresAtEpochSeconds)"
}
