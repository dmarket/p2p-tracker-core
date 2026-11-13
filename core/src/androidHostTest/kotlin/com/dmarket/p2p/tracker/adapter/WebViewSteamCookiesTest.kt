package com.dmarket.p2p.tracker.adapter

import com.dmarket.p2p.tracker.model.steam.SteamCookie
import kotlin.test.Test
import kotlin.test.assertEquals

class WebViewSteamCookiesTest {
    private val webView = mapOf(
        "https://steamcommunity.com/" to "steamLoginSecure=c; sessionid=s; steamCountry=",
        "https://store.steampowered.com/" to "steamLoginSecure=st",
        "https://login.steampowered.com/" to "steamRefresh_steam=r",
        "https://help.steampowered.com/" to "steamLoginSecure=h",
    )

    @Test
    fun readsTheThreeSessionHostsAndKeepsEachCookieOnTheHostItWasReadFor() {
        val read = WebViewSteamCookies { webView[it] }.read()

        assertEquals(
            setOf(
                SteamCookie("steamLoginSecure", "c", "steamcommunity.com"),
                SteamCookie("sessionid", "s", "steamcommunity.com"),
                SteamCookie("steamLoginSecure", "st", "store.steampowered.com"),
                SteamCookie("steamRefresh_steam", "r", "login.steampowered.com"),
            ),
            read.toSet(),
        )
    }

    @Test
    fun noCookiesReadsEmpty() {
        assertEquals(emptyList(), WebViewSteamCookies { null }.read())
    }
}
