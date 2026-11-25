package com.dmarket.p2p.headless

import com.dmarket.p2p.headless.node.LoopbackProxy
import com.dmarket.p2p.headless.node.LoopbackServer
import com.dmarket.p2p.headless.node.tempDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A Steam proxy pushed with the token, through the facade. The Steam calls go out on the production Steam engine to
 * Steam's real host names, but every proxy here refuses each `CONNECT` (or is not there at all), so nothing leaves the
 * machine: what is asserted is which proxy each tracker's Steam traffic went to, that nothing went around it, and
 * what the host is told.
 */
class SteamProxyFacadeTest {
    private val proxies = mutableListOf<LoopbackProxy>()
    private val servers = mutableListOf<LoopbackServer>()
    private val handles = mutableListOf<Any>()

    @AfterTest
    fun closeAll() {
        handles.forEach { runCatching { stop(it) } }
        proxies.forEach { it.close() }
        servers.forEach { it.close() }
    }

    private val createLease = """
        {"activeTracking":[{"dealId":"deal-1","watch":["GetTradeOffer"],"role":"seller"}],
         "directives":[{"directiveId":"dir-1","action":"create_offer","dealId":"deal-1","partnerSteamId":"$OTHER_STEAM_ID",
           "assetIds":["asset-7"],"tradeToken":"tok","contextId":2}],
         "ttlSeconds":100,"linkedSteamId":"$STEAM_ID"}
    """.trimIndent()

    private suspend fun refusingProxy(user: String, password: String) = LoopbackProxy.start(user, password, refuseWith = 502).also {
        proxies +=
            it
    }

    /** A tracker whose DMarket is [TestHost.backend]'s mock and whose Steam calls go out on the production engine. */
    private suspend fun TestHost.proxiedTracker(): Any =
        createHeadlessTrackerForTest(options(tempDir()), backend.engine, idleScope(), realSteam = true).also { handles += it }

    private fun proxyOption(url: String): dynamic {
        val o: dynamic = js("({})")
        o.proxy = url
        return o
    }

    private suspend fun ready(handle: Any, proxy: String) {
        pushTradingApiKey(handle, PUBLIC_KEY, SECRET_SEED).await()
        assertEquals("held", pushSteamToken(handle, steamToken(), proxyOption(proxy)).await().type)
        withContext(Dispatchers.Default) { cycle(handle).await() }
    }

    private fun TestHost.named(name: String): List<dynamic> = events.map { js("JSON.parse")(it) }.filter { it.event == name }

    private fun basic(user: String, password: String): String = "Basic " + js("Buffer").from("$user:$password").toString("base64") as String

    private fun assertNoProxyIn(texts: List<String>, vararg parts: String) {
        for (text in texts) for (part in parts) assertFalse(part in text, "the proxy leaked: $text")
    }

    @Test
    fun two_trackers_in_one_process_each_send_steam_through_their_own_proxy_and_dmarket_through_none() = runTest {
        val first = TestHost().apply { backend.heartbeatBody = createLease }
        val second = TestHost().apply { backend.heartbeatBody = createLease }
        val firstProxy = refusingProxy("first", "first-pass")
        val secondProxy = refusingProxy("second", "second-pass")

        ready(first.proxiedTracker(), firstProxy.url)
        ready(second.proxiedTracker(), secondProxy.url)

        assertTrue(firstProxy.connects.isNotEmpty() && secondProxy.connects.isNotEmpty())
        assertTrue(firstProxy.connects.all { it.second == basic("first", "first-pass") }, "${firstProxy.connects}")
        assertTrue(secondProxy.connects.all { it.second == basic("second", "second-pass") }, "${secondProxy.connects}")
        val steamHosts = setOf("steamcommunity.com:443", "api.steampowered.com:443")
        assertTrue((firstProxy.connects + secondProxy.connects).all { it.first in steamHosts }, "only Steam is tunnelled")
        for (host in listOf(first, second)) {
            assertTrue(host.backend.requested.any { it.endsWith("/p2p/ext/heartbeat") }, "DMarket went out on its own engine")
            assertTrue(host.backend.steamRequests().isEmpty(), "no Steam call fell back to the DMarket engine")
        }
    }

    @Test
    fun an_unreachable_proxy_fails_closed_defers_the_create_and_says_which_deals_wait() = runTest {
        val host = TestHost().apply { backend.heartbeatBody = createLease }
        val closed = LoopbackProxy.closedPort()
        val url = "http://trader:s3cret-pass@127.0.0.1:$closed"
        val handle = host.proxiedTracker()

        ready(handle, url)

        assertTrue(host.backend.requested.none { it.endsWith("/p2p/ext/trade-actions") }, "the create is not reported failed")
        val deferred = host.named("SteamWriteDeferred").single()
        assertEquals(
            listOf("create_offer", "deal-1", reasons().steamProxyUnreachable),
            listOf(deferred.kind, deferred.dealId, deferred.reason),
        )
        val unreachable = host.named("SteamProxyUnreachable").single()
        assertEquals(listOf("deal-1"), (unreachable.dealIds as Array<String>).toList())
        assertEquals("could not connect to the proxy: ECONNREFUSED", unreachable.detail)
        val state = trackerState(handle)
        assertEquals(true, state.steamProxyUnreachable)
        assertEquals(T0.toEpochMilliseconds().toDouble(), state.steamProxyUnreachableSinceMs)
        assertNoProxyIn(host.events + (JSON.stringify(state)), "s3cret", "trader", ":$closed")
    }

