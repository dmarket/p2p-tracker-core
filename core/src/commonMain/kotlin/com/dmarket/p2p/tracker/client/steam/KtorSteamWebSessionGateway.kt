package com.dmarket.p2p.tracker.client.steam

import com.dmarket.p2p.tracker.client.suppressResponseBodyCapture
import com.dmarket.p2p.tracker.port.TransientSessionException
import com.dmarket.p2p.tracker.port.WebCookie
import com.dmarket.p2p.tracker.port.steam.SteamWebSessionGateway
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.parameters
import kotlin.coroutines.cancellation.CancellationException

/**
 * The mobile [SteamWebSessionGateway]: cookies come from the core's store through [jar], and HTTP goes through
 * [httpClient], the Steam client built with the same [WebCookieJar]. So `ajaxrefresh` carries
 * `steamRefresh_steam` (to `login.steampowered.com` only) and the `steamLoginSecure` that `settoken` sets lands back
 * in the store, from which the scraper and the inventory reader read it.
 *
 * [readCookie] answers the cookie the store sends to `https://<domain>/` with no expiry: nothing reads a cookie's own
 * expiry here (the refresher reads the JWT `exp`), and the WebView store does not give it. A store that cannot be read
 * throws, which the port's callers take as transient.
 *
 * Each POST carries the `Origin` and `Referer` a browser would: the page that runs Steam's refresh is the domain being
 * refreshed (`redir` of `ajaxrefresh`, the host of a `settoken`). Steam refuses a cross-site POST without them.
 *
 * Non-OK statuses and transport failures throw [TransientSessionException], per the port's contract.
 */
class KtorSteamWebSessionGateway(private val httpClient: HttpClient, private val jar: WebCookieJar) : SteamWebSessionGateway {
    override suspend fun readCookie(domain: String, name: String): WebCookie? =
        jar.value(rootOf(domain), name)?.let { WebCookie(it, expiresAtEpochSeconds = null) }

    override suspend fun writeSessionCookie(domain: String, value: String, expiresAtEpochSeconds: Long?) =
        jar.set(rootOf(domain), SESSION_COOKIE, value, expiresAtEpochSeconds, httpOnly = true)

    override suspend fun getWithSession(url: String): String? = call { httpClient.get(url) { browserHeaders(originOf(url)) } }

    override suspend fun postFormWithSession(url: String, form: Map<String, String>): String? = call {
        val origin = form[REDIR]?.let(::originOf) ?: originOf(url)
        httpClient.submitForm(url, parameters { form.forEach { (key, value) -> append(key, value) } }) {
            browserHeaders(origin)
        }
    }

    private fun HttpRequestBuilder.browserHeaders(origin: String) {
        header(HttpHeaders.Origin, origin)
        header(HttpHeaders.Referrer, "$origin/")
        // The answers carry the session-transfer secrets (nonce, auth) or a logged-in page: never in the observer log.
        suppressResponseBodyCapture()
    }

    private suspend fun call(request: suspend () -> HttpResponse): String = try {
        request().bodyAsText()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // The sanitized client throws on a non-2xx with no URL secret or body in the message; keep only its type.
        throw TransientSessionException("Steam session request failed: ${e::class.simpleName}")
    }

    private fun rootOf(domain: String): Url = Url("https://${domain.lowercase()}/")

    private fun originOf(url: String): String = Url(url).let { "${it.protocol.name}://${it.host}" }

    private companion object {
        const val SESSION_COOKIE = "steamLoginSecure"
        const val REDIR = "redir"
    }
}
