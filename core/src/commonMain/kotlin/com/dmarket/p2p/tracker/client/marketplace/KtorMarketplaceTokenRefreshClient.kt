package com.dmarket.p2p.tracker.client.marketplace

import com.dmarket.p2p.tracker.client.HttpStatusException
import com.dmarket.p2p.tracker.config.MarketplaceScrapeConfig
import com.dmarket.p2p.tracker.model.marketplace.MarketplaceTokenPair
import com.dmarket.p2p.tracker.net.SteamHosts
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceRefreshRejectedException
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceTokenRefreshClient
import com.dmarket.p2p.tracker.wire.RefreshTokenRequestDto
import com.dmarket.p2p.tracker.wire.RefreshTokenResponseDto
import com.dmarket.p2p.tracker.wire.TrackerJson
import com.dmarket.p2p.tracker.wire.toPairOrNull
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType

/**
 * The DMarket token-refresh call: `POST {baseUrl}{path}` with `{"RefreshToken": "…"}`.
 *
 * **Must be given an [HttpClient] with no authenticator and no 401 retry.** This call is what the 401 path
 * calls *into*; routing it through the authenticated marketplace client would close a cycle whose retry
 * envelope would re-enter the failing refresh. The DMarket Android client keeps a separate HTTP client for
 * the identical reason.
 *
 * @param httpClient built **on first use**, not at construction. On the web target the service worker
 *   respawns roughly once a minute — including idle wakes that return before any credential is needed — while a
 *   ~24 h token with a 10 min trigger means a refresh is actually due on a small fraction of those spawns.
 *   Paying for an HTTP engine plus its plugin stack on every boot to serve that fraction is waste.
 * @param refreshUrl the fully-resolved endpoint. Resolution and origin allow-listing happen at the wiring
 *   site ([resolveRefreshUrl]) — this class does not accept a base plus a remotely-overridable path, so a
 *   poisoned path can never reach it.
 */
class KtorMarketplaceTokenRefreshClient(httpClient: () -> HttpClient, private val refreshUrl: String) : MarketplaceTokenRefreshClient {

    private val client by lazy(httpClient)

    override suspend fun refresh(refreshToken: String, accessTokenOrNull: String?): MarketplaceTokenPair {
        val body = TrackerJson.encodeToString(RefreshTokenRequestDto(refreshToken))
        val response: HttpResponse = try {
            client.post(refreshUrl) {
                // Attached raw, no `Bearer ` prefix — the same shape the DMarket web frontend sends, which
                // is the only shape observed to work against this gateway. Sending an already-expired access
                // token here is normal and proven: the frontend's own post-401 refresh does exactly that.
                // Absent when the store holds no access token at all, which must still be refreshable.
                accessTokenOrNull?.takeIf { it.isNotBlank() }?.let { header(HttpHeaders.Authorization, it) }
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } catch (e: HttpStatusException) {
            // 401 is the ONLY status that may be read as "this refresh token is spent". Everything else —
            // a WAF 403, a gateway 404 because the route is not mounted there, a 502, a 429 — is transient
            // and must never become a signed-out verdict for the whole install base.
            if (e.statusCode == HttpStatusCode.Unauthorized.value) {
                throw MarketplaceRefreshRejectedException(statusCode = e.statusCode)
            }
            throw e
        }

        val decoded = TrackerJson.decodeFromString<RefreshTokenResponseDto>(response.bodyAsText())
        // A 200 can still be a refusal: these APIs answer some failures with an error `Code` in the body.
        // A token-less 200 is therefore a refusal, not a no-op success — treating it as success would leave
        // the caller believing it had rotated when it had not.
        return decoded.toPairOrNull()
            ?: throw MarketplaceRefreshRejectedException(
                statusCode = response.status.value,
                code = decoded.code,
            )
    }
}

/**
 * The refresh endpoint for this configuration. **Total** — a value it will not accept falls back to the
 * compiled default rather than returning `null`, because this is boot code and refusing to build the client
 * over a bad config string would take the whole tracker down.
 *
 * Both inputs are **allow-listed to the two origins this client already talks to** (the marketplace API base
 * and the site), because this is the one request whose body carries a ~30-day account credential and a
 * misconfigured or maliciously published value would exfiltrate it:
 * - [override] must itself resolve to one of those hosts. It exists because the endpoint is not guaranteed to
 *   be served from the API base in every environment (a dev deployment may proxy it through the site origin).
 * - [path] is checked by the URL it actually produces once appended to [apiBaseUrl] — not by a blacklist of
 *   suspicious substrings. `base + "//evil.example/x"` is a protocol-relative URL and
 *   `base + "@evil.example/x"` parses the base as *userinfo*; composing and re-parsing catches both, and
 *   anything else of that shape, by construction.
 *
 * Reuses [SteamHosts] — despite the name, its `hostOf` / `isAllowed` are the module's single URL-host parser
 * ("the one URL-host parser everything checks against"), and this is the same decision it already gates on
 * the Steam side: allow-list the host before POSTing a session secret to it. A second parser here is how the
 * two come to disagree.
 */
fun resolveRefreshUrl(apiBaseUrl: String, siteUrl: String, path: String, override: String?): String {
    val allowed = setOfNotNull(SteamHosts.hostOf(apiBaseUrl), SteamHosts.hostOf(siteUrl))
    if (!override.isNullOrBlank() && SteamHosts.isAllowed(override, allowed)) return override
    val base = apiBaseUrl.trimEnd('/')
    // Fall back to the field's own declared default rather than a second copy of the literal, so the two
    // cannot drift and a rejected value can never resolve to a stale endpoint.
    val safePath = path.takeIf { SteamHosts.isAllowed(base + it, allowed) } ?: MarketplaceScrapeConfig().tokenRefreshPath
    return base + safePath
}
