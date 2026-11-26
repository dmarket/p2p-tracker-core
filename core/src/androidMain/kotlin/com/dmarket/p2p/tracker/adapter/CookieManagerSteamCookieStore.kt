package com.dmarket.p2p.tracker.adapter

import android.webkit.CookieManager
import androidx.webkit.CookieManagerCompat
import androidx.webkit.WebViewFeature
import com.dmarket.p2p.tracker.model.steam.SteamCookie
import com.dmarket.p2p.tracker.port.host.SteamCookieStore
import com.dmarket.p2p.tracker.port.host.SteamCookieStoreException
import io.ktor.http.toHttpDate
import io.ktor.util.date.GMTDate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * [SteamCookieStore] over the app's WebView `CookieManager`: the core's Steam session is the one the app's WebViews hold,
 * and a cookie the core refreshes is what the next WebView page sends. The browser matches domain, path and expiry.
 *
 * Semantics measured on WebView 158 (spike S2/S3, 2026-11-26): the two-argument `setCookie` is synchronous on a thread
 * with no Looper (no callback, no main thread); `Max-Age=0` deletes a stored slot; a host-only cookie and a `Domain=`
 * one of the same name are two slots, and a deletion removes only the slot whose `Domain` it names, so [clearSession]
 * takes each slot's `domain` from `getCookieInfo`. Every Steam cookie on the session hosts is host-only.
 *
 * Every call runs on [io] (the first `CookieManager.getInstance()` loads the WebView provider; `flush()` writes to
 * disk). A store that cannot be loaded or read throws [SteamCookieStoreException]; `null` from `getCookie` is "no
 * cookie". Values never reach a log or an exception's message.
 *
 * @param cookieManager the process's `CookieManager`; the default retries a provider that fails to load once.
 */
