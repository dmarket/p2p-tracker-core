package com.dmarket.p2p.sdk.secrets

import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.model.steam.SteamTokenJwt
import kotlin.time.Instant

/** What became of a pushed Steam access token. */
sealed interface SteamTokenCheck {
    data class Accepted(val credential: SteamCredential) : SteamTokenCheck

    /** [reason] is ours, never the token or any part of it. */
    data class Refused(val reason: String) : SteamTokenCheck
}

/**
 * Checks a Steam access token before the SDK holds it.
 *
 * The SDK takes one credential: a `web`-audience access token, which is at once the `steamLoginSecure` session
 * and the `webapi_token`. A MobileApp token (`["web","mobile"]`) and a SteamClient token (`["client","web"]`)
 * both qualify. Refused:
 *  - anything that is not a JWT with a steamid64 `sub` and an `exp`;
 *  - a token whose `aud` lacks `web`, because Steam would not accept it as the web session;
 *  - a token that carries `renew` or `derive`: that is a refresh token, the credential the SDK must never hold;
 *  - a token already expired at [now].
 */
object PushedSteamToken {
    private val STEAM_ID64 = Regex("^7656119\\d{10}$")
    private val REFRESH_AUDIENCES = setOf("renew", "derive")

    fun check(token: String, now: Instant): SteamTokenCheck {
        val trimmed = token.trim()
        val claims = SteamTokenJwt.claimsOrNull(trimmed) ?: return SteamTokenCheck.Refused("not a Steam access token")
        val subject = claims.sub?.takeIf { STEAM_ID64.matches(it) } ?: return SteamTokenCheck.Refused("no steamid64 subject")
        val audience = claims.aud.map { it.lowercase() }
        if (audience.any { it in REFRESH_AUDIENCES }) return SteamTokenCheck.Refused("a refresh token is never accepted")
        if ("web" !in audience) return SteamTokenCheck.Refused("the token has no web audience")
        val expiresAt = claims.exp?.let(Instant::fromEpochSeconds) ?: return SteamTokenCheck.Refused("the token has no expiry")
        if (expiresAt <= now) return SteamTokenCheck.Refused("the token has expired")
        return SteamTokenCheck.Accepted(SteamCredential(trimmed, SteamId(subject), expiresAt))
    }
}
