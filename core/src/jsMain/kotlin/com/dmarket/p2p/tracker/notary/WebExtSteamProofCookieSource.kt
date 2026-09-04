package com.dmarket.p2p.tracker.notary

import com.dmarket.p2p.tracker.adapter.webext.webExtCookieValue
import com.dmarket.p2p.tracker.port.notary.SteamProofCookieSource
import com.dmarket.p2p.tracker.port.notary.SteamProofSession

/**
 * The **web / browser-extension** [SteamProofCookieSource]: reads the live Steam web session in the context
 * that actually runs the prover.
 *
 * Reading it here rather than passing it in is the point — see the port's doc. The offscreen document has full
 * extension API access, so the session secret never travels through host message-passing code, and the
 * `notaryProofDelegate` signature stays as it is.
 *
 * Reads through the shared `webExtCookieValue` helper rather than open-coding `chrome.cookies.get`, which had
 * accumulated four copies across the Steam-facing classes — each with its own restatement of the Firefox
 * `browser`-vs-`chrome` promise caveat. Not composed over `SteamWebSessionGateway` despite that port having a
 * `readCookie`: its constructor takes an `HttpClient` that `readCookie` never touches, and building one in the
 * offscreen document purely to read a cookie trades one duplication for a heavier dependency.
 *
 * Only `steamcommunity.com` is read, and only these two names. The Steam JWT is not here (it comes off the
 * `SteamCredential`), and neither is anything on `login.steampowered.com` — a proven request has no business
 * with the session-transfer surface.
 *
 * **Manifest requirements:** the `"cookies"` permission and `host_permissions` for
 * `https://steamcommunity.com`, on the context hosting the prover. See `vendor/tlsn/INTEGRATION.md`.
 */
class WebExtSteamProofCookieSource(private val communityBaseUrl: String = "https://steamcommunity.com") : SteamProofCookieSource {

    /**
     * `null` when `steamLoginSecure` is absent — the "not logged in" case, which must fail the proof rather
     * than issue a cookie-less request whose 401 would be faithfully attested.
     *
     * `sessionid` is optional here and required later, by the writes that actually echo it: a cookie-authed
     * *read* needs no anti-CSRF token, so demanding one would block the inventory read for no reason.
     *
     * A read failure surfaces as `null` — a missing permission and a logged-out user are the same outcome at
     * this layer, and the caller turns either into a clear failure before spending a prover.
     */
    override suspend fun currentSession(): SteamProofSession? {
        val login = webExtCookieValue(communityBaseUrl, SESSION_COOKIE) ?: return null
        val sessionId = webExtCookieValue(communityBaseUrl, SESSION_ID_COOKIE)
        val header = buildString {
            append(SESSION_COOKIE).append('=').append(login)
            sessionId?.let { append("; ").append(SESSION_ID_COOKIE).append('=').append(it) }
        }
        return SteamProofSession(cookieHeader = header, sessionId = sessionId)
    }

    private companion object {
        const val SESSION_COOKIE = "steamLoginSecure"
        const val SESSION_ID_COOKIE = "sessionid"
    }
}
