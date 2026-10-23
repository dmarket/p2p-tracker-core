package com.dmarket.p2p.headless

import com.dmarket.p2p.headless.node.tempDir
import com.dmarket.p2p.tracker.client.marketplace.TradingApiSignature
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.time.Duration
import kotlin.time.Instant

/** A scope nothing ever advances: the tracker's own work (the driver, a push's cycle) never runs on it. */
fun idleScope(): CoroutineScope = CoroutineScope(StandardTestDispatcher(TestCoroutineScheduler()))

/**
 * DMarket and Steam behind one mock engine. Records every request as `METHOD path`; DMarket heartbeats answer
 * [heartbeatBody] (or, for a heartbeat signed with [OTHER_PUBLIC_KEY], [otherKeyHeartbeat]), a cancel answers that it
 * landed, a create that it needs confirmation, an offer read [offerBody] when set, a proof that it verified, anything
 * else an empty Steam response.
 */
class FakeBackend {
    var heartbeatBody = """{"activeTracking":[],"directives":[],"ttlSeconds":100,"linkedSteamId":"$STEAM_ID"}"""
    var heartbeatStatus = HttpStatusCode.OK
    var otherKeyHeartbeat: String? = null
    var otherKeyStatus = HttpStatusCode.OK
    var offerBody: String? = null
    val requested = mutableListOf<String>()

    val engine = MockEngine { request ->
        val path = request.url.encodedPath
        requested += "${request.method.value} $path"
        val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        val heartbeat = path.endsWith("/p2p/ext/heartbeat")
        when {
            heartbeat && request.headers[TradingApiSignature.API_KEY] == OTHER_PUBLIC_KEY ->
                respond(otherKeyHeartbeat ?: heartbeatBody, otherKeyStatus, json)
            heartbeat -> respond(heartbeatBody, heartbeatStatus, json)
            path.endsWith("/p2p/ext/notary") -> respond("""{"dealId":"deal-1","verified":true}""", headers = json)
            path.endsWith("/IEconService/GetTradeOffer/v1/") && offerBody != null -> respond(offerBody!!, headers = json)
            path.endsWith("/cancel") -> respond("""{"tradeofferid":"555"}""", headers = json)
            path.endsWith("/tradeoffer/new/send") -> respond("""{"tradeofferid":"777","needs_mobile_confirmation":true}""", headers = json)
            else -> respond("""{"response":{}}""", headers = json)
        }
    }

    fun steamRequests(): List<String> = requested.filter { "/tradeoffer/" in it }
}

/** The options a host would pass, on a test clock; every event lands in [events]. */
class TestHost(var now: Instant = T0) {
    val events = mutableListOf<String>()
    val backend = FakeBackend()

    fun options(dataDir: String, steamWrites: Boolean? = null): dynamic {
        val o: dynamic = js("({})")
        o.dataDir = dataDir
        o.baseUrl = "https://gateway.test"
        if (steamWrites != null) o.steamWrites = steamWrites
        o.onEvent = { json: String -> events += json }
        o.nowMs = { now.toEpochMilliseconds().toDouble() }
        return o
    }

    /** A tracker on [backend] whose own background work never runs: each cycle is the test's. */
    suspend fun tracker(steamWrites: Boolean? = null): Any =
        createHeadlessTrackerForTest(options(tempDir(), steamWrites), backend.engine, idleScope())

    fun advance(by: Duration) {
        now += by
    }
}

/** `typeof`, with `null` and arrays told apart: what a TS type of the value would say. */
fun kindOf(value: dynamic): String = when {
    value === null -> "null"
    value === undefined -> "undefined"
    js("Array.isArray")(value) as Boolean -> "array"
    else -> jsTypeOf(value)
}

/** Each own key of [o], sorted, with [kindOf] its value. */
fun shapeOf(o: dynamic): Map<String, String> = (js("Object.keys")(o) as Array<String>).sorted().associateWith { kindOf(o[it]) }
