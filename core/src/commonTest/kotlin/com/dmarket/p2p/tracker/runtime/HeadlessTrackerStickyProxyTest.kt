package com.dmarket.p2p.tracker.runtime

import com.dmarket.p2p.tracker.client.SteamProxyUnreachableException
import com.dmarket.p2p.tracker.credential.steam.SteamProxyRefusal
import com.dmarket.p2p.tracker.credential.steam.SteamProxyUpdate
import com.dmarket.p2p.tracker.credential.steam.SteamPushResult
import com.dmarket.p2p.tracker.model.LifecycleEvent
import com.dmarket.p2p.tracker.net.STEAM_PROXY_MISSING_DETAIL
import com.dmarket.p2p.tracker.net.SteamProxy
import com.dmarket.p2p.tracker.port.host.DeviceKeyValueStore
import com.dmarket.p2p.tracker.port.host.EventObserver
import com.dmarket.p2p.tracker.port.marketplace.Ed25519Signer
import com.dmarket.p2p.tracker.support.FakeClock
import com.dmarket.p2p.tracker.support.steamAccessToken
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * The Steam proxy is optional, but sticky once set: a tracker pins itself on the first proxy it holds, and one made with
 * `requireSteamProxy` (a host's restart of an account that uses one) starts pinned. Against a Steam engine that honours
 * [SteamRoute.proxyRequired] the way the platform's does: with no proxy held it sends nothing and fails coded as the
 * proxy's, never directly.
 */
class HeadlessTrackerStickyProxyTest {
    private val clock = FakeClock()
    private val token = steamAccessToken(STEAM_ID, clock.now() + 24.hours)
    private val events = mutableListOf<LifecycleEvent>()
    private val steamSent = mutableListOf<Pair<HttpRequestData, SteamProxy?>>()
    private val proxy = SteamProxy.parse("http://trader:s3cret-pass@203.0.113.7:3128")
    private val otherProxy = SteamProxy.parse("http://trader:other-pass@203.0.113.8:3128")

    /** A proxy the engine cannot get through, as one that is down. */
    private var proxyDown: SteamProxy? = null

    private val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private val heartbeatBody = """
        {"activeTracking":[{"dealId":"deal-1","watch":["GetTradeOffer"],"role":"seller"}],
         "directives":[{"directiveId":"dir-1","action":"create_offer","dealId":"deal-1",
           "partnerSteamId":"$OTHER_STEAM_ID","assetIds":["asset-7"],"tradeToken":"tok","contextId":2}],
         "ttlSeconds":100,"linkedSteamId":"$STEAM_ID"}
    """.trimIndent()

    private val dmarketEngine = MockEngine { request ->
        when {
            request.url.encodedPath.endsWith("/p2p/ext/heartbeat") -> respond(heartbeatBody, headers = json)
            else -> respond("""{"results":[]}""", headers = json)
        }
    }

    private fun steamEngine(route: SteamRoute): HttpClientEngine = MockEngine { request ->
        val through = route.proxy
        if (through == null && route.proxyRequired) {
            throw SteamProxyUnreachableException(request.method.value, request.url.toString(), STEAM_PROXY_MISSING_DETAIL)
        }
        if (through != null && through == proxyDown) {
            route.unreachable(through, "could not connect to the proxy: ECONNREFUSED")
            throw SteamProxyUnreachableException(
                request.method.value,
                request.url.toString(),
                "could not connect to the proxy: ECONNREFUSED",
            )
        }
        steamSent += request to through
        when {
            request.url.encodedPath.endsWith("/tradeoffer/new/send") ->
                respond("""{"tradeofferid":"555","needs_mobile_confirmation":true}""", headers = json)
            else -> respond("""{"response":{}}""", headers = json)
        }
    }

    private class Store : DeviceKeyValueStore {
        private val entries = mutableMapOf<String, String>()

        override suspend fun get(key: String): String? = entries[key]

        override suspend fun set(key: String, value: String) {
            entries[key] = value
        }

        override suspend fun remove(key: String) {
            entries.remove(key)
        }
    }

    private fun platform(proxyCapable: Boolean = true): HeadlessPlatform {
        val store = Store()
        return HeadlessPlatform(
            storageFor = { store },
            clock = clock,
            eventObserver = EventObserver { events += it },
            httpEngine = dmarketEngine,
            signerFor = { Ed25519Signer { it } },
            steamHttpEngine = if (proxyCapable) ::steamEngine else null,
        )
    }

    private fun tracker(requireSteamProxy: Boolean = true): HeadlessTracker = HeadlessTracker(
        platform = platform(),
        scope = CoroutineScope(StandardTestDispatcher(TestCoroutineScheduler())),
        baseUrl = "https://gateway.test",
        requireSteamProxy = requireSteamProxy,
    )

    private fun HeadlessTracker.proxyState(): Pair<Boolean, Boolean> = state().let { it.steamProxyHeld to it.steamProxyRequired }

    private inline fun <reified T : LifecycleEvent> events(): List<T> = events.filterIsInstance<T>()

    @Test
    fun it_cannot_be_made_on_a_platform_whose_steam_engine_ignores_the_proxy() {
        assertFailsWith<IllegalArgumentException> {
            HeadlessTracker(
                platform = platform(proxyCapable = false),
                scope = CoroutineScope(StandardTestDispatcher(TestCoroutineScheduler())),
                requireSteamProxy = true,
            )
        }
    }

    @Test
    fun restarted_with_the_option_it_holds_steam_work_until_a_proxy_is_pushed_refusing_a_token_without_one() = runTest {
        val tracker = tracker()
        assertEquals(false to true, tracker.proxyState(), "a fresh start: none held, one required")
        assertTrue(tracker.state().steamProxyMissing)
        tracker.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED)

        for (update in listOf(SteamProxyUpdate.Keep, SteamProxyUpdate.Clear)) {
            val refused = tracker.pushSteamToken(token, update)
            assertEquals(SteamPushResult.ProxyRefused(SteamProxyRefusal.REQUIRED), refused, "$update")
            assertEquals("STEAM_PROXY_REQUIRED", (refused as SteamPushResult.ProxyRefused).refusal.code)
        }
        assertEquals(null, tracker.state().steamTokenSteamId, "token-only first push: no token is held without a proxy")
        val missing = events<LifecycleEvent.SteamProxyMissing>().single()
        assertEquals(STEAM_PROXY_MISSING_DETAIL, missing.detail)

        tracker.cycle()
        assertTrue(steamSent.isEmpty(), "nothing went to Steam, and nothing went direct")
        assertEquals(1, events<LifecycleEvent.SteamProxyMissing>().size, "said once, not every push or cycle")

        assertIs<SteamPushResult.Held>(tracker.pushSteamToken(token, SteamProxyUpdate.Use(proxy)))
        assertEquals(true to true, tracker.proxyState())
        assertFalse(tracker.state().steamProxyMissing)
        assertEquals(1, events<LifecycleEvent.SteamProxyRecovered>().size)
        tracker.cycle()
        assertTrue(steamSent.isNotEmpty())
        assertTrue(steamSent.all { (_, through) -> through == proxy }, "every Steam call went through the proxy")
        for (text in events.map { it.toString() } + tracker.state().toString()) {
            for (part in listOf("s3cret", "trader", "203.0.113.7")) assertFalse(part in text, text)
        }
    }

    @Test
    fun with_the_option_a_held_proxy_is_locked_renewals_keep_it_and_nothing_is_missing() = runTest {
        val tracker = tracker()
        tracker.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED)
        assertIs<SteamPushResult.Held>(tracker.pushSteamToken(token, SteamProxyUpdate.Use(proxy)))

        assertEquals(SteamPushResult.ProxyRefused(SteamProxyRefusal.LOCKED), tracker.pushSteamToken(token, SteamProxyUpdate.Clear))
        assertEquals(proxy, tracker.steamRoute.proxy, "the proxy stays")
        assertIs<SteamPushResult.Held>(tracker.pushSteamToken(token), "a renewal keeps the proxy")
        assertEquals(proxy, tracker.steamRoute.proxy)

        tracker.cycle()
        assertTrue(steamSent.isNotEmpty() && steamSent.all { (_, through) -> through == proxy })
        assertFalse(tracker.state().steamProxyMissing)
        assertTrue(events<LifecycleEvent.SteamProxyMissing>().isEmpty(), "a proxy was held before any cycle: nothing is missing")
    }

    @Test
    fun without_the_option_the_first_held_proxy_pins_it_null_is_locked_and_another_proxy_may_replace_it() = runTest {
        val tracker = tracker(requireSteamProxy = false)
        tracker.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED)
        assertIs<SteamPushResult.Held>(tracker.pushSteamToken(token), "optional: a token without a proxy is held")
        assertEquals(false to false, tracker.proxyState())
        var moves = 0
        tracker.steamRoute.onProxyMoved { moves++ }

        assertIs<SteamPushResult.Held>(tracker.pushSteamToken(token, SteamProxyUpdate.Use(proxy)))
        assertEquals(true to true, tracker.proxyState(), "set, and pinned from now on")

        assertEquals(SteamPushResult.ProxyRefused(SteamProxyRefusal.LOCKED), tracker.pushSteamToken(token, SteamProxyUpdate.Clear))
        assertEquals(proxy, tracker.steamRoute.proxy, "null after set changed nothing")
        assertIs<SteamPushResult.Held>(tracker.pushSteamToken(token), "token-only keeps it")
        assertEquals(proxy, tracker.steamRoute.proxy)
        tracker.cycle()
        assertTrue(steamSent.isNotEmpty() && steamSent.all { (_, through) -> through == proxy })

        val moved = assertIs<SteamPushResult.Held>(tracker.pushSteamToken(token, SteamProxyUpdate.Use(otherProxy)))
        assertTrue(moved.proxyChanged, "replacing the held proxy with another is allowed")
        assertEquals(otherProxy, tracker.steamRoute.proxy)
        assertTrue(events<LifecycleEvent.SteamProxyMissing>().isEmpty(), "pinned never means missing: a proxy is always held")
        assertEquals(2, moves, "the transports heard the first proxy and the replacement, not the renewal or the refused null")
    }

    @Test
    fun a_pinned_proxy_that_is_down_holds_the_steam_work_and_nothing_falls_back_to_a_direct_call() = runTest {
        val tracker = tracker(requireSteamProxy = false)
        tracker.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED)
        assertIs<SteamPushResult.Held>(tracker.pushSteamToken(token, SteamProxyUpdate.Use(proxy)))
        proxyDown = proxy

        tracker.cycle()
        tracker.cycle()

        assertTrue(steamSent.none { (_, through) -> through == null }, "no direct call while the proxy is down: $steamSent")
        assertTrue(tracker.state().steamProxyUnreachable)
        assertTrue(events<LifecycleEvent.SteamProxyUnreachable>().isNotEmpty())
        assertEquals(
            SteamPushResult.ProxyRefused(SteamProxyRefusal.LOCKED),
            tracker.pushSteamToken(token, SteamProxyUpdate.Clear),
            "a dead proxy is no way out: null is still refused",
        )
    }

    @Test
    fun without_the_option_and_never_a_proxy_steam_goes_out_directly_as_before() = runTest {
        val tracker = tracker(requireSteamProxy = false)
        tracker.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED)
        assertIs<SteamPushResult.Held>(tracker.pushSteamToken(token, SteamProxyUpdate.Clear))
        tracker.cycle()

        assertTrue(steamSent.isNotEmpty() && steamSent.all { (_, through) -> through == null })
        assertEquals(false to false, tracker.proxyState())
        assertTrue(events<LifecycleEvent.SteamProxyMissing>().isEmpty())
    }

    private companion object {
        const val STEAM_ID = "76561198000000001"
        const val OTHER_STEAM_ID = "76561198000000099"
        const val PUBLIC_KEY = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
        const val SECRET_SEED = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"
    }
}
