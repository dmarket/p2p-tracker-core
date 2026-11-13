package com.dmarket.p2p.tracker.client.steam

import com.dmarket.p2p.tracker.model.steam.SteamCookie
import com.dmarket.p2p.tracker.port.host.Clock
import com.dmarket.p2p.tracker.port.host.SteamCookieVault
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.parseServerSetCookieHeader
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The Steam client's cookies on mobile, read from and written to the core's [SteamCookieVault]: the `Cookie` header of
 * every request is built from the vault, and every `Set-Cookie` in a response goes back into it.
 *
 * A cookie goes to the exact host it was stored for, never to a subdomain or a parent, and only under its path. So
 * `steamRefresh_steam` (stored for `login.steampowered.com`) reaches that host alone, and each domain's
 * `steamLoginSecure` stays on its own domain. shortcut: no public-suffix or `Domain=` widening, which the Steam hosts
 * this serves never need; revisit if a cookie must reach a subdomain.
 *
 * Suspend all the way (the vault is), so it never blocks a thread. Values never reach a log or a `toString`.
 */
class VaultCookieJar(private val vault: SteamCookieVault, private val clock: Clock) {
    private val mutex = Mutex()

    /** The `Cookie` header for [url], or `null` when no stored cookie applies. */
    suspend fun cookieHeader(url: Url): String? {
        val now = clock.now().epochSeconds
        return vault.readSteamCookies()
            .filter { it.appliesTo(url) && !it.expiredAt(now) }
            .joinToString("; ") { "${it.name}=${it.value}" }
            .ifEmpty { null }
    }

    /** Every stored cookie. */
    suspend fun cookies(): List<SteamCookie> = vault.readSteamCookies()

    /** Stores [cookie] in place of the one with the same name, host and path. */
    suspend fun put(cookie: SteamCookie) = store(listOf(cookie), clock.now().epochSeconds)

    /**
     * Writes the cookies of [setCookieHeaders] (a response to [url]) into the vault: a new value replaces the cookie
     * with the same name, host and path, and an expired one (`Max-Age=0`, a past `Expires`) erases it.
     */
    suspend fun save(url: Url, setCookieHeaders: List<String>) {
        if (setCookieHeaders.isEmpty()) return
        val now = clock.now().epochSeconds
        val received = setCookieHeaders.mapNotNull { header ->
            val parsed = runCatching { parseServerSetCookieHeader(header) }.getOrNull() ?: return@mapNotNull null
            if (parsed.name.isBlank()) return@mapNotNull null
            SteamCookie(
                name = parsed.name,
                value = parsed.value,
                domain = parsed.domain?.trimStart('.')?.lowercase()?.takeIf { it.isNotEmpty() } ?: url.host.lowercase(),
                path = parsed.path?.takeIf { it.startsWith("/") } ?: "/",
                expiresAtEpochSeconds = parsed.maxAge?.let { now + it } ?: parsed.expires?.timestamp?.div(1000),
                httpOnly = parsed.httpOnly,
            )
        }
        if (received.isNotEmpty()) store(received, now)
    }

    private suspend fun store(received: List<SteamCookie>, now: Long) = mutex.withLock {
        val kept = vault.readSteamCookies().filterNot { stored -> received.any { it.sameSlot(stored) } }
        vault.writeSteamCookies(kept + received.filterNot { it.expiredAt(now) })
    }

    private fun SteamCookie.appliesTo(url: Url): Boolean {
        if (!url.host.equals(domain, ignoreCase = true)) return false
        val requestPath = url.encodedPath.ifEmpty { "/" }
        return requestPath == path || requestPath.startsWith(if (path.endsWith("/")) path else "$path/")
    }

    private fun SteamCookie.expiredAt(nowEpochSeconds: Long) = expiresAtEpochSeconds?.let { it <= nowEpochSeconds } ?: false

    private fun SteamCookie.sameSlot(other: SteamCookie) =
        name == other.name && domain.equals(other.domain, ignoreCase = true) && path == other.path
}

/**
 * Sends the [VaultCookieJar]'s cookies with every request and saves every `Set-Cookie` back into it. Installed on the
 * mobile Steam client in place of the engine's own cookie store, which stays off: cookies live in the vault only.
 * Unlike Ktor's `HttpCookies` it logs nothing about cookies at any level.
 */
internal fun vaultCookiesPlugin(jar: VaultCookieJar) = createClientPlugin("SteamVaultCookies") {
    onRequest { request, _ ->
        val header = jar.cookieHeader(Url(request.url.buildString()))
        if (header == null) request.headers.remove(HttpHeaders.Cookie) else request.headers[HttpHeaders.Cookie] = header
    }
    onResponse { response ->
        jar.save(response.call.request.url, response.headers.getAll(HttpHeaders.SetCookie).orEmpty())
    }
}
