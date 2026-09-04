// PHASE 3 SCAFFOLD — reference only, NOT compiled until the iOS targets are enabled in
// build-logic/.../dmarket.kmp.library.gradle.kts (iosArm64()/iosSimulatorArm64()/iosX64()). KMP ignores
// this source set until then; it is linted by spotless but not type-checked, and can only be built on a
// macOS CI runner with full Xcode. Finalize the cinterop details there. It implements ONLY the gateway —
// the refresh algorithm is shared in commonMain (DefaultSteamSessionRefresher), so iOS and web stay
// consistent.
package com.dmarket.p2p.tracker.client.steam

import com.dmarket.p2p.tracker.port.TransientSessionException
import com.dmarket.p2p.tracker.port.WebCookie
import com.dmarket.p2p.tracker.port.steam.SteamWebSessionGateway
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSCharacterSet
import platform.Foundation.NSDate
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSHTTPCookieDomain
import platform.Foundation.NSHTTPCookieExpires
import platform.Foundation.NSHTTPCookieName
import platform.Foundation.NSHTTPCookiePath
import platform.Foundation.NSHTTPCookieSecure
import platform.Foundation.NSHTTPCookieValue
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.WebKit.WKHTTPCookieStore
import platform.WebKit.WKWebsiteDataStore

/**
 * iOS [SteamWebSessionGateway] for the WKWebView-based Steam session.
 *
 * Cookies go through the web view's [WKHTTPCookieStore] (the `default()` data store persists across
 * launches — never `nonPersistent()`); HTTP goes through an [NSURLSession] that shares those cookies so
 * `getWithSession` carries `steamRefresh_steam` and `settoken`'s `Set-Cookie` lands back in the store.
 *
 * Note: there is no public `HttpOnly`/`SameSite` cookie-property key on iOS, so `HttpOnly` is set via
 * its raw string key (the Apple-confirmed workaround). Set cookies *before* the web view loads.
 */
@OptIn(ExperimentalForeignApi::class)
class WkWebViewSteamWebSessionGateway(
    dataStore: WKWebsiteDataStore = WKWebsiteDataStore.defaultDataStore(),
    private val session: NSURLSession = NSURLSession.sharedSession,
) : SteamWebSessionGateway {

    private val cookieStore: WKHTTPCookieStore = dataStore.httpCookieStore

    override suspend fun readCookie(domain: String, name: String): WebCookie? = suspendCancellableCoroutine { continuation ->
        cookieStore.getAllCookies { cookies ->
            val match = cookies
                ?.filterIsInstance<NSHTTPCookie>()
                // Host equality, or a dot-anchored parent-domain cookie. A bare `endsWith(domain)`
                // would also match `evil-steamcommunity.com`, i.e. read a look-alike host's cookie.
                ?.firstOrNull { it.name == name && it.domain.matchesCookieDomain(domain) }
            val result = match?.let { cookie ->
                WebCookie(cookie.value, cookie.expiresDate?.timeIntervalSince1970?.toLong())
            }
            continuation.resumeWith(Result.success(result))
        }
    }

    override suspend fun writeSessionCookie(domain: String, value: String, expiresAtEpochSeconds: Long?) =
        suspendCancellableCoroutine { continuation ->
            val properties = mutableMapOf<Any?, Any?>(
                NSHTTPCookieName to "steamLoginSecure",
                NSHTTPCookieValue to value,
                NSHTTPCookieDomain to domain,
                NSHTTPCookiePath to "/",
                NSHTTPCookieSecure to "TRUE",
                "HttpOnly" to "TRUE",
            )
            if (expiresAtEpochSeconds != null) {
                properties[NSHTTPCookieExpires] = NSDate.dateWithTimeIntervalSince1970(expiresAtEpochSeconds.toDouble())
            }
            val cookie = NSHTTPCookie(properties = properties)
            if (cookie != null) {
                cookieStore.setCookie(cookie) { continuation.resumeWith(Result.success(Unit)) }
            } else {
                continuation.resumeWith(Result.success(Unit))
            }
        }

    override suspend fun getWithSession(url: String): String? = request(url, method = "GET", body = null)

    override suspend fun postFormWithSession(url: String, form: Map<String, String>): String? {
        // Percent-encode: these values carry `||`-separated tokens and URLs, which a raw join corrupts.
        val encoded = form.entries.joinToString("&") { "${it.key.urlFormEncoded()}=${it.value.urlFormEncoded()}" }
        // The response is load-bearing (transfer secrets / settoken `result`), so it is returned. `request`
        // throws TransientSessionException on a non-OK status per the port contract.
        return request(url, method = "POST", body = encoded)
    }

    private suspend fun request(url: String, method: String, body: String?): String? = suspendCancellableCoroutine { continuation ->
        val request = NSMutableURLRequest(uRL = NSURL(string = url)!!)
        request.setHTTPMethod(method)
        if (body != null) {
            request.setHTTPBody((body as NSString).dataUsingEncoding(NSUTF8StringEncoding))
            // Steam's session endpoints are form POSTs; without this they see an empty body.
            request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField = "Content-Type")
        }
        val task = session.dataTaskWithRequest(request) { data, response, error ->
            // Port contract: a non-OK status or a transport error is TRANSIENT and must throw, never be
            // reported as an empty body — an empty body reads as "logged out" one layer up.
            val status = (response as? NSHTTPURLResponse)?.statusCode?.toInt()
            when {
                error != null ->
                    continuation.resumeWith(
                        Result.failure(TransientSessionException("session request failed: ${error.localizedDescription}")),
                    )

                status == null || status !in 200..299 ->
                    continuation.resumeWith(Result.failure(TransientSessionException("session request non-OK status: $status")))

                else -> {
                    val text = data?.let { NSString.create(data = it, encoding = NSUTF8StringEncoding) as String? }
                    continuation.resumeWith(Result.success(text))
                }
            }
        }
        task.resume()
    }
}

/** Percent-encodes a form field: these values carry `||`-separated tokens and URLs a raw join corrupts. */
private fun String.urlFormEncoded(): String = (this as NSString)
    .stringByAddingPercentEncodingWithAllowedCharacters(NSCharacterSet.alphanumericCharacterSet) ?: this

/**
 * Whether this cookie `domain` attribute applies to [host]: the same host, or a parent-domain cookie
 * (leading-dot form) of it. Anchoring on the dot is what a plain suffix test misses — `endsWith`
 * accepts `evil-steamcommunity.com` for `steamcommunity.com`, which would read a look-alike host's
 * session cookie.
 */
private fun String.matchesCookieDomain(host: String): Boolean {
    val domain = removePrefix(".")
    return domain == host || host.endsWith(".$domain")
}
