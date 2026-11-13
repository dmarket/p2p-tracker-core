package com.dmarket.p2p.tracker.adapter

import android.webkit.CookieManager
import com.dmarket.p2p.tracker.model.steam.SteamCookie

/**
 * Reads the Steam session cookies the app's WebView holds after a Steam sign-in, for the one import that follows it
 * ([com.dmarket.p2p.tracker.runtime.P2pTracker.importSteamSession]). Read-only: nothing is written to the WebView's
 * store, and after the import the core sends Steam nothing from it.
 *
 * [lookup] is `CookieManager.getCookie(url)`, which answers `name=value; …` with no domain, path or expiry. Each cookie
 * is kept for the host it was read for, path `/`, as a session cookie: the session's own clock is the JWT `exp` inside
 * `steamLoginSecure`, and the import is what ties `steamRefresh_steam` to `login.steampowered.com` alone.
 */
internal class WebViewSteamCookies(private val lookup: (url: String) -> String? = CookieManager.getInstance()::getCookie) {
    fun read(): List<SteamCookie> = SteamCookie.SESSION_DOMAINS.flatMap { domain ->
        lookup("https://$domain/").orEmpty().split(';').mapNotNull { pair ->
            val name = pair.substringBefore('=').trim()
            val value = pair.substringAfter('=', missingDelimiterValue = "").trim()
            if (name.isEmpty() || value.isEmpty()) null else SteamCookie(name, value, domain)
        }
    }
}
