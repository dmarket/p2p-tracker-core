package com.dmarket.p2p.tracker.client.steam

import com.dmarket.p2p.tracker.adapter.webext.webExtCookieValue
import com.dmarket.p2p.tracker.client.suppressResponseBodyCapture
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import kotlin.coroutines.cancellation.CancellationException

/**
 * Steam's `sessionid` anti-CSRF cookie for [communityBaseUrl], minting it first when the jar has none.
 *
 * `sessionid` is a **browser-session** cookie (no `Expires`) while `steamLoginSecure` is persistent, so after a
 * browser restart the jar routinely holds a valid login and no `sessionid` until something loads a
 * steamcommunity.com page. A leased directive fires from the loop's own alarm, usually before the user has
 * opened Steam — so reading the jar alone failed those writes without ever contacting Steam. Any credentialed
 * response from the community host sets the cookie, so one `GET` of the root is the whole mint.
 *
 * Best-effort: a failed or refused `GET` falls through to the second read, and a `null` from that is the
 * caller's "no session" signal exactly as before. The response body is never captured — Steam pages embed the
 * same token (`g_sessionID`) in their script.
 */
internal suspend fun HttpClient.steamSessionId(communityBaseUrl: String): String? {
    webExtCookieValue(communityBaseUrl, SESSION_ID_COOKIE)?.let { return it }
    try {
        get("$communityBaseUrl/") { suppressResponseBodyCapture() }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        // Fall through: the re-read decides.
    }
    return webExtCookieValue(communityBaseUrl, SESSION_ID_COOKIE)
}

private const val SESSION_ID_COOKIE = "sessionid"
