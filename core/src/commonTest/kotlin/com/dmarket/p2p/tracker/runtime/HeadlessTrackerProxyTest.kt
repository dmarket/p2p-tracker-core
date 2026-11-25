package com.dmarket.p2p.tracker.runtime

import com.dmarket.p2p.tracker.client.SteamProxyUnreachableException
import com.dmarket.p2p.tracker.credential.steam.SteamProxyUpdate
import com.dmarket.p2p.tracker.credential.steam.SteamPushResult
import com.dmarket.p2p.tracker.model.LifecycleEvent
import com.dmarket.p2p.tracker.net.STEAM_PROXY_UNREACHABLE_REASON
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
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * A Steam proxy pushed with the token, against a Steam engine that stands in for the platform's: it goes through
 * [SteamRoute.proxy] when the request goes out, and while the proxy is [down] it fails every request the way the
 * platform's does — the route told, a [SteamProxyUnreachableException] thrown, nothing sent.
 */
class HeadlessTrackerProxyTest {
    private val clock = FakeClock()
    private val token = steamAccessToken(STEAM_ID, clock.now() + 24.hours)
    private val events = mutableListOf<LifecycleEvent>()
    private val dmarket = mutableListOf<HttpRequestData>()
    private val steamSent = mutableListOf<Pair<HttpRequestData, SteamProxy?>>()
    private var down = false
    private var downDetail = "could not connect to the proxy: ECONNREFUSED"
    private val proxy = SteamProxy.parse("http://trader:s3cret-pass@203.0.113.7:3128")
    private val otherProxy = SteamProxy.parse("http://trader:other-pass@203.0.113.8:3128")

    private val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private var heartbeatBody = """
        {"activeTracking":[{"dealId":"deal-1","watch":["GetTradeOffer"],"role":"seller"}],
         "directives":[{"directiveId":"dir-1","action":"create_offer","dealId":"deal-1",
           "partnerSteamId":"$OTHER_STEAM_ID","assetIds":["asset-7"],"tradeToken":"tok","contextId":2}],
         "ttlSeconds":100,"linkedSteamId":"$STEAM_ID"}
    """.trimIndent()

    private val dmarketEngine = MockEngine { request ->
        dmarket += request
        when {
            request.url.encodedPath.endsWith("/p2p/ext/heartbeat") -> respond(heartbeatBody, headers = json)
            else -> respond("""{"results":[]}""", headers = json)
        }
    }

