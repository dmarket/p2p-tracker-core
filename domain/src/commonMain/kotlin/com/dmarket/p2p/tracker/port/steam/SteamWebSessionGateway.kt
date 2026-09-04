package com.dmarket.p2p.tracker.port.steam

import com.dmarket.p2p.tracker.port.TransientSessionException
import com.dmarket.p2p.tracker.port.WebCookie

/**
 * The thin, per-platform IO seam the shared session-refresh algorithm
 * (`DefaultSteamSessionRefresher`) sits on. Each platform implements only these four primitives; the
 * orchestration — self-gate, `ajaxrefresh`/`settoken` sequencing, post-refresh verification, hard-rule
 * enforcement — lives once in `commonMain`, so web and mobile behave identically.
 *
 * **Session contract:** [getWithSession] and [postFormWithSession] **must** carry the platform's live
 * Steam web session cookies (web: `fetch(..., {credentials:"include"})`; mobile: the in-app WebView's
 * cookie jar), and any `Set-Cookie` the server returns (e.g. from `settoken`) must land back in the
 * same store that [readCookie] / [writeSessionCookie] observe. This is how the durable
 * `steamRefresh_steam` cookie reaches Steam without the client ever reading it.
 *
 * Implementations should be best-effort: return `null` / no-op rather than throwing on the common
 * "not logged in" / non-OK paths (the orchestration wraps everything and never propagates).
 */
interface SteamWebSessionGateway {
    /** Reads a cookie for a Steam web [domain] (e.g. `steamcommunity.com`), or `null` if absent. */
    suspend fun readCookie(domain: String, name: String): WebCookie?

    /**
     * Writes the `steamLoginSecure` session cookie for [domain] with the hardened attributes the
     * session requires (`Secure`, `HttpOnly`, `SameSite=None`, `Path=/`). [expiresAtEpochSeconds] is
     * the cookie's expiry in epoch seconds, or `null` for a session cookie.
     */
    suspend fun writeSessionCookie(domain: String, value: String, expiresAtEpochSeconds: Long?)

    /**
     * Cookie-bearing GET against the live Steam session; returns the reachable response's body text
     * (possibly a "please log in" page the orchestration will classify as unusable → `NOT_LOGGED_IN`).
     *
     * Implementations **must throw [TransientSessionException]** on a non-OK HTTP status (5xx / 429 /
     * 403 / network error) so the orchestration reports `FAILED` (retry later) rather than mistaking a
     * transient blip for a logged-out session. Only a genuinely reachable 2xx response returns a body.
     */
    suspend fun getWithSession(url: String): String?

    /**
     * Cookie-bearing form POST against the live Steam session; returns the reachable response's body text.
     *
     * Steam's session endpoints are POST-with-form, and their answers are load-bearing: the refresh
     * handshake replies with the per-domain transfer secrets, and each `settoken` reply carries the
     * `result` that says whether the cookie it was supposed to set actually landed. A POST that discards
     * its body can only ever guess.
     *
     * Same non-OK contract as [getWithSession]: implementations **must throw [TransientSessionException]**
     * on a non-OK HTTP status or network error, so a blip is never mistaken for a logged-out session.
     */
    suspend fun postFormWithSession(url: String, form: Map<String, String>): String?
}
