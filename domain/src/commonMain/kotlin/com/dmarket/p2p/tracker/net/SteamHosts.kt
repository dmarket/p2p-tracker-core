package com.dmarket.p2p.tracker.net

/**
 * The Steam hosts this client may talk to, and the one URL-host parser everything checks against.
 *
 * **Why this exists.** The Steam endpoint bases are host-suppliable
 * ([com.dmarket.p2p.tracker.config.SteamEndpointsConfig]) so a moved Steam path can be hot-patched
 * without a client release — but the Steam JWT rides on those bases as an `access_token` query
 * parameter, and the session-transfer secrets are POSTed to them. Left unchecked, a single wrong
 * string in a host- or remotely-supplied config would send device-only credentials to any origin,
 * which is exactly what the audit boundary promises cannot happen. So the *paths and parameter names*
 * stay tunable while the *hosts* are compiled in here.
 *
 * Pure string handling — no URL library, because this module is zero-dependency by design.
 */
object SteamHosts {

    /** Web API host: `IEconService` / `IPlayerService` / notification reads, authed by query token. */
    val API: Set<String> = setOf("api.steampowered.com")

    /**
     * Steam web hosts: the session-refresh and community/store surfaces. Exact-host matching, no
     * subdomain wildcard — deliberately strict, since this also gates where the `settoken`
     * session-transfer secrets may be sent.
     */
    val WEB: Set<String> = setOf(
        "login.steampowered.com",
        "steamcommunity.com",
        "store.steampowered.com",
        "help.steampowered.com",
    )

    /** Extracts the lower-cased host from an absolute URL, or `null` if it is not one. */
    fun hostOf(url: String): String? {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return null
        val afterScheme = url.substring(schemeEnd + 3)
        val end = afterScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (end < 0) afterScheme else afterScheme.substring(0, end)
        val host = authority.substringAfterLast('@').substringBefore(':').lowercase()
        return host.ifEmpty { null }
    }

    /** True if [url] is `https` and its host is exactly one of [allowed]. */
    fun isAllowed(url: String, allowed: Set<String>): Boolean = url.startsWith("https://", ignoreCase = true) && hostOf(url) in allowed

    /**
     * Guards a configured endpoint base: `https` + an [allowed] host, or [IllegalArgumentException]
     * naming the offending field. Fails hard on purpose — quietly substituting the default would be a
     * silent security downgrade, and a config this wrong is a bug in the host, not a runtime condition.
     */
    fun requireAllowed(url: String, allowed: Set<String>, field: String) {
        require(isAllowed(url, allowed)) {
            "$field must be an https URL on one of ${allowed.sorted().joinToString(", ")}, was '$url'"
        }
    }

    /**
     * Guards a configured **path**, by checking the URL it actually produces once appended to [base].
     * Validating the base alone is not enough: a request URL is `base + path`, so a path may move the
     * effective host even though the base is allow-listed. `"@evil.example.com/"` is the canonical case
     * — `https://api.steampowered.com` + that path parses as *userinfo* `api.steampowered.com` on host
     * `evil.example.com`, which a URL parser reads exactly as written and which would carry the Steam
     * JWT (an `access_token` query parameter on these reads) straight off Steam.
     */
    fun requirePathKeepsHost(base: String, path: String, allowed: Set<String>, field: String) {
        require(isAllowed(base + path, allowed)) {
            "$field must be a path that keeps the request on ${allowed.sorted().joinToString(", ")}: " +
                "'$base$path' resolves to host '${hostOf(base + path)}'"
        }
    }
}
