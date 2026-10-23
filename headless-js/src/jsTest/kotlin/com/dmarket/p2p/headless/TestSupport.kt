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
import kotlin.io.encoding.Base64
import kotlin.time.Duration
import kotlin.time.Instant

const val STEAM_ID = "76561198000000001"
const val OTHER_STEAM_ID = "76561198000000099"

// RFC 8032 TEST 1 and TEST 2: real Ed25519 pairs, so the platform's own signer accepts both.
const val PUBLIC_KEY = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
const val SECRET_SEED = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"
const val OTHER_PUBLIC_KEY = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"
const val OTHER_SECRET_SEED = "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb"

/** The clock every test starts on: 2026-06-16T12:00:00Z. */
val T0: Instant = Instant.fromEpochSeconds(1_781_611_200)

/** A Steam access token shaped like Steam's: a JWT whose payload carries [sub], [aud] and [exp]. Unsigned. */
fun steamToken(sub: String = STEAM_ID, aud: List<String> = listOf("web", "mobile"), exp: Instant = T0 + Duration.parse("24h")): String {
    val audience = aud.joinToString(",") { "\"$it\"" }
    val payload = """{"iss":"r:test","sub":"$sub","aud":[$audience],"exp":${exp.epochSeconds},"iat":${T0.epochSeconds}}"""
    val encode = { text: String -> Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(text.encodeToByteArray()) }
    return "${encode("""{"alg":"EdDSA","typ":"JWT"}""")}.${encode(payload)}.c2lnbmF0dXJl"
}

/** The middle part of a JWT: what would show if a token leaked whole or in part. */
fun String.jwtPayload(): String = substringAfter('.').substringBefore('.')

/** A scope nothing ever advances: the tracker's own work (the driver, a push's cycle) never runs on it. */
fun idleScope(): CoroutineScope = CoroutineScope(StandardTestDispatcher(TestCoroutineScheduler()))

/**
 * DMarket and Steam behind one mock engine. Records every request as `METHOD path`; DMarket heartbeats answer
 * [heartbeatBody] (or, for a heartbeat signed with [OTHER_PUBLIC_KEY], [otherKeyHeartbeat]), a cancel answers that it
 * landed, a create that it needs confirmation, anything else an empty Steam response.
 */
class FakeBackend {
    var heartbeatBody = """{"activeTracking":[],"directives":[],"ttlSeconds":100,"linkedSteamId":"$STEAM_ID"}"""
    var heartbeatStatus = HttpStatusCode.OK
    var otherKeyHeartbeat: String? = null
    var otherKeyStatus = HttpStatusCode.OK
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
