package com.dmarket.p2p.tracker.model.steam

import com.dmarket.p2p.tracker.model.SteamId
import kotlin.time.Instant

/** What became of a pushed Steam access token. */
sealed interface SteamTokenCheck {
    data class Accepted(val credential: SteamCredential) : SteamTokenCheck

    /** [reason] is ours, never the token or any part of it. */
    data class Refused(val reason: String) : SteamTokenCheck
}

/**
 * Checks a Steam access token a host pushes in, before anything holds it.
 *
 * The one credential taken is an access token Steam takes as the steamcommunity.com session; the library sends it
 * as the `steamLoginSecure` cookie and as the Web API `access_token`. Its `aud` holds `web` (a MobileApp token
 * `["web","mobile"]`, a SteamClient token `["client","web"]`) or `web:community` (the `steamLoginSecure` cookie of
 * a browser logged in on steamcommunity.com). Refused:
 *  - anything that is not a JWT with a steamid64 `sub` and an `exp`;
 *  - a token whose `aud` has neither, a store cookie's `web:store` included, because Steam would not accept it as
 *    the community session;
 *  - a token that carries `renew` or `derive`: that is a refresh token, a credential this library never holds;
 *  - a token already expired at [now].
 *
 * The signature is not verified (see [SteamTokenJwt]).
 */
object PushedSteamToken {
    private val STEAM_ID64 = Regex("^7656119\\d{10}$")
    private val REFRESH_AUDIENCES = setOf("renew", "derive")
    private val SESSION_AUDIENCES = setOf("web", "web:community")

    fun check(token: String, now: Instant): SteamTokenCheck {
        val trimmed = token.trim()
        val claims = SteamTokenJwt.claimsOrNull(trimmed) ?: return SteamTokenCheck.Refused("not a Steam access token")
        val subject = claims.sub?.takeIf { STEAM_ID64.matches(it) } ?: return SteamTokenCheck.Refused("no steamid64 subject")
        val audience = claims.aud.map { it.lowercase() }
        if (audience.any { it in REFRESH_AUDIENCES }) return SteamTokenCheck.Refused("a refresh token is never accepted")
        if (audience.none { it in SESSION_AUDIENCES }) return SteamTokenCheck.Refused("the token has no web or web:community audience")
        val expiresAt = claims.exp?.let(Instant::fromEpochSeconds) ?: return SteamTokenCheck.Refused("the token has no expiry")
        if (expiresAt <= now) return SteamTokenCheck.Refused("the token has expired")
        return SteamTokenCheck.Accepted(SteamCredential(trimmed, SteamId(subject), expiresAt))
    }
}
