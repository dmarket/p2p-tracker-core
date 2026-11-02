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

    private fun stubCookies(httpOnly: Boolean?) {
        val g: dynamic = js("globalThis")
        g["_seedHttpOnly"] = httpOnly
        js(
            """
            globalThis._setCalls = [];
            globalThis._jar = {};
            var seed = globalThis._seedHttpOnly;
            if (seed !== null && seed !== undefined) {
                ['dm-trade-token', 'dm-trade-refresh-token'].forEach(function (n) {
                    globalThis._jar[n] = { value: 'old', hostOnly: true, path: '/', secure: true, httpOnly: seed };
                });
            }
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
}
