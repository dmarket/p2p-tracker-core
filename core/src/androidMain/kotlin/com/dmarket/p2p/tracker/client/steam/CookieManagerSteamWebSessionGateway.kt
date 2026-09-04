// PHASE 3 SCAFFOLD — reference only, NOT compiled until the Android target is enabled in
// build-logic/.../dmarket.kmp.library.gradle.kts (the `androidTarget { ... }` block). KMP ignores this
// source set until then; it is linted by spotless but not type-checked. Finalize against a real
// Android build. It implements ONLY the gateway — the refresh algorithm is shared in commonMain
// (DefaultSteamSessionRefresher), so mobile and web stay consistent.
package com.dmarket.p2p.tracker.client.steam

import android.webkit.CookieManager
import com.dmarket.p2p.tracker.port.TransientSessionException
import com.dmarket.p2p.tracker.port.WebCookie
import com.dmarket.p2p.tracker.port.steam.SteamWebSessionGateway
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Android [SteamWebSessionGateway] for the in-app WebView Steam session.
 *
 * Cookies go through the WebView's [CookieManager]; HTTP goes through an [OkHttpClient] whose
 * `CookieJar` is **bridged to the same [CookieManager]** so `getWithSession` carries the WebView's
 * `steamRefresh_steam` and `settoken`'s `Set-Cookie` lands back in the WebView jar.
 *
 * Host responsibility: call `CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)` on
 * the actual WebView (cross-site cookies default off on API 21+), and share the cookie jar with [http].
 */
class CookieManagerSteamWebSessionGateway(
    private val http: OkHttpClient,
    private val cookieManager: CookieManager = CookieManager.getInstance(),
) : SteamWebSessionGateway {

    init {
        cookieManager.setAcceptCookie(true)
    }

    override suspend fun readCookie(domain: String, name: String): WebCookie? {
        // CookieManager returns "k=v; k2=v2" and does not expose per-cookie expiry → treat as session-scoped.
        val header = cookieManager.getCookie("https://$domain/") ?: return null
        val value = header.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter("=")
            ?: return null
        return WebCookie(value, expiresAtEpochSeconds = null)
    }

    override suspend fun writeSessionCookie(domain: String, value: String, expiresAtEpochSeconds: Long?) {
        // setCookie IGNORES session/expired cookies, so a future Expires is mandatory; flush() persists it.
        val expires = httpDate(expiresAtEpochSeconds ?: FAR_FUTURE_EPOCH_SECONDS)
        cookieManager.setCookie(
            "https://$domain/",
            "steamLoginSecure=$value; Domain=$domain; Path=/; Expires=$expires; Secure; HttpOnly; SameSite=None",
        )
        cookieManager.flush()
    }

    override suspend fun getWithSession(url: String): String? {
        val request = Request.Builder().url(url).get().build()
        http.newCall(request).execute().use { response ->
            return if (response.isSuccessful) response.body?.string() else null
        }
    }

    override suspend fun postFormWithSession(url: String, form: Map<String, String>): String? {
        val body = FormBody.Builder().apply { form.forEach { (key, value) -> add(key, value) } }.build()
        val request = Request.Builder().url(url).post(body).build()
        // The response is load-bearing (transfer secrets / settoken `result`). Non-OK must THROW per the
        // port contract, so a Steam 429/5xx is never mistaken for a logged-out session.
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw TransientSessionException("session POST non-OK status: ${response.code}")
            }
            response.body?.string()
        }
    }

    private fun httpDate(epochSeconds: Long): String =
        Instant.ofEpochSecond(epochSeconds).atZone(ZoneOffset.UTC).format(DateTimeFormatter.RFC_1123_DATE_TIME)

    private companion object {
        const val FAR_FUTURE_EPOCH_SECONDS = 4_102_444_800L // 2100-01-01
    }
}