    private fun steamEngine(route: SteamRoute): HttpClientEngine = MockEngine { request ->
        val through = route.proxy
        if (through != null && down) {
            route.unreachable(through, downDetail)
            throw SteamProxyUnreachableException(request.method.value, request.url.toString(), downDetail)
        }
        through?.let(route::tunnelled)
        steamSent += request to through
        when {
            request.url.encodedPath.endsWith("/tradeoffer/new/send") ->
                respond("""{"tradeofferid":"555","needs_mobile_confirmation":true}""", headers = json)
            request.url.encodedPath.endsWith("/tradeoffer/555/cancel") -> respond("""{"tradeofferid":"555"}""", headers = json)
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

    private fun tracker(proxyCapable: Boolean = true): HeadlessTracker {
        val store = Store()
        return HeadlessTracker(
            platform = HeadlessPlatform(
                storageFor = { store },
                clock = clock,
                eventObserver = EventObserver { events += it },
                httpEngine = dmarketEngine,
                signerFor = { Ed25519Signer { it } },
                steamHttpEngine = if (proxyCapable) ::steamEngine else null,
            ),
            scope = CoroutineScope(StandardTestDispatcher(TestCoroutineScheduler())),
            baseUrl = "https://gateway.test",
        )
    }

    private fun reports() = dmarket.filter { it.url.encodedPath.endsWith("/p2p/ext/trade-actions") }

    private inline fun <reified T : LifecycleEvent> events(): List<T> = events.filterIsInstance<T>()

    @Test
    fun a_proxy_is_refused_where_steam_cannot_go_through_one_and_nothing_quotes_it() = runTest {
        val tracker = tracker(proxyCapable = false)
        val refused = assertIs<SteamPushResult.Invalid>(tracker.pushSteamToken(token, SteamProxyUpdate.Use(proxy)))
        for (part in listOf("s3cret", "trader", "203.0.113.7")) assertFalse(part in refused.toString(), "$refused")
        assertEquals(null, tracker.state().steamTokenSteamId, "the token is not held either")
        assertIs<SteamPushResult.Held>(tracker.pushSteamToken(token), "the same token without a proxy is")
    }

    @Test
    fun every_steam_call_goes_through_the_proxy_pushed_with_the_token_and_dmarket_never_does() = runTest {
        down = false
        val tracker = tracker()
        tracker.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED)
        assertIs<SteamPushResult.Held>(tracker.pushSteamToken(token, SteamProxyUpdate.Use(proxy)))
        tracker.cycle()

        assertTrue(steamSent.isNotEmpty())
        assertTrue(steamSent.all { (_, through) -> through == proxy }, "every Steam call went through the proxy")
        assertEquals(1, steamSent.count { (request, _) -> request.url.encodedPath.endsWith("/tradeoffer/new/send") })
        assertTrue(dmarket.none { "steam" in it.url.host } && dmarket.isNotEmpty(), "DMarket stays on its own engine")
        assertFalse(tracker.state().steamProxyUnreachable)
        assertTrue(events<LifecycleEvent.SteamProxyUnreachable>().isEmpty())
    }

    @Test
    fun an_unreachable_proxy_defers_the_create_says_so_once_with_its_deals_and_recovers() = runTest {
        down = true
        val tracker = tracker()
        tracker.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED)
        tracker.pushSteamToken(token, SteamProxyUpdate.Use(proxy))
        tracker.cycle()

        assertTrue(steamSent.isEmpty(), "nothing went out, through the proxy or around it")
        assertTrue(reports().isEmpty(), "the create that was not sent is not reported failed")
        val deferred = events<LifecycleEvent.SteamWriteDeferred>().single()
        assertEquals(
            listOf("dir-1", "deal-1", STEAM_PROXY_UNREACHABLE_REASON),
            listOf(deferred.directiveId, deferred.dealId, deferred.reason),
        )
        val unreachable = events<LifecycleEvent.SteamProxyUnreachable>().single()
        assertEquals(listOf("deal-1"), unreachable.dealIds)
        assertEquals("could not connect to the proxy: ECONNREFUSED", unreachable.detail)
        val state = tracker.state()
        assertTrue(state.steamProxyUnreachable)
        assertEquals(clock.now(), assertNotNull(state.steamProxyUnreachableSince))
        for (text in events.map { it.toString() } + state.toString()) {
            for (part in listOf("s3cret", "trader", "203.0.113.7")) assertFalse(part in text, text)
        }

        clock.advance(2.minutes)
        tracker.loop.forceHeartbeatNow()
        tracker.cycle()
        assertEquals(1, events<LifecycleEvent.SteamProxyUnreachable>().size, "said once, not every cycle")
        assertTrue(reports().isEmpty())

        down = false
        clock.advance(2.minutes)
        tracker.loop.forceHeartbeatNow()
        tracker.cycle()
        assertEquals(
            1,
            steamSent.count { (request, _) ->
                request.url.encodedPath.endsWith("/tradeoffer/new/send")
            },
            "the re-leased create runs",
        )
        assertEquals(1, events<LifecycleEvent.SteamProxyRecovered>().size)
        assertFalse(tracker.state().steamProxyUnreachable)
        assertTrue(reports().none { "\"failed\"" in (it.body as OutgoingContent.ByteArrayContent).bytes().decodeToString() })
    }

    @Test
    fun a_cancel_the_proxy_kept_from_going_out_is_deferred_unread_and_unreported_and_runs_once_it_is_back() = runTest {
        heartbeatBody = """
            {"activeTracking":[{"dealId":"deal-1","watch":["GetTradeOffer"],"role":"seller"}],
             "directives":[{"directiveId":"dir-c","action":"cancel_offer","dealId":"deal-1","steamOfferId":"555"}],
             "ttlSeconds":100,"linkedSteamId":"$STEAM_ID"}
        """.trimIndent()
        down = true
        val tracker = tracker()
        tracker.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED)
        tracker.pushSteamToken(token, SteamProxyUpdate.Use(proxy))
        tracker.cycle()

        assertTrue(steamSent.isEmpty())
        assertTrue(reports().isEmpty(), "a cancel that never reached Steam is not reported failed")
        val deferred = events<LifecycleEvent.SteamWriteDeferred>().single()
        assertEquals(
            listOf("cancel_offer", "dir-c", "deal-1", STEAM_PROXY_UNREACHABLE_REASON),
            listOf(deferred.kind, deferred.directiveId, deferred.dealId, deferred.reason),
        )
        assertTrue(events<LifecycleEvent.DirectiveExecuted>().isEmpty(), "no outcome at all")
        assertTrue(events<LifecycleEvent.DirectiveDropped>().isEmpty(), "not dropped as a cancel Steam may have taken")
        assertTrue(events<LifecycleEvent.FailedCancelChecked>().isEmpty(), "its offer is not read through the dead proxy")
        assertEquals(listOf("deal-1"), events<LifecycleEvent.SteamProxyUnreachable>().single().dealIds)

