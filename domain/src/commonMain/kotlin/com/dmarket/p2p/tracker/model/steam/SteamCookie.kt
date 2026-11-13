package com.dmarket.p2p.tracker.model.steam

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One Steam web cookie as the core's vault keeps it: the session cookies of a Steam sign-in (`steamLoginSecure` per
 * domain, `sessionid`, `steamRefresh_steam`). A cookie goes only to the exact host in [domain] (no subdomains) and to
 * paths under [path].
 *
 * Device-only, like [SteamCredential]: it never goes to DMarket, and [toString] leaves [value] out.
 *
 * @property domain the host the cookie belongs to, without a leading dot, e.g. `login.steampowered.com`.
 * @property expiresAtEpochSeconds when the cookie itself expires, or `null` for a session cookie.
 */
@Serializable
data class SteamCookie(
    @SerialName("name") val name: String,
    @SerialName("value") val value: String,
    @SerialName("domain") val domain: String,
    @SerialName("path") val path: String = "/",
    @SerialName("expires") val expiresAtEpochSeconds: Long? = null,
    @SerialName("httpOnly") val httpOnly: Boolean = false,
) {
    override fun toString(): String =
        "SteamCookie(name=$name, value=<redacted>, domain=$domain, path=$path, expires=$expiresAtEpochSeconds, httpOnly=$httpOnly)"

    companion object {
        /** The hosts whose cookies make up a Steam web session; an import keeps only these. */
        val SESSION_DOMAINS: Set<String> = setOf("steamcommunity.com", "store.steampowered.com", "login.steampowered.com")
    }
}
