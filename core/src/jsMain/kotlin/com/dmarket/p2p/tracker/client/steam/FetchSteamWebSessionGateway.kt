package com.dmarket.p2p.tracker.client.steam

import com.dmarket.p2p.tracker.adapter.webext.webExtApi
import com.dmarket.p2p.tracker.client.HttpStatusException
import com.dmarket.p2p.tracker.client.suppressResponseBodyCapture
import com.dmarket.p2p.tracker.port.TransientSessionException
import com.dmarket.p2p.tracker.port.WebCookie
import com.dmarket.p2p.tracker.port.steam.SteamWebSessionGateway
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.parameters
import kotlinx.coroutines.await
import kotlin.js.Promise

/**
 * The **web / browser-extension** [SteamWebSessionGateway] — the thin platform IO seam under the
 * shared `DefaultSteamSessionRefresher`. It only does primitives; the orchestration, hard-rule
 * enforcement, and response parsing live in `commonMain`.
 *
 * Cookies are read/written via the `chrome.cookies` API (which, unlike `document.cookie`, can touch
 * HttpOnly cookies). The two HTTP primitives ([getWithSession] / [postFormWithSession]) go through the
 * injected credentialed Steam [HttpClient] ([com.dmarket.p2p.tracker.client.createSteamHttpClient]) whose browser engine sets fetch
 * `credentials:"include"` (so the durable `steamRefresh_steam` cookie rides along) and `cache:"no-store"`
 * (always re-mint against the live session, never a cached `ajaxrefresh` nonce).
 *
 * **Manifest requirements:** `"cookies"` permission and `host_permissions` for
 * `https://login.steampowered.com`, `https://steamcommunity.com`, and `https://store.steampowered.com`.
 *
 * **Host anti-CSRF requirement:** Steam's `login/settoken` enforces an `Origin`/`Referer` check that a
 * service-worker request cannot satisfy (its `Origin` is `chrome-extension://…`), so the re-mint 403s
 * and never lands. The host must rewrite the `Origin`/`Referer` on POSTs to `…/login/settoken` on both
 * Steam web domains to first-party via a `declarativeNetRequest` rule (see the debug extension's
 * `installSteamSettokenHeaderRules` in `tools/debug-extension/sw.js` for the reference rule). The
 * library cannot install DNR rules itself; without it the refresher correctly reports `FAILED`.
 *
 * The `ajaxrefresh` GET and each `settoken` POST are reported to the injected client's
 * [com.dmarket.p2p.tracker.client.NetworkObservation] plugin as redacted [com.dmarket.p2p.tracker.model.NetworkExchange]s so the
 * refresh traffic is visible in the session log; the secret-bearing **response** bodies
 * (`ajaxrefresh` nonce/auth) are omitted via [suppressResponseBodyCapture], and the `settoken` request
 * form's `nonce`/`auth`/`sessionid` are scrubbed by `NetworkRedaction` before the record is built.
 */
class FetchSteamWebSessionGateway(private val httpClient: HttpClient) : SteamWebSessionGateway {

    override suspend fun readCookie(domain: String, name: String): WebCookie? {
        val details: dynamic = js("({})")
        details.url = "https://$domain/"
        details.name = name
        val api: dynamic = webExtApi()

        @Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")
        val cookie: dynamic = api.cookies.get(details).unsafeCast<Promise<dynamic>>().await() ?: return null
        val value = cookie.value as? String ?: return null
        val expiresAt = (cookie.expirationDate as? Double)?.toLong() // chrome expiry is epoch seconds
        return WebCookie(value, expiresAt)
    }

    override suspend fun writeSessionCookie(domain: String, value: String, expiresAtEpochSeconds: Long?) {
        val details: dynamic = js("({})")
        details.url = "https://$domain/"
        details.name = "steamLoginSecure"
        details.value = value
        details.domain = domain
        details.path = "/"
        details.secure = true // SameSite=None requires Secure
        details.httpOnly = true
        details.sameSite = "no_restriction" // == SameSite=None
        if (expiresAtEpochSeconds != null) details.expirationDate = expiresAtEpochSeconds.toDouble()
        val api: dynamic = webExtApi()

        @Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")
        api.cookies.set(details).unsafeCast<Promise<dynamic>>().await()
    }

    override suspend fun getWithSession(url: String): String? {
        val response = try {
            httpClient.get(url) {
                // The ajaxrefresh response carries the transfer nonce/auth secrets — never log the body.
                suppressResponseBodyCapture()
            }
            // NOTE: this clause must stay ABOVE the Throwable catch-all below, or a non-OK status is
            // reported as "network error" — inverting the signal the session diagnoses depend on.
        } catch (e: HttpStatusException) {
            // A non-OK status (5xx/429/403) is a transient blip, NOT a logged-out session — surface it as
            // FAILED via the exception rather than letting the parser see null and misreport NOT_LOGGED_IN.
            // Retaining `e` as the cause is safe: its message carries no URL secret and no response body.
            throw TransientSessionException("ajaxrefresh non-OK status: ${e.statusCode}", e)
        } catch (e: Throwable) {
            // Network error (offline / DNS / CORS) — transient, not logged-out.
            throw TransientSessionException("ajaxrefresh network error", e)
        }
        return response.bodyAsText()
    }

    override suspend fun postFormWithSession(url: String, form: Map<String, String>): String? {
        // The response is load-bearing (transfer secrets / settoken `result`), so it is returned rather
        // than discarded. The request form is captured + redacted by the observer; the response body is
        // kept out of the log entirely — it carries the same secrets.
        val response = try {
            httpClient.submitForm(
                url = url,
                formParameters = parameters { form.forEach { (key, value) -> append(key, value) } },
            ) {
                suppressResponseBodyCapture()
            }
            // Must stay above the Throwable catch-all — see getWithSession.
        } catch (e: HttpStatusException) {
            // Non-OK: transient by the port contract, never "logged out".
            throw TransientSessionException("session POST non-OK status: ${e.statusCode}", e)
        } catch (e: Throwable) {
            throw TransientSessionException("session POST network error", e)
        }
        return response.bodyAsText()
    }
}
