package com.dmarket.p2p.tracker.adapter

import com.dmarket.p2p.tracker.adapter.CookieManagerSteamCookieStore.CookieSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The pure parts of [CookieManagerSteamCookieStore]; the WebView itself is in the device tests. */
class CookieManagerSteamCookieStoreTest {
    @Test
    fun aCookieInfoLineGivesItsSlotInAnyCase() {
        assertEquals(
            CookieSlot("steamLoginSecure", "steamcommunity.com", "/"),
            CookieSlot.parse(
                "steamLoginSecure=v; Domain=steamcommunity.com; Path=/; Expires=Fri, 31 Dec 2027 00:00:00 GMT; Secure; HttpOnly",
            ),
        )
        assertEquals(CookieSlot("a", ".steampowered.com", "/x"), CookieSlot.parse("a=1; domain=.steampowered.com; path=/x; secure"))
        assertEquals(CookieSlot("a", null, "/"), CookieSlot.parse("a=1"))
        assertNull(CookieSlot.parse("=1; domain=x"))
    }

    @Test
    fun aDeletionNamesTheDomainOnlyForADomainSlot() {
        assertEquals(
            "a=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Secure",
            CookieSlot("a", "store.steampowered.com", "/").deletionLine(),
        )
        assertEquals(
            "a=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Secure; Domain=.steampowered.com",
            CookieSlot("a", ".steampowered.com", "/").deletionLine(),
        )
    }

    @Test
    fun withoutCookieInfoEveryNameIsExpiredHostOnlyAndOnTheHostAndItsParent() {
        assertEquals(
            listOf(
                CookieSlot("a", null, "/"),
                CookieSlot("a", ".store.steampowered.com", "/"),
                CookieSlot("a", ".steampowered.com", "/"),
            ),
            CookieManagerSteamCookieStore.guessedSlots("store.steampowered.com", "a=1; a=2"),
        )
        assertEquals(
            listOf(CookieSlot("b", null, "/"), CookieSlot("b", ".steam.tv", "/")),
            CookieManagerSteamCookieStore.guessedSlots("steam.tv", "b=1"),
        )
        assertEquals(emptyList(), CookieManagerSteamCookieStore.guessedSlots("steam.tv", null))
    }

    @Test
    fun aCookieTheCoreMakesIsHostOnlySecureAndKeepsItsExpiry() {
        assertEquals(
            "sessionid=s; Path=/; Secure; SameSite=None",
            CookieManagerSteamCookieStore.cookieLine("sessionid", "s", null, httpOnly = false),
        )
        assertEquals(
            "steamLoginSecure=v; Path=/; Secure; SameSite=None; HttpOnly; Expires=Thu, 01 Jan 2026 00:00:00 GMT",
            CookieManagerSteamCookieStore.cookieLine("steamLoginSecure", "v", 1_767_225_600, httpOnly = true),
        )
    }
}
