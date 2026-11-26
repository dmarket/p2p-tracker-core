package com.dmarket.p2p.tracker.port.host

/**
 * Where the mobile core's Steam web session lives, keyed by URL as a browser's cookie store is: every Steam request
 * takes its `Cookie` header from here and every `Set-Cookie` Steam answers with goes back in, so a refreshed
 * `steamLoginSecure` (or a rotated `steamRefresh_steam`) lives in one place.
 *
 * Two implementations: the core's sealed vault (the default, matched by the core itself) and the platform's WebView
 * store (Android `CookieManager`), where the store matches domain, path and expiry itself and the app's own WebViews
 * see the same session. The WebView store also holds other Steam cookies and other sites': nothing here replaces the
 * whole store.
 *
 * **A failed read throws.** `null` means the store answered and holds no such cookie; a store that cannot be read
 * (a WebView provider that fails to load) must never read as "no session", which would turn into a logout.
 *
 * Device-only, like [CredentialVault]: nothing read here goes to DMarket. Values never reach a log.
 */
interface SteamCookieStore {
    /** The `Cookie` header the store sends to [url], or `null` when it sends none. Throws when the store cannot be read. */
    suspend fun cookieHeader(url: String): String?

    /** The value of the cookie [name] the store sends to [url], or `null`. Throws when the store cannot be read. */
    suspend fun value(url: String, name: String): String?

    /**
     * A response's raw `Set-Cookie` lines for [url], stored as a browser would: attributes kept, an expired one
     * (`Max-Age=0`, a past `Expires`) deletes its slot, one whose `Domain=` does not domain-match [url]'s host is dropped.
     */
    suspend fun store(url: String, setCookieLines: List<String>)

    /**
     * One cookie the core makes itself (`sessionid`, a refreshed `steamLoginSecure`): host-only on [url]'s host,
     * path `/`, `Secure`, expiring at [expiresAtEpochSeconds] (`null` = a session cookie).
     */
    suspend fun set(url: String, name: String, value: String, expiresAtEpochSeconds: Long?, httpOnly: Boolean)

    /** Deletes the Steam session on every Steam host (logout, a session the user must replace); other sites stay. */
    suspend fun clearSession()
}

/** A [SteamCookieStore] that could not be read or written; a transient failure, never "no session". */
class SteamCookieStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The `NotSent` reason of a Steam write whose session could not be read from the [SteamCookieStore]: it never left. */
const val STEAM_COOKIE_STORE_UNREADABLE_REASON: String = "not sent: the Steam cookie store could not be read"
