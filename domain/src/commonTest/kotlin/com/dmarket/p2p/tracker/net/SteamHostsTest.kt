package com.dmarket.p2p.tracker.net

import com.dmarket.p2p.tracker.config.SteamEndpointsConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The host guard for the Steam endpoint bases. What it protects: the Steam JWT rides on
 * `steamApiBaseUrl` as an `access_token` query parameter, and the session-transfer secrets are POSTed
 * to the other three bases — all four are host-suppliable, so this is the line between "paths are
 * tunable" and "credentials can be pointed anywhere".
 */
class SteamHostsTest {

    @Test
    fun hostOf_extracts_the_host_from_the_url_shapes_that_reach_it() {
        val cases = listOf(
            "https://api.steampowered.com" to "api.steampowered.com",
            "https://api.steampowered.com/" to "api.steampowered.com",
            "https://api.steampowered.com/IEconService/GetTradeOffer/v1/?x=1" to "api.steampowered.com",
            "https://API.SteamPowered.COM/x" to "api.steampowered.com",
            "https://steamcommunity.com:443/login/settoken" to "steamcommunity.com",
            "https://user:pw@steamcommunity.com/x" to "steamcommunity.com",
            "https://steamcommunity.com?q=1" to "steamcommunity.com",
            "https://steamcommunity.com#f" to "steamcommunity.com",
        )
        cases.forEach { (url, host) -> assertEquals(host, SteamHosts.hostOf(url), url) }
    }

    @Test
    fun hostOf_returns_null_for_anything_that_is_not_an_absolute_url() {
        listOf("", "steamcommunity.com", "/login/settoken", "https://", "https:///path").forEach {
            assertEquals(null, SteamHosts.hostOf(it), "'$it' is not an absolute URL with a host")
        }
    }

    @Test
    fun isAllowed_accepts_exactly_the_compiled_in_hosts() {
        SteamHosts.WEB.forEach { assertTrue(SteamHosts.isAllowed("https://$it/login/settoken", SteamHosts.WEB), it) }
        assertTrue(SteamHosts.isAllowed("https://api.steampowered.com", SteamHosts.API))
    }

    @Test
    fun isAllowed_rejects_a_look_alike_host_a_suffix_test_would_pass() {
        // The whole reason this is exact-host and not `endsWith`: every one of these ends with a Steam
        // domain, and every one is a different origin.
        listOf(
            "https://evil-steamcommunity.com/login/settoken",
            "https://steamcommunity.com.evil.example/login/settoken",
            "https://notsteamcommunity.com",
            "https://sub.steamcommunity.com",
        ).forEach { assertFalse(SteamHosts.isAllowed(it, SteamHosts.WEB), it) }
    }

    @Test
    fun isAllowed_rejects_a_non_https_scheme_and_a_cross_set_host() {
        assertFalse(SteamHosts.isAllowed("http://steamcommunity.com", SteamHosts.WEB), "plaintext must not pass")
        // The two sets are separate on purpose: the settoken secrets have no business on the Web API host,
        // and the token-bearing reads have none on the login host.
        assertFalse(SteamHosts.isAllowed("https://api.steampowered.com", SteamHosts.WEB))
        assertFalse(SteamHosts.isAllowed("https://login.steampowered.com", SteamHosts.API))
    }

    @Test
    fun requireAllowed_names_the_field_and_the_accepted_hosts() {
        val error = assertFailsWith<IllegalArgumentException> {
            SteamHosts.requireAllowed("https://evil.example.com", SteamHosts.API, "steamApiBaseUrl")
        }
        assertTrue("steamApiBaseUrl" in error.message!!, error.message!!)
        assertTrue("api.steampowered.com" in error.message!!, error.message!!)
    }

    // ---- the config guard --------------------------------------------------------------------------

    @Test
    fun the_shipped_defaults_construct() {
        // Guards the guard: a validation set that excluded a default would throw on every construction.
        val config = SteamEndpointsConfig()
        assertEquals("https://api.steampowered.com", config.steamApiBaseUrl)
        assertEquals("https://login.steampowered.com", config.loginBaseUrl)
    }

    @Test
    fun a_host_supplied_base_off_steam_is_refused_on_every_field() {
        val off = "https://evil.example.com"
        assertFailsWith<IllegalArgumentException> { SteamEndpointsConfig(steamApiBaseUrl = off) }
        assertFailsWith<IllegalArgumentException> { SteamEndpointsConfig(loginBaseUrl = off) }
        assertFailsWith<IllegalArgumentException> { SteamEndpointsConfig(communityBaseUrl = off) }
        assertFailsWith<IllegalArgumentException> { SteamEndpointsConfig(storeBaseUrl = off) }
    }

    @Test
    fun copy_revalidates_so_a_remote_override_cannot_slip_past_construction() {
        // `copy()` is how a host actually applies remote overrides, so the guard has to fire there too.
        assertFailsWith<IllegalArgumentException> {
            SteamEndpointsConfig().copy(steamApiBaseUrl = "https://evil.example.com")
        }
    }

    @Test
    fun a_path_that_moves_the_host_is_refused_even_though_the_base_is_allowed() {
        // The bypass the base-only guard missed: a request URL is base + path, and `@` makes the checked
        // base parse as userinfo — the effective host becomes the attacker's, carrying the Steam JWT with
        // it. Same idea with a backslash, which some parsers fold into `/`.
        listOf("@evil.example.com/", "@evil.example.com/IEconService/GetTradeOffer/v1/", "\\evil.example.com/").forEach { path ->
            val error = assertFailsWith<IllegalArgumentException>("path '$path' must be refused") {
                SteamEndpointsConfig(getTradeOfferPath = path)
            }
            assertTrue("getTradeOfferPath" in error.message!!, error.message!!)
        }
        // Every JWT-bearing path field is guarded, not just the first one.
        assertFailsWith<IllegalArgumentException> { SteamEndpointsConfig(getTradeOffersPath = "@evil.example.com/") }
        assertFailsWith<IllegalArgumentException> { SteamEndpointsConfig(getTradeHistoryPath = "@evil.example.com/") }
        assertFailsWith<IllegalArgumentException> { SteamEndpointsConfig(getPlayerSummariesPath = "@evil.example.com/") }
        assertFailsWith<IllegalArgumentException> { SteamEndpointsConfig(getSteamLevelPath = "@evil.example.com/") }
        assertFailsWith<IllegalArgumentException> { SteamEndpointsConfig(getSteamNotificationsPath = "@evil.example.com/") }
    }

    @Test
    fun paths_and_parameter_names_stay_tunable() {
        // The point of the config: a moved Steam path is still hot-patchable without a client release.
        val config = SteamEndpointsConfig(
            getTradeOfferPath = "/IEconService/GetTradeOffer/v2/",
            paramAccessToken = "token",
        )
        assertEquals("/IEconService/GetTradeOffer/v2/", config.getTradeOfferPath)
        assertEquals("token", config.paramAccessToken)
    }
}