internal class CookieManagerSteamCookieStore(
    private val cookieManager: () -> CookieManager = ::webViewCookieManager,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SteamCookieStore {
    override suspend fun cookieHeader(url: String): String? = read { it.getCookie(url)?.takeIf(String::isNotBlank) }

    override suspend fun value(url: String, name: String): String? = read { it.getCookie(url)?.cookieValue(name) }

    /** Each raw line goes to `setCookie` as it came, so the browser keeps its attributes and drops a foreign `Domain=`. */
    override suspend fun store(url: String, setCookieLines: List<String>) {
        if (setCookieLines.isEmpty()) return
        write { manager -> setCookieLines.forEach { manager.setCookie(url, it) } }
    }

    override suspend fun set(url: String, name: String, value: String, expiresAtEpochSeconds: Long?, httpOnly: Boolean) = write {
        it.setCookie(url, cookieLine(name, value, expiresAtEpochSeconds, httpOnly))
    }

    /**
     * Deletes every cookie the Steam hosts ([CLEARED_HOSTS]) hold, each slot with its own `Domain` and `Path` from
     * `getCookieInfo`, and then reads the session cookies back: one still there throws, since a session left half
     * cleared is worse than a failure the host sees. Other sites' cookies stay.
     */
    override suspend fun clearSession() = write { manager ->
        val infoSupported = WebViewFeature.isFeatureSupported(WebViewFeature.GET_COOKIE_INFO)
        for (host in CLEARED_HOSTS) {
            val url = "https://$host/"
            val slots = if (infoSupported) {
                CookieManagerCompat.getCookieInfo(manager, url).mapNotNull(CookieSlot::parse)
            } else {
                guessedSlots(host, manager.getCookie(url))
            }
            slots.forEach { manager.setCookie(url, it.deletionLine()) }
        }
        val left = SESSION_COOKIES.filter { name -> CLEARED_HOSTS.any { manager.getCookie("https://$it/")?.cookieValue(name) != null } }
        if (left.isNotEmpty()) throw SteamCookieStoreException("Steam session cookies left after clearing: $left")
    }

    private suspend fun <T> read(block: (CookieManager) -> T): T = onStore("read") { block(cookieManager()) }

    private suspend fun write(block: (CookieManager) -> Unit) = onStore("write") {
        val manager = cookieManager()
        block(manager)
        manager.flush()
    }

    private suspend fun <T> onStore(action: String, block: () -> T): T = withContext(io) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SteamCookieStoreException) {
            throw e
        } catch (e: Exception) {
            throw SteamCookieStoreException("WebView cookie store $action failed: ${e::class.simpleName}", e)
        }
    }

    /** One stored cookie's slot, from a `getCookieInfo` line (`name=value; domain=…; path=…; …`, any case). */
    internal data class CookieSlot(val name: String, val domain: String?, val path: String) {
        /** Expires this slot: `Domain` only when the slot was set with one (a leading dot), else host-only. */
        fun deletionLine(): String = buildString {
            append("$name=; Max-Age=0; Expires=$EPOCH; Path=$path; Secure")
            if (domain != null && domain.startsWith('.')) append("; Domain=$domain")
        }

        override fun toString(): String = "CookieSlot(name=$name, domain=$domain, path=$path)"

        companion object {
            fun parse(line: String): CookieSlot? {
                val parts = line.split(';')
                val name = parts.first().substringBefore('=').trim().takeIf { it.isNotEmpty() } ?: return null
                val attributes = parts.drop(1).associate { part ->
                    part.substringBefore('=').trim().lowercase() to part.substringAfter('=', missingDelimiterValue = "").trim()
                }
                return CookieSlot(
                    name = name,
                    domain = attributes["domain"]?.takeIf { it.isNotEmpty() },
                    path = attributes["path"]?.takeIf { it.startsWith("/") } ?: "/",
                )
            }
        }
    }

    internal companion object {
        /** The session's own cookies: what [clearSession] reads back. */
        val SESSION_COOKIES: List<String> = listOf("steamLoginSecure", "steamRefresh_steam")

        /**
         * The Steam hosts [clearSession] clears: the session hosts plus the ones a Steam sign-in also leaves
         * `steamLoginSecure` on (`help`, `checkout`, `steam.tv`), and the bare `steampowered.com` for a `Domain=` slot.
         */
        val CLEARED_HOSTS: List<String> = SteamCookie.SESSION_DOMAINS.toList() + listOf(
            "help.steampowered.com",
            "checkout.steampowered.com",
            "steampowered.com",
            "steam.tv",
        )

        private const val EPOCH = "Thu, 01 Jan 1970 00:00:00 GMT"

        /** A `Set-Cookie` line for one cookie the core makes: host-only, path `/`, `Secure`, `SameSite=None` as Steam's. */
        fun cookieLine(name: String, value: String, expiresAtEpochSeconds: Long?, httpOnly: Boolean): String = buildString {
            append("$name=$value; Path=/; Secure; SameSite=None")
            if (httpOnly) append("; HttpOnly")
            expiresAtEpochSeconds?.let { append("; Expires=${GMTDate(it * 1000).toHttpDate()}") }
        }

        /**
         * Without `getCookieInfo` (an old WebView) the slots are not known: each name [cookieHeader] shows is expired
         * host-only and with `Domain=` of the host and of its parent, which covers every slot a Steam host can hold.
         */
        fun guessedSlots(host: String, cookieHeader: String?): List<CookieSlot> {
            val names = cookieHeader.orEmpty().split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }.distinct()
            val parent = host.split('.').takeLast(2).joinToString(".").takeIf { it != host }
            // null = the host-only slot.
            val domains: List<String?> = listOf(null, ".$host") + listOfNotNull(parent?.let { ".$it" })
            return names.flatMap { name -> domains.map { CookieSlot(name, it, "/") } }
        }

        private fun String.cookieValue(name: String): String? = split(';').firstNotNullOfOrNull { pair ->
            if (pair.substringBefore('=').trim() != name) return@firstNotNullOfOrNull null
            pair.substringAfter('=', missingDelimiterValue = "").trim().takeIf { it.isNotEmpty() }
        }
    }
}

/**
 * The process's `CookieManager`. Its first call loads the WebView provider, which on Android 15+ can fail once with an
 * `AndroidRuntimeException`; one retry, then [SteamCookieStoreException].
 */
internal fun webViewCookieManager(): CookieManager = try {
    CookieManager.getInstance()
} catch (_: RuntimeException) {
    try {
        CookieManager.getInstance()
    } catch (e: RuntimeException) {
        throw SteamCookieStoreException("WebView cookie store unavailable: ${e::class.simpleName}", e)
    }
}
