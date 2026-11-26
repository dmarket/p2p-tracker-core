package com.dmarket.p2p.tracker.support

import com.dmarket.p2p.tracker.port.host.Clock
import com.dmarket.p2p.tracker.port.host.SteamCookieStore
import com.dmarket.p2p.tracker.port.host.SteamCookieStoreException
import io.ktor.http.Url
import io.ktor.http.parseServerSetCookieHeader

/**
 * A [SteamCookieStore] that behaves as the WebView's store measured in spike S2: a slot is (name, domain, host-only,
 * path), so a host-only cookie and a `Domain=` one of the same name are two cookies; a `Domain=` cookie reaches the
 * domain's subdomains, a host-only one its host alone; `Max-Age=0` or a past `Expires` deletes the slot it names; a
 * `Domain=` that does not domain-match the response's host is dropped. [failReads] / [failWrites] make the store throw.
 */
class FakeBrowserCookieStore(private val clock: Clock) : SteamCookieStore {
    data class Slot(
        val name: String,
        val value: String,
        val domain: String,
        val hostOnly: Boolean,
        val path: String,
        val expires: Long?,
        val httpOnly: Boolean,
    ) {
        override fun toString(): String = "Slot(name=$name, domain=$domain, hostOnly=$hostOnly, path=$path)"
    }

    val slots = mutableListOf<Slot>()
    var failReads = false
    var failWrites = false
    var clears = 0

    override suspend fun cookieHeader(url: String): String? = matching(url).joinToString("; ") { "${it.name}=${it.value}" }.ifEmpty { null }

    override suspend fun value(url: String, name: String): String? = matching(url).firstOrNull { it.name == name }?.value

    override suspend fun store(url: String, setCookieLines: List<String>) {
        if (failWrites) throw SteamCookieStoreException("write failed")
        val host = Url(url).host.lowercase()
        val now = clock.now().epochSeconds
        for (line in setCookieLines) {
            val parsed = parseServerSetCookieHeader(line)
            val domainAttr = parsed.domain?.trimStart('.')?.lowercase()?.takeIf { it.isNotEmpty() }
            if (domainAttr != null && host != domainAttr && !host.endsWith(".$domainAttr")) continue
            val expires = parsed.maxAge?.let { now + it } ?: parsed.expires?.timestamp?.div(1000)
            put(Slot(parsed.name, parsed.value, domainAttr ?: host, domainAttr == null, parsed.path ?: "/", expires, parsed.httpOnly), now)
        }
    }

    override suspend fun set(url: String, name: String, value: String, expiresAtEpochSeconds: Long?, httpOnly: Boolean) {
        if (failWrites) throw SteamCookieStoreException("write failed")
        put(Slot(name, value, Url(url).host.lowercase(), true, "/", expiresAtEpochSeconds, httpOnly), clock.now().epochSeconds)
    }

    override suspend fun clearSession() {
        if (failWrites) throw SteamCookieStoreException("write failed")
        clears++
        slots.removeAll { slot -> STEAM_DOMAINS.any { slot.domain == it || slot.domain.endsWith(".$it") } }
    }

    /** Puts a slot as a browser's sign-in would leave it (host-only, path `/`). */
    fun signIn(host: String, name: String, value: String, expires: Long? = null) {
        put(Slot(name, value, host, true, "/", expires, httpOnly = true), clock.now().epochSeconds)
    }

    private fun put(slot: Slot, now: Long) {
        slots.removeAll { it.name == slot.name && it.domain == slot.domain && it.hostOnly == slot.hostOnly && it.path == slot.path }
        if (slot.expires == null || slot.expires > now) slots += slot
    }

    private fun matching(url: String): List<Slot> {
        if (failReads) throw SteamCookieStoreException("read failed")
        val target = Url(url)
        val host = target.host.lowercase()
        val path = target.encodedPath.ifEmpty { "/" }
        val now = clock.now().epochSeconds
        return slots.filter { slot ->
            val hostMatches = if (slot.hostOnly) host == slot.domain else host == slot.domain || host.endsWith(".${slot.domain}")
            hostMatches &&
                (path == slot.path || path.startsWith(slot.path.trimEnd('/') + "/")) &&
                (slot.expires == null || slot.expires > now)
        }
    }

    private companion object {
        val STEAM_DOMAINS = listOf("steamcommunity.com", "steampowered.com", "steam.tv")
    }
}