        down = false
        clock.advance(2.minutes)
        tracker.loop.forceHeartbeatNow()
        tracker.cycle()
        assertEquals(1, steamSent.count { (request, _) -> request.url.encodedPath.endsWith("/tradeoffer/555/cancel") })
        val executed = events<LifecycleEvent.DirectiveExecuted>().single()
        assertEquals(listOf("cancel_offer", "SUCCESS"), listOf(executed.kind, executed.status))
        assertTrue(reports().none { "\"failed\"" in (it.body as OutgoingContent.ByteArrayContent).bytes().decodeToString() })
    }

    @Test
    fun pushing_another_proxy_ends_the_problem_with_the_old_one() = runTest {
        down = true
        val tracker = tracker()
        tracker.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED)
        tracker.pushSteamToken(token, SteamProxyUpdate.Use(proxy))
        tracker.cycle()
        assertTrue(tracker.state().steamProxyUnreachable)

        tracker.pushSteamToken(token, SteamProxyUpdate.Use(otherProxy))
        assertFalse(tracker.state().steamProxyUnreachable, "the problem was the old proxy's")
        assertEquals(otherProxy, tracker.steamRoute.proxy)
        down = false
        tracker.cycle()
        assertEquals(1, events<LifecycleEvent.SteamProxyRecovered>().size)
        assertTrue(steamSent.all { (_, through) -> through == otherProxy })
    }

    @Test
    fun another_unreachable_proxy_is_told_as_its_own_outage_once_and_the_same_one_again_is_not() = runTest {
        down = true
        val tracker = tracker()
        tracker.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED)
        tracker.pushSteamToken(token, SteamProxyUpdate.Use(proxy))
        tracker.cycle()

        // The clock stands still: the second outage starts at the same instant as the first.
        downDetail = "the proxy answered CONNECT with HTTP 407"
        tracker.pushSteamToken(token, SteamProxyUpdate.Use(otherProxy))
        tracker.loop.forceHeartbeatNow()
        tracker.cycle()
        assertEquals(
            listOf("could not connect to the proxy: ECONNREFUSED", "the proxy answered CONNECT with HTTP 407"),
            events<LifecycleEvent.SteamProxyUnreachable>().map { it.detail },
        )
        assertTrue(events<LifecycleEvent.SteamProxyRecovered>().isEmpty(), "neither proxy got through")

        tracker.pushSteamToken(token, SteamProxyUpdate.Use(SteamProxy.parse("http://trader:other-pass@203.0.113.8:3128")))
        tracker.loop.forceHeartbeatNow()
        tracker.cycle()
        tracker.cycle()
        assertEquals(2, events<LifecycleEvent.SteamProxyUnreachable>().size, "the same proxy named again is the same outage")
        assertTrue(events<LifecycleEvent.SteamProxyRecovered>().isEmpty())
    }

    @Test
    fun a_refused_token_changes_neither_the_token_nor_the_proxy() = runTest {
        val tracker = tracker()
        tracker.pushSteamToken(token, SteamProxyUpdate.Use(proxy))
        val wrong = steamAccessToken(OTHER_STEAM_ID, clock.now() + 24.hours)
        assertIs<SteamPushResult.WrongAccount>(tracker.pushSteamToken(wrong, SteamProxyUpdate.Use(otherProxy)))
        assertIs<SteamPushResult.WrongAccount>(tracker.pushSteamToken(wrong, SteamProxyUpdate.Clear))
        assertEquals(proxy, tracker.steamRoute.proxy)
    }

    @Test
    fun a_renewal_that_names_no_proxy_keeps_the_held_one_and_only_an_explicit_clear_goes_direct() = runTest {
        val tracker = tracker()
        tracker.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED)
        tracker.pushSteamToken(token, SteamProxyUpdate.Use(proxy))

        val renewed = steamAccessToken(STEAM_ID, clock.now() + 48.hours)
        assertIs<SteamPushResult.Held>(tracker.pushSteamToken(renewed))
        assertEquals(proxy, tracker.steamRoute.proxy, "a renewal never moves the account back to the host's address")
        tracker.cycle()
        assertTrue(steamSent.isNotEmpty() && steamSent.all { (_, through) -> through == proxy }, "the renewed token still goes through it")

        assertIs<SteamPushResult.Held>(tracker.pushSteamToken(renewed, SteamProxyUpdate.Clear))
        assertEquals(null, tracker.steamRoute.proxy, "dropped on the host's explicit word only")
        // A fresh create, so the next cycle surely calls Steam.
        heartbeatBody = heartbeatBody.replace("dir-1", "dir-2").replace("deal-1", "deal-2").replace("asset-7", "asset-8")
        val before = steamSent.size
        clock.advance(2.minutes)
        tracker.loop.forceHeartbeatNow()
        tracker.cycle()
        val after = steamSent.drop(before)
        assertTrue(after.any { (request, _) -> request.url.encodedPath.endsWith("/tradeoffer/new/send") }, "$after")
        assertTrue(after.all { (_, through) -> through == null }, "with the proxy cleared, Steam is reached directly")
    }

    private companion object {
        const val STEAM_ID = "76561198000000001"
        const val OTHER_STEAM_ID = "76561198000000099"
        const val PUBLIC_KEY = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
        const val SECRET_SEED = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"
    }
}
