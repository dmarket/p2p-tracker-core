package com.dmarket.p2p.tracker.credential.steam

import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.steam.SteamSessionCookie
import com.dmarket.p2p.tracker.net.SteamHosts
import com.dmarket.p2p.tracker.port.SessionRefreshOutcome
import com.dmarket.p2p.tracker.port.WebCookie
import com.dmarket.p2p.tracker.port.host.Clock
import com.dmarket.p2p.tracker.port.steam.SteamSessionRefresher
import com.dmarket.p2p.tracker.port.steam.SteamWebSessionGateway
import com.dmarket.p2p.tracker.port.steam.SteamWebSessionState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * The single, platform-agnostic Steam web-session keep-alive algorithm. Every platform shares this;
 * only the [SteamWebSessionGateway] differs (web: `chrome.cookies` + `window.fetch`; mobile: the
 * in-app WebView's cookie jar + native HTTP). It never reads the durable `steamRefresh_steam` token —
 * it lets Steam re-mint `steamLoginSecure`, one **audience-scoped** cookie per Steam web domain.
 *
 * Steps (mirrors the original web flow, now shared + unit-testable):
 * 1. **Self-gate** — if the current community `steamLoginSecure` still has more than [gateHeadroom] of
 *    life, report [SessionRefreshOutcome.NOT_NEEDED] (nothing else — no cookie writes).
 * 2. **Re-mint** — `POST …/jwt/ajaxrefresh` with `redir=<domain>` as a FORM FIELD, once per Steam web
 *    domain (the gateway attaches the live session, incl. the durable refresh cookie); each response is
 *    parsed by [SteamRefreshResponseParser]. The shape matters: Steam answers the same request sent as a
 *    GET query string with `{"success":false,"error":8}` (InvalidParam), measured back-to-back against
 *    the POST on one live session — the GET failed, the POST succeeded. Every reference client, and
 *    Steam's own login page, POSTs the form.
 * 3. **settoken** — POST `{steamID,nonce,auth}` to the endpoint Steam named for that domain (`login_url`,
 *    allow-listed); Steam re-`Set-Cookie`s a fresh, domain-scoped `steamLoginSecure`, which the gateway's
 *    store picks up, and answers `{"result":1}` when it accepted the transfer.
 * 4. **Verify** — re-read the community `steamLoginSecure`; report [SessionRefreshOutcome.REFRESHED]
 *    only if its embedded access-token now clears [gateHeadroom]; otherwise [SessionRefreshOutcome.FAILED].
 *
 * **This class never writes a Steam cookie on the web path.** Steam's `steamLoginSecure` is
 * audience-scoped per domain (community `aud=web:community`, store `aud=web:store` — which is *why*
 * `ajaxrefresh` returns a per-domain `transfer_info[]` and `settoken` is POSTed per domain), so copying
 * one domain's token onto another writes a wrong-audience cookie the page can't repair. Each domain's
 * cookie is populated *only* by that domain's own `settoken` `Set-Cookie` response, landing in the
 * gateway's store. `writeSessionCookie` remains solely for the native mint path (Android/iOS), where
 * the platform builds its own cookie.
 *
 * The `ajaxrefresh` URL is built here; the `settoken` URLs are read from its response (`login_url`, or
 * `transfer_info[]`) but are constrained to a fixed Steam-domain allowlist before the session-transfer
 * secrets are POSTed to them, so a poisoned response cannot redirect them off Steam.
 * Best-effort: any failure is caught and returned as [SessionRefreshOutcome.FAILED]; it never throws.
 *
 * @param gateHeadroom skip the network refresh while the session cookie has at least this much life
 *   left (default 1h) — avoids hammering `ajaxrefresh` on rapid successive refreshes.
 */
class DefaultSteamSessionRefresher(
    private val gateway: SteamWebSessionGateway,
    private val clock: Clock,
    private val gateHeadroom: Duration = 1.hours,
    private val loginBaseUrl: String = "https://login.steampowered.com",
    private val communityBaseUrl: String = "https://steamcommunity.com",
    private val storeBaseUrl: String = "https://store.steampowered.com",
    private val sessionCookieName: String = "steamLoginSecure",
    private val sessionIdCookieName: String = "sessionid",
) : SteamSessionRefresher {

    /**
     * One settoken POST: the endpoint plus the exact form to send it (see [handshake]).
     *
     * `internal` rather than `private` only so the redaction below is directly testable.
     */
    internal data class SetTokenJob(val url: String, val form: Map<String, String>) {
        /**
         * Redacted: [form] is the settoken payload, so it carries `nonce`, `auth`, `sessionid` and — worst
         * of all — `prior`, the **live** `steamLoginSecure` access token. A generated `toString()` prints
         * every one of them. The field NAMES are kept: which fields Steam echoed back is the actual
         * diagnosis for a rejected transfer.
         */
        override fun toString(): String = "SetTokenJob(url=$url, form=<redacted ${form.size} fields: ${form.keys.sorted()}>)"
    }

    override suspend fun refreshSession(force: Boolean): SessionRefreshOutcome =
        runCatching { doRefresh(force) }.getOrElse { SessionRefreshOutcome.FAILED }

    /**
     * One cookie-store lookup, no network: classify the community session by the **embedded** token's
     * expiry against the same [gateHeadroom] step 1 self-gates on, so a caller can both trust a cached
     * credential and know when to call [refreshSession] (see [SteamSessionRefresher.sessionState]).
     *
     * An unreadable value counts as [SteamWebSessionState.NEEDS_REFRESH] — the same "fall through to a
     * re-mint attempt" that step 1 does — while an already-expired one is [SteamWebSessionState.GONE]:
     * the renew endpoint refreshes a live session, so past expiry there is nothing left to renew and a
     * request would only ever come back rejected. Fails open to [SteamWebSessionState.ALIVE].
     *
     * With an [expectedSteamId] the same read also answers *whose* session it is: the cookie carries the
     * steamid in front of the token (the id this class already prefers for the settoken form, see
     * [requestSetTokenJobs]), so a live session belonging to another account is reported as
     * [SteamWebSessionState.OTHER_ACCOUNT] rather than a healthy `ALIVE`. Identity is judged **before** the
     * unreadable-value bail, since the steamid half survives a token half this cannot parse: the caller acts
     * on that identity, so an unrelated parse failure must not hide an account switch behind a verdict that
     * says "your cache is fine".
     */
    override suspend fun sessionState(expectedSteamId: SteamId?): SteamWebSessionState = runCatching {
        val value = gateway.readCookie(COMMUNITY_DOMAIN, sessionCookieName)?.value
            ?: return@runCatching SteamWebSessionState.GONE
        // The whole cookie, not just its expiry: the steamId in front of the token is the ONLY zero-network
        // evidence of *whose* session this is, and after a re-login as another account it is the only thing
        // that has changed — the cookie is freshly minted and reads perfectly healthy either way.
        val cookie = SteamSessionCookie.parse(value)
        if (cookie == null) {
            // Unparseable: no expiry to judge, so this falls through to a re-mint attempt exactly as step 1
            // does — but identity is judged FIRST when it is still legible. The two halves fail
            // independently: the steamid in front of the separator survives a token half this parse cannot
            // read (a Steam JWT shape change, a truncated value), and the caller acts on that identity, so an
            // unrelated parse failure must not answer "your cached credential is fine". Requires a real
            // steamid — the separator present and an all-digit prefix — so a wholly malformed value is still
            // never read as a switch (identity unknown is not a switch).
            val steamId = value.steamIdPrefixOrNull()
            return@runCatching if (expectedSteamId != null && steamId != null && steamId != expectedSteamId.value) {
                SteamWebSessionState.OTHER_ACCOUNT
            } else {
                SteamWebSessionState.NEEDS_REFRESH
            }
        }
        val secondsLeft = cookie.expiresAt.epochSeconds - clock.now().epochSeconds
        when {
            // A session that is not there belongs to nobody, so liveness is judged first.
            secondsLeft <= 0 -> SteamWebSessionState.GONE
            // Identity outranks the renewal window: renewing somebody else's session buys the caller
            // nothing, and the credential it is holding is wrong whichever side of the headroom we are on.
            // Only judged when the caller supplied an id, so we never claim a switch on an unknown identity.
            expectedSteamId != null && cookie.steamId != expectedSteamId -> SteamWebSessionState.OTHER_ACCOUNT
            secondsLeft <= gateHeadroom.inWholeSeconds -> SteamWebSessionState.NEEDS_REFRESH
            else -> SteamWebSessionState.ALIVE
        }
    }.getOrDefault(SteamWebSessionState.ALIVE)

    // ---- private -----------------------------------------------------------------------------------

    private suspend fun doRefresh(force: Boolean = false): SessionRefreshOutcome {
        // 1. Self-gate on the EMBEDDED access-token's expiry — the real ~24h session life — NOT the
        // cookie's browser `expirationDate`. Steam persists `steamLoginSecure` with a far-future
        // browser expiry (months out) while rotating the short-lived access token inside it, so
        // gating on the browser expiry would skip the re-mint forever and report NOT_NEEDED even for
        // a long-dead / logged-out session. An unparseable value falls through to a re-mint attempt.
        //
        // [force] skips the gate entirely: the caller has established there is no usable session and is
        // asking Steam to mint one from the durable credential. Everything below is identical — the
        // handshake carries whatever the platform's cookie jar holds, and with no session cookie there is
        // simply no `prior` token to echo and no cookie steamId to prefer.
        val current = gateway.readCookie(COMMUNITY_DOMAIN, sessionCookieName)
        if (!force && clearsGate(current?.value)) return SessionRefreshOutcome.NOT_NEEDED

        // 2 + 3. Let Steam re-mint, then push the per-domain settoken calls. Each settoken response's
        // Set-Cookie lands the fresh, domain-scoped cookie in the gateway's store directly.
        val jobs = requestSetTokenJobs(current)
        if (jobs.isEmpty()) return SessionRefreshOutcome.NOT_LOGGED_IN
        // Steam answers each transfer with an EResult. If every one was rejected the session did not come
        // back, and saying so here is more truthful than inferring it from a cookie read that could race a
        // concurrent login in another tab.
        val accepted = jobs.map { postSetToken(it) }
        if (accepted.none { it }) return SessionRefreshOutcome.FAILED

        // 4. Verify the re-mint actually landed: only REFRESHED if the community cookie now clears the
        // gate. A silently-rejected settoken (e.g. an anti-CSRF Origin 403) leaves the cookie stale →
        // FAILED, so the caller surfaces re-login honestly instead of trusting a no-op "REFRESHED".
        val refreshed = gateway.readCookie(COMMUNITY_DOMAIN, sessionCookieName)
        return if (clearsGate(refreshed?.value)) SessionRefreshOutcome.REFRESHED else SessionRefreshOutcome.FAILED
    }

    /**
     * The `steamid64` half of a `steamLoginSecure` value, or `null` when this value carries no legible one.
     * Used only by [sessionState]'s unparseable-token branch, where [SteamSessionCookie.parse] has already
     * refused the value as a whole; "legible" is deliberately strict (separator present, digits only) so an
     * arbitrary malformed value can never be mistaken for somebody else's account.
     */
    private fun String.steamIdPrefixOrNull(): String? {
        val separator = listOf(SEPARATOR_ENCODED, SEPARATOR_RAW)
            .map { indexOf(it, ignoreCase = true) }
            .filter { it > 0 }
            .minOrNull()
            ?: return null
        return substring(0, separator).takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
    }

    /** True if [cookieValue] parses to a `steamLoginSecure` whose embedded token clears [gateHeadroom]. */
    private fun clearsGate(cookieValue: String?): Boolean {
        val tokenExpiry = cookieValue?.let { SteamSessionCookie.parse(it)?.expiresAt?.epochSeconds } ?: return false
        return tokenExpiry - clock.now().epochSeconds > gateHeadroom.inWholeSeconds
    }

    private suspend fun requestSetTokenJobs(currentCookie: WebCookie?): List<SetTokenJob> {
        // Prefer the steamId from the existing cookie (no JSON-number precision risk); else the response's.
        val parsedCookie = currentCookie?.let { SteamSessionCookie.parse(it.value) }
        val cookieSteamId = parsedCookie?.steamId?.value
        val priorToken = parsedCookie?.accessToken
        val sessionId = gateway.readCookie(COMMUNITY_DOMAIN, sessionIdCookieName)?.value
        // ONE handshake per Steam web domain. `ajaxrefresh` answers for the requested `redir` domain only,
        // and Steam's session cookies are audience-scoped per domain (`web:community` vs `web:store`), so a
        // transfer minted for community must never be POSTed at store — it would write a wrong-audience
        // cookie the page cannot repair. This is the same reason the response carries `login_url`.
        return listOf(communityBaseUrl, storeBaseUrl).flatMap { redir ->
            handshake(redir, cookieSteamId, priorToken, sessionId)
        }
    }

    /** The refresh handshake for one `redir` domain: POST `ajaxrefresh`, turn the answer into settoken jobs. */
    private suspend fun handshake(redir: String, cookieSteamId: String?, priorToken: String?, sessionId: String?): List<SetTokenJob> {
        // POST with `redir` as a FORM FIELD — the shape Steam's own login page and every reference client
        // use. Sending it as a GET query string is answered `{"success":false,"error":8}` (InvalidParam),
        // measured back-to-back against this call on one live session: the GET failed, the POST succeeded.
        val body = gateway.postFormWithSession("$loginBaseUrl/jwt/ajaxrefresh", mapOf("redir" to redir))
        return when (val parsed = SteamRefreshResponseParser.parse(body)) {
            is SteamRefreshResponse.Transfers ->
                // The per-domain settoken URL is read from the (network-fetched) refresh response, so a
                // poisoned/MITM'd body could redirect the session-transfer secrets ({steamID,nonce,auth,
                // sessionid}) to an attacker host. Constrain each target to a fixed Steam-domain allowlist;
                // drop anything else rather than POST secrets to it.
                parsed.transfers
                    .filter { isAllowedSetTokenHost(it.url) }
                    .map { transfer ->
                        SetTokenJob(
                            transfer.url,
                            buildMap {
                                put("steamID", cookieSteamId ?: parsed.steamId)
                                put("nonce", transfer.nonce)
                                put("auth", transfer.auth)
                                sessionId?.let { put(sessionIdCookieName, it) }
                            },
                        )
                    }

            is SteamRefreshResponse.Flat -> {
                // Steam names the settoken endpoint this transfer is scoped to; honour it when it is a Steam
                // host, else fall back to the redir domain's own path.
                //
                // BOTH are allow-listed, for different reasons. The named URL arrives over the network, so a
                // poisoned body must not be able to redirect the transfer secrets ({steamID, nonce, auth,
                // sessionid}) to an attacker host. The fallback is built from the CONFIGURED redir, so a
                // non-Steam base — a bad remote config, or a direct construction that bypasses
                // SteamEndpointsConfig's guard — must not receive them either; that one was unchecked until
                // now. Keeping the fallback (rather than refusing outright on a disallowed name) is
                // deliberate: if Steam ever names a host we don't know yet, the re-mint still completes
                // against a domain we do trust instead of degrading into a spurious re-login prompt.
                val url = parsed.loginUrl?.takeIf { isAllowedSetTokenHost(it) }
                    ?: "$redir/login/settoken".takeIf { isAllowedSetTokenHost(it) }
                    ?: return emptyList()
                // Echo the WHOLE refresh response and add `prior` — the access token currently in the
                // session cookie — exactly as Steam's own page does
                // (`settoken(Object.assign(response, {prior: g_wapit}))`). Sending a hand-picked subset to
                // an endpoint this strict about shape is a guess; sending what Steam sent back is not.
                // `prior` is absent when there is no live session, which is precisely when there is no
                // prior token to name.
                val form = buildMap {
                    putAll(parsed.fields)
                    put("steamID", cookieSteamId ?: parsed.steamId)
                    priorToken?.let { put("prior", it) }
                }
                listOf(SetTokenJob(url, form))
            }

            SteamRefreshResponse.Unusable -> emptyList()
        }
    }

    /**
     * POSTs one transfer and reports whether Steam accepted it (`{"result":1}` = EResult.OK). Steam's
     * `Set-Cookie` is the point of this call, so a rejected transfer is the difference between a session
     * that came back and one that only looks like it did — [doRefresh]'s verify catches it for the
     * community domain, but only this tells the difference between "store was rejected" and "store was
     * never asked".
     */
    private suspend fun postSetToken(job: SetTokenJob): Boolean {
        val body = runCatching { gateway.postFormWithSession(job.url, job.form) }.getOrNull() ?: return false
        return SetTokenResult.accepted(body)
    }

    /**
     * True if [url] is an https Steam session domain the settoken secrets may be POSTed to. Exact-host
     * match (no subdomain wildcard) — deliberately strict, since this gates where the session-transfer
     * secrets are sent. One parser and one host set, shared with the config guard ([SteamHosts]), so a
     * URL cannot be judged differently in two places.
     */
    private fun isAllowedSetTokenHost(url: String): Boolean = SteamHosts.isAllowed(url, SteamHosts.WEB)

    private companion object {
        const val COMMUNITY_DOMAIN = "steamcommunity.com"

        // The `steamid || access_token` separator, in both the forms a cookie store hands back — used only
        // to recover the steamid from a value whose token half [SteamSessionCookie.parse] could not read.
        const val SEPARATOR_ENCODED = "%7C%7C"
        const val SEPARATOR_RAW = "||"
    }
}
