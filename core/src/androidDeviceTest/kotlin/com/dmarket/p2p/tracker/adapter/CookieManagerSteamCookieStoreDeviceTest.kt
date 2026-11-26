package com.dmarket.p2p.tracker.adapter

import android.os.Looper
import android.util.AndroidRuntimeException
import android.webkit.CookieManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.CookieManagerCompat
import androidx.webkit.WebViewFeature
import com.dmarket.p2p.tracker.port.host.SteamCookieStoreException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [CookieManagerSteamCookieStore] on a real WebView (spike S2 as tests): the two-argument `setCookie` from a thread with
 * no Looper, `Max-Age=0` deletion, host-only against `Domain=` slots, and [CookieManagerSteamCookieStore.clearSession]
 * over every Steam host. Runs in the test app's own WebView data; nothing reaches the network.
 */
@RunWith(AndroidJUnit4::class)
class CookieManagerSteamCookieStoreDeviceTest {
    private val store = CookieManagerSteamCookieStore()
    private lateinit var manager: CookieManager

    private val community = "https://steamcommunity.com/"
    private val storeHost = "https://store.steampowered.com/"
    private val help = "https://help.steampowered.com/"

    @Before
    fun emptyTheStore() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { manager = CookieManager.getInstance() }
        val removed = CountDownLatch(1)
        instrumentation.runOnMainSync { manager.removeAllCookies { removed.countDown() } }
        assertTrue(removed.await(10, TimeUnit.SECONDS))
    }

    @After
    fun leaveNothing() = emptyTheStore()

    private fun info(url: String): List<String> = CookieManagerCompat.getCookieInfo(manager, url)

    private fun infoOf(url: String, name: String): String = info(url).single { it.startsWith("$name=") }.lowercase()

    @Test
    fun aCookieTheCoreSetsIsReadAtOnceFromAThreadWithNoLooper() = runBlocking {
        assertNull("the test thread has no Looper", Looper.myLooper())

        store.set(community, "sessionid", "sid-1", expiresAtEpochSeconds = null, httpOnly = false)

        assertEquals("sid-1", store.value(community, "sessionid"))
        assertEquals("sessionid=sid-1", store.cookieHeader(community))
        assertNull(store.value(storeHost, "sessionid"))
    }

    @Test
    fun aSetCookieKeepsItsAttributesAndStaysPersistent() = runBlocking {
        assertTrue(WebViewFeature.isFeatureSupported(WebViewFeature.GET_COOKIE_INFO))
        val expires = System.currentTimeMillis() / 1000 + 86_400

        store.set(community, "steamLoginSecure", "jwt", expires, httpOnly = true)
        store.set(community, "sessionid", "sid", expiresAtEpochSeconds = null, httpOnly = false)

        val loginSecure = infoOf(community, "steamLoginSecure")
        assertTrue(loginSecure, "domain=steamcommunity.com" in loginSecure)
        assertTrue(loginSecure, "expires=" in loginSecure)
        assertTrue(loginSecure, "httponly" in loginSecure)
        assertTrue(loginSecure, "secure" in loginSecure)
        assertFalse(infoOf(community, "sessionid"), "httponly" in infoOf(community, "sessionid"))
    }

    @Test
    fun rawSetCookieLinesAreStoredAsSentAndMaxAgeZeroDeletes() = runBlocking {
        store.store(community, listOf("steamCountry=UA; Path=/; Secure; HttpOnly; Max-Age=3600"))
        assertEquals("UA", store.value(community, "steamCountry"))

        store.store(community, listOf("steamCountry=; Path=/; Max-Age=0"))
        assertNull(store.value(community, "steamCountry"))
        assertNull(store.cookieHeader(community))
    }

    @Test
    fun aForeignDomainIsDropped() = runBlocking {
        store.store(community, listOf("x=1; Domain=example.org; Path=/"))

        assertNull(manager.getCookie("https://example.org/"))
        assertNull(store.cookieHeader(community))
    }

    @Test
    fun hostOnlyAndDomainCookiesAreTwoSlotsAndClearingRemovesBoth() = runBlocking {
        store.store(
            storeHost,
            listOf("steamLoginSecure=host; Path=/; Secure", "steamLoginSecure=dom; Domain=steampowered.com; Path=/; Secure"),
        )
        assertEquals(2, store.cookieHeader(storeHost)!!.split("; ").size)
        assertEquals("steamLoginSecure=dom", store.cookieHeader(help))
        val slots = info(storeHost).mapNotNull(CookieManagerSteamCookieStore.CookieSlot::parse).map { it.domain }.toSet()
        assertEquals(setOf("store.steampowered.com", ".steampowered.com"), slots)

        // A deletion without Domain= removes the host-only slot alone (S2).
        store.store(storeHost, listOf("steamLoginSecure=; Path=/; Max-Age=0"))
        assertEquals("steamLoginSecure=dom", store.cookieHeader(storeHost))

        store.clearSession()
        assertNull(store.cookieHeader(storeHost))
        assertNull(store.cookieHeader(help))
    }

    @Test
    fun clearingTheSessionDeletesEverySteamHostAndLeavesOtherSites() = runBlocking {
        val expires = System.currentTimeMillis() / 1000 + 86_400
        store.set(community, "steamLoginSecure", "c", expires, httpOnly = true)
        store.set(community, "browserid", "b", expires, httpOnly = false)
        store.set(storeHost, "steamLoginSecure", "s", expires, httpOnly = true)
        store.set("https://login.steampowered.com/", "steamRefresh_steam", "r", expires, httpOnly = true)
        store.set(help, "steamLoginSecure", "h", expires, httpOnly = true)
        store.set("https://checkout.steampowered.com/", "steamLoginSecure", "k", expires, httpOnly = true)
        store.set("https://steam.tv/", "steamLoginSecure", "t", expires, httpOnly = true)
        store.set("https://example.org/", "other", "kept", expires, httpOnly = false)

        store.clearSession()

        for (host in CookieManagerSteamCookieStore.CLEARED_HOSTS) {
            assertNull(host, store.cookieHeader("https://$host/"))
        }
        assertEquals("other=kept", manager.getCookie("https://example.org/"))
    }

    @Test
    fun aStoreThatCannotLoadThrowsInsteadOfReadingEmpty() = runBlocking {
        val broken = CookieManagerSteamCookieStore(cookieManager = { throw AndroidRuntimeException("provider failed") })

        for (call in listOf<suspend () -> Unit>({
            broken.cookieHeader(community)
        }, { broken.value(community, "x") }, { broken.clearSession() })) {
            try {
                call()
                fail("a store that cannot load must throw")
            } catch (_: SteamCookieStoreException) {
            }
        }
    }
}
