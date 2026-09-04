package com.dmarket.p2p.tracker.client.marketplace

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The guard in front of the one request that carries a ~30-day account credential.
 *
 * The Steam side already allow-lists hosts before POSTing session secrets; this is the marketplace analogue,
 * and it matters more here because the path is remote-config overridable.
 */
class RefreshUrlResolutionTest {

    private val api = "https://api.dmarket.com"
    private val site = "https://dmarket.com/"

    @Test
    fun derives_the_endpoint_from_the_api_base_and_path() {
        assertEquals(
            "https://api.dmarket.com/marketplace-api/v1/refresh-token",
            resolveRefreshUrl(api, site, "/marketplace-api/v1/refresh-token", override = null),
        )
    }

    @Test
    fun tolerates_a_trailing_slash_on_the_base() {
        assertEquals(
            "https://api.dmarket.com/marketplace-api/v1/refresh-token",
            resolveRefreshUrl("https://api.dmarket.com/", site, "/marketplace-api/v1/refresh-token", null),
        )
    }

    @Test
    fun accepts_an_override_on_the_api_or_site_origin() {
        assertEquals(
            "https://api.dmarket.com/other/refresh",
            resolveRefreshUrl(api, site, "/x", override = "https://api.dmarket.com/other/refresh"),
        )
        // A dev deployment that proxies the endpoint through the site origin is the reason this exists.
        assertEquals(
            "https://dmarket.com/marketplace-api/v1/refresh-token",
            resolveRefreshUrl(api, site, "/x", override = "https://dmarket.com/marketplace-api/v1/refresh-token"),
        )
    }

    /** An off-origin override is ignored and the derived endpoint is used — the function is total. */
    @Test
    fun rejects_an_override_on_any_other_origin() {
        val derived = "https://api.dmarket.com/marketplace-api/v1/refresh-token"
        val path = "/marketplace-api/v1/refresh-token"
        assertEquals(derived, resolveRefreshUrl(api, site, path, override = "https://evil.example/collect"))
        assertEquals(derived, resolveRefreshUrl(api, site, path, override = "http://api.dmarket.com/x"), "scheme must match")
        assertEquals(derived, resolveRefreshUrl(api, site, path, override = "https://api.dmarket.com.evil.example/x"))
        assertEquals(derived, resolveRefreshUrl(api, site, path, override = "//api.dmarket.com/x"), "no scheme at all")
    }

    @Test
    fun a_userinfo_prefix_cannot_impersonate_a_trusted_origin() {
        // `https://api.dmarket.com@evil.example/x` has host evil.example — a naive prefix check would pass it.
        assertEquals(
            "https://api.dmarket.com/marketplace-api/v1/refresh-token",
            resolveRefreshUrl(api, site, "/marketplace-api/v1/refresh-token", "https://api.dmarket.com@evil.example/x"),
        )
    }

    /**
     * A path that would move the request off the allowed hosts falls back to the compiled default, rather than
     * being caught by a blacklist of suspicious substrings: the check composes `base + path` and re-parses the
     * host, so these all fail by construction.
     */
    @Test
    fun a_path_that_could_leave_the_host_falls_back_to_the_compiled_default() {
        val derived = "https://api.dmarket.com/marketplace-api/v1/refresh-token"
        assertEquals(derived, resolveRefreshUrl(api, site, "@evil.example/x", null), "base becomes userinfo")
        assertEquals(derived, resolveRefreshUrl(api, site, "https://evil.example/x", null))
        assertEquals(derived, resolveRefreshUrl(api, site, "marketplace-api/v1/refresh-token", null), "must be rooted")
    }

    /**
     * `//evil.example/x` appended to a base that already carries a scheme is just an empty path segment —
     * `https://api.dmarket.com//evil.example/x` requests api.dmarket.com — so composing and re-parsing accepts
     * it, correctly. It is only a protocol-relative URL when the base has no scheme, which cannot happen here.
     * (The extension's own validator still refuses to publish it, so it never arrives in practice.)
     */
    @Test
    fun a_double_slash_path_stays_on_the_host_and_is_therefore_accepted() {
        assertEquals(
            "https://api.dmarket.com//evil.example/x",
            resolveRefreshUrl(api, site, "//evil.example/x", null),
        )
    }

    @Test
    fun a_case_or_query_difference_in_the_override_origin_still_matches() {
        val override = "https://API.DMarket.com/marketplace-api/v1/refresh-token?x=1"
        assertEquals(override, resolveRefreshUrl(api, site, "/marketplace-api/v1/refresh-token", override))
    }
}
