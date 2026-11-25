package com.dmarket.p2p.tracker.client.steam

import com.dmarket.p2p.tracker.credential.steam.DefaultSteamSessionRefresher.Companion.COMMUNITY_DOMAIN
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.model.steam.SteamSessionCookie
import com.dmarket.p2p.tracker.model.steam.SteamTokenJwt
import com.dmarket.p2p.tracker.port.steam.SteamSessionScraper
import com.dmarket.p2p.tracker.port.steam.SteamWebSessionGateway

/**
 * [SteamSessionScraper] that reads the Steam Web API credential out of the `steamLoginSecure` session cookie
 * (`steamid64 || access_token`, community domain). No network request.
 *
 * Until 2026-11-24 the credential was scraped from the steamcommunity.com homepage
 * (`data-loyalty_webapi_token`). Steam removed that attribute then (DMA-1070); the page still reports the
 * token's expiry (`g_wapitExpiry`), and it equals the `exp` of the access token in this cookie: it was the same
 * token. `api.steampowered.com` accepts it as `access_token`.
 *
 * The cookie stays fresh through `DefaultSteamSessionRefresher`, which renews it before the token expires.
 *
 * Return contract (see [SteamSessionScraper]):
 * - `null` when the cookie is absent or unparseable, or the token's `sub` claim names another account than the
 *   cookie's steamid.
 * - Throws whatever [SteamWebSessionGateway.readCookie] throws: a failed read is transient, not a logout.
 */
class CookieSteamSessionScraper(private val gateway: SteamWebSessionGateway, private val sessionCookieName: String = "steamLoginSecure") :
    SteamSessionScraper {

    override suspend fun scrape(): SteamCredential? {
        val value = gateway.readCookie(COMMUNITY_DOMAIN, sessionCookieName)?.value ?: return null
        val cookie = SteamSessionCookie.parse(value) ?: return null
        // The two halves of the cookie must name the same account.
        val sub = SteamTokenJwt.subjectOrNull(cookie.accessToken)
        if (sub != null && sub != cookie.steamId.value) return null
        return SteamCredential(token = cookie.accessToken, subjectSteamId = cookie.steamId, expiresAt = cookie.expiresAt)
    }
}
