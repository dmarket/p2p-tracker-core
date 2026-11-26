package com.dmarket.p2p.tracker.adapter.host

import com.dmarket.p2p.tracker.model.steam.SteamCookie
import com.dmarket.p2p.tracker.port.host.Clock
import com.dmarket.p2p.tracker.port.host.SteamCookieStore
import com.dmarket.p2p.tracker.port.host.SteamCookieVault
import io.ktor.http.Url
import io.ktor.http.parseServerSetCookieHeader
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [SteamCookieStore] over the core's own [SteamCookieVault] (the sealed vault, the mobile default): the core matches
 * cookies to URLs itself.
 *
 * A cookie goes to the exact host it was stored for, never to a subdomain or a parent, and only under its path. So
 * `steamRefresh_steam` (stored for `login.steampowered.com`) reaches that host alone, and each domain's
 * `steamLoginSecure` stays on its own domain. shortcut: no public-suffix or `Domain=` widening, which the Steam hosts
 * this serves never need; revisit if a cookie must reach a subdomain.
 *
 * A read-merge-write runs under one lock, so two writes never lose each other's cookie. Values never reach a log.
 */
class VaultSteamCookieStore(private val vault: SteamCookieVault, private val clock: Clock) : SteamCookieStore {
    private val mutex = Mutex()

    override suspend fun cookieHeader(url: String): String? {
        val target = Url(url)
        val now = clock.now().epochSeconds
        return vault.readSteamCookies()
            .filter { it.appliesTo(target) && !it.expiredAt(now) }
            .joinToString("; ") { "${it.name}=${it.value}" }
            .ifEmpty { null }
    }

    override suspend fun value(url: String, name: String): String? {
        val target = Url(url)
        val now = clock.now().epochSeconds
        return vault.readSteamCookies().firstOrNull { it.name == name && it.appliesTo(target) && !it.expiredAt(now) }?.value
    }

    /**
     * A new value replaces the cookie with the same name, host and path, and an expired one (`Max-Age=0`, a past
     * `Expires`) erases it. A cookie whose `Domain=` does not domain-match [url]'s host (RFC 6265 §5.3) is dropped, so
     * one host never writes another's; one that does is kept for its `Domain=` host exactly.
     */
    override suspend fun store(url: String, setCookieLines: List<String>) {
        if (setCookieLines.isEmpty()) return
        val now = clock.now().epochSeconds
        val host = Url(url).host.lowercase()
        val received = setCookieLines.mapNotNull { header ->
            val parsed = runCatching { parseServerSetCookieHeader(header) }.getOrNull() ?: return@mapNotNull null
            if (parsed.name.isBlank()) return@mapNotNull null
            val domain = parsed.domain?.trimStart('.')?.lowercase()?.takeIf { it.isNotEmpty() } ?: host
            if (host != domain && !host.endsWith(".$domain")) return@mapNotNull null
            SteamCookie(
                name = parsed.name,
                value = parsed.value,
                domain = domain,
                path = parsed.path?.takeIf { it.startsWith("/") } ?: "/",
                expiresAtEpochSeconds = parsed.maxAge?.let { now + it } ?: parsed.expires?.timestamp?.div(1000),
                httpOnly = parsed.httpOnly,
            )
        }
        if (received.isNotEmpty()) merge(received, now)
    }

    override suspend fun set(url: String, name: String, value: String, expiresAtEpochSeconds: Long?, httpOnly: Boolean) =
        merge(listOf(SteamCookie(name, value, Url(url).host.lowercase(), "/", expiresAtEpochSeconds, httpOnly)), clock.now().epochSeconds)

    override suspend fun clearSession() = mutex.withLock { vault.clearSteamCookies() }

    private suspend fun merge(received: List<SteamCookie>, now: Long) = mutex.withLock {
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
