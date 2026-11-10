package com.dmarket.p2p.tracker.client.marketplace

import com.dmarket.p2p.tracker.model.marketplace.MarketplaceTokenPair
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A rotation rewrites both cookies through `cookies.set`, where an absent `httpOnly` means non-HttpOnly. These
 * tests pin that the flag of the record that was read is mirrored, and that a store which never read a record
 * keeps the non-HttpOnly default. `globalThis.chrome.cookies` is stubbed: `get` serves the seeded records and
 * `set` stores what it is given (so the verify read-back matches) and logs each call.
 */
class FetchMarketplaceCookieTokenStoreTest {

    /** `null` leaves that cookie out of the jar. */
    private fun stubCookies(httpOnly: Boolean?, refreshHttpOnly: Boolean? = httpOnly) {
        val g: dynamic = js("globalThis")
        g["_seedHttpOnly"] = arrayOf(httpOnly, refreshHttpOnly)
        js(
            """
            globalThis._setCalls = [];
            globalThis._jar = {};
            ['dm-trade-token', 'dm-trade-refresh-token'].forEach(function (n, i) {
                var seed = globalThis._seedHttpOnly[i];
                if (seed !== null && seed !== undefined) {
                    globalThis._jar[n] = { value: 'old', hostOnly: true, path: '/', secure: true, httpOnly: seed };
                }
            });
            globalThis.chrome = { cookies: {
                get: function (d) { return Promise.resolve(globalThis._jar[d.name] || null); },
                set: function (d) { globalThis._setCalls.push(d); globalThis._jar[d.name] = d; return Promise.resolve(d); }
            } };
            """,
        )
    }

    @AfterTest
    fun cleanup() {
        js("delete globalThis.chrome; delete globalThis._setCalls; delete globalThis._jar; delete globalThis._seedHttpOnly;")
    }

    private fun setCallFlags(): List<Boolean?> {
        val calls: dynamic = js("globalThis._setCalls")
        return (0 until (calls.length as Int)).map { calls[it].httpOnly as? Boolean }
    }

    private fun setCallNames(): List<String?> {
        val calls: dynamic = js("globalThis._setCalls")
        return (0 until (calls.length as Int)).map { calls[it].name as? String }
    }

    private val tokens = MarketplaceTokenPair("access", "refresh", null)

    @Test
    fun rewrite_mirrors_httpOnly_true_from_the_record_that_was_read() = runTest {
        stubCookies(httpOnly = true)
        val store = FetchMarketplaceCookieTokenStore()
        store.read()
        store.write(tokens)
        assertEquals(listOf<Boolean?>(true, true), setCallFlags())
    }

    @Test
    fun rewrite_mirrors_httpOnly_false_from_the_record_that_was_read() = runTest {
        stubCookies(httpOnly = false)
        val store = FetchMarketplaceCookieTokenStore()
        store.read()
        store.write(tokens)
        assertEquals(listOf<Boolean?>(false, false), setCallFlags())
    }

    @Test
    fun default_template_stays_non_httpOnly_when_nothing_was_read() = runTest {
        stubCookies(httpOnly = null)
        FetchMarketplaceCookieTokenStore().write(tokens)
        assertEquals(listOf<Boolean?>(false, false), setCallFlags())
    }

    @Test
    fun each_cookie_is_rewritten_with_its_own_httpOnly() = runTest {
        stubCookies(httpOnly = true, refreshHttpOnly = false)
        val store = FetchMarketplaceCookieTokenStore()
        store.read()
        store.write(tokens)
        // Refresh first, then access; each keeps the flag it was read with.
        assertEquals(listOf<String?>("dm-trade-refresh-token", "dm-trade-token"), setCallNames())
        assertEquals(listOf<Boolean?>(false, true), setCallFlags())
    }

    @Test
    fun a_missing_cookie_borrows_the_other_cookies_attributes() = runTest {
        stubCookies(httpOnly = true, refreshHttpOnly = null)
        val store = FetchMarketplaceCookieTokenStore()
        store.read()
        store.write(tokens)
        assertEquals(listOf<Boolean?>(true, true), setCallFlags())
    }
}
