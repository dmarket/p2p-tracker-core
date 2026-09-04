package com.dmarket.p2p.tracker.client.steam

import com.dmarket.p2p.tracker.client.HttpStatusException
import com.dmarket.p2p.tracker.client.suppressResponseBodyCapture
import com.dmarket.p2p.tracker.config.SteamScrapeConfig
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.steam.MalformedSteamTokenException
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.model.steam.SteamTokenJwt
import com.dmarket.p2p.tracker.port.steam.SteamSessionScraper
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders

/**
 * Ktor-backed [SteamSessionScraper]: scrapes `data-loyalty_webapi_token` from a logged-in
 * `steamcommunity.com` page. On the browser the shared Steam engine attaches the cookie session
 * (fetch `credentials:"include"`) and forces `cache:"no-store"` — Steam rotates the embedded token
 * (~24h) while the session cookie's browser expiry stays months out, so a cached homepage would yield
 * a rotated-out (dead) token.
 *
 * This performs only the silent background attempt (per the [SteamSessionScraper] contract). A
 * logged-in cookie session is sufficient — no Steam tab needs to be open. On the browser the
 * extension must declare `host_permissions` for `https://steamcommunity.com`.
 *
 * Return contract:
 * - Returns the fresh [SteamCredential] on success.
 * - Returns `null` if the token or Steam ID is absent (user not logged in), if the JWT `sub` claim
 *   and `g_steamID` disagree, or if the JWT is malformed (degrade gracefully — no crash).
 * - Returns `null` on a non-OK HTTP status (treated as logged out), and **throws** only on a transport
 *   failure so the caller can distinguish "logged out" from "offline".
 */
class KtorSteamSessionScraper(
    private val httpClient: HttpClient,
    scrapeConfig: SteamScrapeConfig = SteamScrapeConfig(),
    private val communityBaseUrl: String = "https://steamcommunity.com",
) : SteamSessionScraper {

    // Matches: data-loyalty_webapi_token="&quot;THE.JWT.TOKEN&quot;" (anchored on the second &quot;).
    private val tokenRegex = Regex(scrapeConfig.tokenRegex)

    // Matches: var g_steamID = "76561198000000001"; (any amount of whitespace around =).
    private val steamIdRegex = Regex(scrapeConfig.steamIdRegex)

    override suspend fun scrape(): SteamCredential? {
        val body = fetchHtml() ?: return null

        val token = tokenRegex.find(body)?.groupValues?.getOrNull(1) ?: return null
        val steamId = steamIdRegex.find(body)?.groupValues?.getOrNull(1) ?: return null

        val expiresAt = try {
            SteamTokenJwt.parseExp(token)
        } catch (_: MalformedSteamTokenException) {
            return null // malformed JWT → treat as logged out
        }

        // Cross-check: JWT `sub` must agree with `g_steamID` (defends against stale/wrong page).
        val sub = SteamTokenJwt.subjectOrNull(token)
        if (sub != null && sub != steamId) return null

        return SteamCredential(
            token = token,
            subjectSteamId = SteamId(steamId),
            expiresAt = expiresAt,
        )
    }

    // ---- private -----------------------------------------------------------------------------------

    private suspend fun fetchHtml(): String? {
        // A non-OK status (e.g. a login-redirect landing) is "logged out" → null: the shared client
        // sanitizes failures, so any non-2xx throws [HttpStatusException], which we map to null. Only a
        // transport failure propagates, so the caller can tell "needs login" from "try again later".
        // (This never "opted out of expectSuccess" as an older comment here claimed —
        // suppressResponseBodyCapture only affects the observer log.)
        val response = try {
            httpClient.get(communityBaseUrl) {
                header(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9")
                // The response is the JWT-bearing homepage HTML (data-loyalty_webapi_token) — keep it out
                // of the observer log entirely rather than lean on redaction.
                suppressResponseBodyCapture()
            }
        } catch (_: HttpStatusException) {
            return null
        }
        return response.bodyAsText()
    }
}