    @Test
    fun the_proxy_is_write_only_and_a_bad_one_is_refused_without_being_quoted() = runTest {
        val host = TestHost()
        val handle = host.proxiedTracker()
        val secret = "http://trader:s3cret-pass@203.0.113.7:3128"

        // The proxy's value is refused coded, for a host to tell from its own mistakes; the options' shape is not.
        val refusals: List<Triple<dynamic, String, String?>> = listOf(
            Triple(proxyOption("socks5://trader:s3cret-pass@203.0.113.7:1080"), "SOCKS is not supported", steamProxyInvalidCode()),
            Triple(proxyOption("http://trader:s3cret-pass@203.0.113.7"), "host and a port", steamProxyInvalidCode()),
            Triple(js("({proxy: 3128})"), "proxy must be a string or null", steamProxyInvalidCode()),
            Triple(js("({proxi: 'http://trader:s3cret-pass@203.0.113.7:3128'})"), "unknown option: proxi", null),
            Triple(js("'http://trader:s3cret-pass@203.0.113.7:3128'"), "options must be an object", null),
        )
        for ((options, rule, code) in refusals) {
            val failure = runCatching { pushSteamToken(handle, steamToken(), options).await() }.exceptionOrNull()
            val message = failure?.message ?: "resolved"
            assertTrue(rule in message, message)
            assertTrue(js("(function (e) { return e instanceof Error; })")(failure) as Boolean, message)
            assertEquals(code, failure.asDynamic().code as String?, message)
            assertNoProxyIn(listOf(message), "s3cret", "trader", "203.0.113.7")
        }
        assertEquals(null, trackerState(handle).steamTokenSteamId, "a refused proxy holds no token either")

        val held = pushSteamToken(handle, steamToken(), proxyOption(secret)).await()
        assertEquals("held", held.type)
        val shown =
            listOf(JSON.stringify(held), JSON.stringify(trackerState(handle)), inspect(handle, js("({depth: 10, showHidden: true})")))
        assertNoProxyIn(shown, "s3cret", "trader", "203.0.113.7", "3128")
    }

    @Test
    fun on_the_production_engines_dmarket_goes_direct_while_steam_goes_through_the_proxy() = runTest {
        withContext(Dispatchers.Default) {
            val proxy = refusingProxy("trader", "s3cret-pass")
            val dmarket = LoopbackServer.start { request ->
                if (request.url.endsWith("/p2p/ext/heartbeat")) 200 to createLease else 200 to """{"results":[]}"""
            }.also { servers += it }
            val options = TestHost().options(tempDir())
            options.baseUrl = dmarket.base
            val handle = createHeadlessTracker(options).also { handles += it }

            pushTradingApiKey(handle, PUBLIC_KEY, SECRET_SEED).await()
            pushSteamToken(handle, steamToken(), proxyOption(proxy.url)).await()
            withTimeout(10.seconds) { while (proxy.connects.isEmpty()) delay(20.milliseconds) }

            assertTrue(dmarket.seen.any { it.url.endsWith("/p2p/ext/heartbeat") }, "the heartbeat reached DMarket directly")
            val dmarketAddress = dmarket.base.removePrefix("http://")
            assertTrue(proxy.connects.none { it.first == dmarketAddress }, "DMarket was never tunnelled: ${proxy.connects}")
            assertTrue(proxy.connects.all { it.first.endsWith(":443") && "steam" in it.first }, "${proxy.connects}")
        }
    }

    @Test
    fun a_renewal_without_a_proxy_keeps_it_and_only_null_sends_steam_direct() = runTest {
        val host = TestHost().apply { backend.heartbeatBody = createLease }
        val proxy = refusingProxy("trader", "s3cret-pass")
        val handle = host.proxiedTracker()
        ready(handle, proxy.url)
        val tunnelled = proxy.connects.size
        assertTrue(tunnelled > 0)

        for (renewal in listOf<dynamic>(undefined, js("({})"), js("({proxy: undefined})"))) {
            assertEquals("held", pushSteamToken(handle, steamToken(), renewal).await().type)
        }
        assertEquals(true, trackerState(handle).steamProxyUnreachable, "the renewals kept the proxy, and its problem with it")
        withContext(Dispatchers.Default) { cycle(handle).await() }
        assertTrue(proxy.connects.size > tunnelled, "the renewed token still goes through the proxy")
        assertTrue(host.backend.steamRequests().isEmpty(), "nothing went around it")

        // No cycle after it: with no proxy the Steam calls would go to Steam's real hosts.
        assertEquals("held", pushSteamToken(handle, steamToken(), js("({proxy: null})")).await().type)
        assertEquals(false, trackerState(handle).steamProxyUnreachable, "null dropped the proxy, and nothing is unreachable")
    }
}
