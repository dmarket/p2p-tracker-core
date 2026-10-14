package com.dmarket.p2p.tracker.runtime

import com.dmarket.p2p.tracker.client.marketplace.TradingApiSignature
import com.dmarket.p2p.tracker.client.marketplace.platformEd25519Signer
import com.dmarket.p2p.tracker.credential.marketplace.KeyChange
import com.dmarket.p2p.tracker.credential.marketplace.TradingKeyPushResult
import com.dmarket.p2p.tracker.credential.steam.SteamPushResult
import com.dmarket.p2p.tracker.model.LifecycleEvent
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.marketplace.TradingApiKey
import com.dmarket.p2p.tracker.port.host.DeviceKeyValueStore
import com.dmarket.p2p.tracker.port.host.EventObserver
import com.dmarket.p2p.tracker.port.marketplace.Ed25519Signer
import com.dmarket.p2p.tracker.support.FakeClock
import com.dmarket.p2p.tracker.support.steamAccessToken
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * The composed headless tracker against a scripted gateway and Steam. Cycles are driven by hand: the tracker's own
 * scope runs on a dispatcher nothing advances, so no background wake or push nudge can race an assertion.
 */
class HeadlessTrackerTest {
    private val clock = FakeClock()
    private val token = steamAccessToken(STEAM_ID, clock.now() + 24.hours)
    private val requests = mutableListOf<HttpRequestData>()
    private val events = mutableListOf<LifecycleEvent>()
    private var heartbeatBody = """{"activeTracking":[],"directives":[],"ttlSeconds":100,"linkedSteamId":"$STEAM_ID"}"""

    private class RecordingStore : DeviceKeyValueStore {
        val entries = mutableMapOf<String, String>()

        override suspend fun get(key: String): String? = entries[key]

        override suspend fun set(key: String, value: String) {
            entries[key] = value
        }

        override suspend fun remove(key: String) {
            entries.remove(key)
        }
    }

    private val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private val engine = MockEngine { request ->
        requests += request
        val path = request.url.encodedPath
        when {
            path.endsWith("/p2p/ext/heartbeat") -> respond(heartbeatBody, headers = json)
            path.endsWith("/p2p/ext/trade-actions") -> respond("""{"results":[{"directiveId":"dir-1","accepted":true}]}""", headers = json)
            path.endsWith("/p2p/ext/trade-events") -> respond("""{"results":[]}""", headers = json)
            path.endsWith("/tradeoffer/new/send") -> respond("""{"tradeofferid":"555","needs_mobile_confirmation":true}""", headers = json)
            path.contains("GetTradeOffers") -> respond("""{"response":{"trade_offers_sent":[]}}""", headers = json)
            else -> respond("""{"response":{}}""", headers = json)
        }
    }

    private val idle = CoroutineScope(StandardTestDispatcher(TestCoroutineScheduler()))

    /** Signs nothing: returns the message bytes, so a test can read what was signed off the header. */
    private val echoSigner = Ed25519Signer { message -> message }

    private fun tracker(
        storage: DeviceKeyValueStore = RecordingStore(),
        signerFor: suspend (TradingApiKey) -> Ed25519Signer = { echoSigner },
    ) = HeadlessTracker(
        platform = HeadlessPlatform(storage, clock, EventObserver { events += it }, engine, signerFor),
        scope = idle,
        baseUrl = "https://gateway.test",
    )

    private fun heartbeats() = requests.filter { it.url.encodedPath.endsWith("/p2p/ext/heartbeat") }

    private fun steamRequests() = requests.filter { "steam" in it.url.host }

    private fun HttpRequestData.text(): String = when (val content = body) {
        is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
        else -> ""
    }

    private fun HttpRequestData.deviceId(): String = Json.parseToJsonElement(text()).jsonObject.getValue("deviceId").jsonPrimitive.content

    // ---- pushes ------------------------------------------------------------------------------------

    @Test
    fun a_key_push_says_whether_the_key_changed() = runTest {
        val tracker = tracker()
        assertEquals(TradingKeyPushResult.Held(PUBLIC_KEY, KeyChange.FIRST), tracker.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED))
        assertEquals(TradingKeyPushResult.Held(PUBLIC_KEY, KeyChange.SAME), tracker.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED + PUBLIC_KEY))
        assertEquals(
            TradingKeyPushResult.Held(OTHER_PUBLIC_KEY, KeyChange.REPLACED),
            tracker.pushTradingApiKey(OTHER_PUBLIC_KEY, SECRET_SEED),
        )
        assertTrue(tracker.state().hasTradingApiKey)
    }

    @Test
    fun a_malformed_pair_is_refused_and_the_held_key_stays() = runTest {
        val tracker = tracker()
        tracker.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED)

        val refused = assertIs<TradingKeyPushResult.Invalid>(tracker.pushTradingApiKey(PUBLIC_KEY, "not-hex"))
        assertTrue("not-hex" !in refused.toString(), "$refused")
        assertEquals(TradingKeyPushResult.Held(PUBLIC_KEY, KeyChange.SAME), tracker.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED))
    }

    @Test
    fun a_key_whose_seed_belongs_to_another_key_is_refused_without_quoting_it() = runTest {
        val tracker = tracker(signerFor = ::platformEd25519Signer)
        val otherSeed = "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb"

        val refused = assertIs<TradingKeyPushResult.Invalid>(tracker.pushTradingApiKey(PUBLIC_KEY, otherSeed))
        assertTrue(otherSeed !in refused.toString(), "$refused")
        assertEquals(false, tracker.state().hasTradingApiKey, "a refused key is not held")
        assertIs<TradingKeyPushResult.Held>(tracker.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED), "the platform signer takes the real pair")
    }

    @Test
    fun a_held_token_is_reported_by_account_and_expiry_only() = runTest {
        val tracker = tracker()

        assertEquals(SteamPushResult.Held(SteamId(STEAM_ID), clock.now() + 24.hours), tracker.pushSteamToken(token))
        val state = tracker.state()
        assertEquals(SteamId(STEAM_ID), state.steamTokenSteamId)
        assertEquals(clock.now() + 24.hours, state.steamTokenExpiresAt)
        assertTrue(token.substringAfter('.') !in state.toString(), "$state")
    }

    // ---- the cycle ---------------------------------------------------------------------------------

    @Test
    fun no_heartbeat_until_both_the_key_and_the_token_are_pushed() = runTest {
        val tracker = tracker()
        tracker.cycle()
        assertTrue(requests.isEmpty(), "nothing is sent before a push")

        tracker.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED + PUBLIC_KEY)
        tracker.cycle()
        assertTrue(requests.isEmpty(), "a key alone does not heartbeat: there is no Steam account to report")
        assertNull(tracker.state().readySince)

        tracker.pushSteamToken(token)
        tracker.cycle()
        val heartbeat = heartbeats().single()
        assertEquals(PUBLIC_KEY, heartbeat.headers[TradingApiSignature.API_KEY])
        assertTrue(heartbeat.headers[TradingApiSignature.REQUEST_SIGN].orEmpty().startsWith("dmar ed25519 "))
        assertEquals(null, heartbeat.headers[HttpHeaders.Authorization], "no session token rides an API-key call")
        val body = Json.parseToJsonElement(heartbeat.text()).jsonObject
        assertEquals("sdk", body.getValue("platform").jsonPrimitive.content)
        assertEquals(STEAM_ID, body.getValue("steamId").jsonPrimitive.content)
        assertEquals(clock.now(), tracker.state().readySince)
        assertEquals(SteamId(STEAM_ID), tracker.state().linkedSteamId)
    }

    @Test
    fun an_expired_token_stops_the_tracker_until_a_fresh_one_is_pushed() = runTest {
        val tracker = tracker()
        tracker.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED)
        tracker.pushSteamToken(steamAccessToken(STEAM_ID, clock.now() + 1.hours))
        tracker.cycle()
        assertEquals(1, heartbeats().size)

        clock.advance(61.minutes)
        tracker.loop.forceHeartbeatNow()
        tracker.cycle()
        assertEquals(1, heartbeats().size, "no heartbeat on an expired token")
        assertNull(tracker.state().readySince)
        assertEquals(clock.now() - 1.minutes, tracker.state().steamTokenExpiresAt, "the expired token stays held")
    }

    @Test
    fun without_a_linked_account_the_tracker_heartbeats_but_takes_no_steam_action() = runTest {
        heartbeatBody = """{"activeTracking":[{"dealId":"deal-1","steamOfferId":"555","watch":["GetTradeOffer"]}],"ttlSeconds":100}"""
        val tracker = tracker()
        tracker.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED)
        tracker.pushSteamToken(token)
        tracker.cycle()

        assertEquals(1, heartbeats().size)
        assertTrue(steamRequests().isEmpty(), "no Steam request while DMarket names no linked account")
        assertTrue(events.any { it is LifecycleEvent.LinkedSteamIdUnknown }, "$events")
    }

    @Test
    fun a_create_directive_reaches_steam_with_the_pushed_session_and_is_reported() = runTest {
        heartbeatBody = """
            {"activeTracking":[{"dealId":"deal-1","watch":["GetTradeOffer"],"role":"seller"}],
             "directives":[{"directiveId":"dir-1","action":"create_offer","dealId":"deal-1",
               "partnerSteamId":"$OTHER_STEAM_ID","assetIds":["asset-7"],"tradeToken":"tok","contextId":2}],
             "ttlSeconds":100,"linkedSteamId":"$STEAM_ID"}
        """.trimIndent()
        val tracker = tracker()
        tracker.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED)
        tracker.pushSteamToken(token)
        tracker.cycle()

        val create = requests.single { it.url.encodedPath.endsWith("/tradeoffer/new/send") }
        val cookie = create.headers[HttpHeaders.Cookie].orEmpty()
        val sessionId = cookie.substringAfter("; sessionid=", missingDelimiterValue = "")
        assertEquals("steamLoginSecure=$STEAM_ID%7C%7C$token; sessionid=$sessionId", cookie)
        assertTrue(Regex("^[0-9a-f]{24}$").matches(sessionId), sessionId)
        assertTrue("sessionid=$sessionId" in create.text(), "the form carries the cookie's sessionid: ${create.text()}")
        assertEquals("https://steamcommunity.com", create.headers[HttpHeaders.Origin])
        val report = requests.single { it.url.encodedPath.endsWith("/p2p/ext/trade-actions") }
        assertTrue(""""status":"needs_confirmation"""" in report.text() && """"steamOfferId":"555"""" in report.text(), report.text())
        assertTrue(report.headers[TradingApiSignature.REQUEST_SIGN] != null, "the report is signed too")
    }

    @Test
    fun a_report_batch_is_split_under_the_headless_body_cap() = runTest {
        // A hundred refused directives answer in one /trade-actions batch, well past 7 KiB on the wire.
        val directives = (1..100).joinToString(",") { """{"directiveId":"dir-$it","action":"frobnicate_offer","dealId":"deal-$it"}""" }
        heartbeatBody = """{"activeTracking":[],"directives":[$directives],"ttlSeconds":100,"linkedSteamId":"$STEAM_ID"}"""
        val tracker = tracker()
        tracker.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED)
        tracker.pushSteamToken(token)
        tracker.cycle()

        val reports = requests.filter { it.url.encodedPath.endsWith("/p2p/ext/trade-actions") }
        assertTrue(reports.size > 1, "split into ${reports.size} request(s)")
        reports.forEach {
            assertTrue(it.text().encodeToByteArray().size <= HeadlessTracker.MAX_REPORT_BODY_BYTES, "${it.text().length} bytes")
        }
        assertEquals(100, reports.sumOf { Json.parseToJsonElement(it.text()).jsonObject.getValue("reports").jsonArray.size })
    }

    @Test
    fun once_dmarket_names_the_account_a_token_for_another_is_refused_and_the_held_one_stays() = runTest {
        val tracker = tracker()
        tracker.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED)
        tracker.pushSteamToken(token)
        tracker.cycle()

        val refused =
            assertIs<SteamPushResult.WrongAccount>(tracker.pushSteamToken(steamAccessToken(OTHER_STEAM_ID, clock.now() + 24.hours)))
        assertEquals(SteamPushResult.WrongAccount(SteamId(OTHER_STEAM_ID), SteamId(STEAM_ID)), refused)
        assertEquals(SteamId(STEAM_ID), tracker.state().steamTokenSteamId)
    }

    @Test
    fun a_replaced_key_unbinds_the_account_until_its_own_heartbeat_comes_back() = runTest {
        val tracker = tracker()
        tracker.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED)
        tracker.pushSteamToken(token)
        tracker.cycle()

        val theirs = steamAccessToken(OTHER_STEAM_ID, clock.now() + 24.hours)
        assertEquals(SteamPushResult.WrongAccount(SteamId(OTHER_STEAM_ID), SteamId(STEAM_ID)), tracker.pushSteamToken(theirs))

        tracker.pushTradingApiKey(OTHER_PUBLIC_KEY, SECRET_SEED)
        assertEquals(
            SteamPushResult.WrongAccount(SteamId(OTHER_STEAM_ID), null),
            tracker.pushSteamToken(theirs),
            "the previous key's linked account no longer counts; the held token's account still does",
        )
    }

    @Test
    fun no_secret_reaches_storage_or_the_events() = runTest {
        val storage = RecordingStore()
        val tracker = tracker(storage)
        tracker.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED + PUBLIC_KEY)
        tracker.pushSteamToken(token)
        tracker.cycle()
        tracker.pushSteamToken(steamAccessToken(OTHER_STEAM_ID, clock.now() + 24.hours))

        val payload = token.substringAfter('.').substringBefore('.')
        val everything = storage.entries.values + events.map { it.toString() } + tracker.state().toString()
        for (text in everything) {
            assertTrue(SECRET_SEED !in text, "the secret key leaked: $text")
            assertTrue(payload !in text, "the Steam token leaked: $text")
        }
        assertTrue(storage.entries.isNotEmpty(), "the loop's own state is persisted")
        assertTrue(events.any { it is LifecycleEvent.HeartbeatSent }, "the events were observed: $events")
    }

    @Test
    fun a_restart_holds_neither_secret_and_keeps_the_device_id() = runTest {
        val storage = RecordingStore()
        val first = tracker(storage)
        first.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED)
        first.pushSteamToken(token)
        first.cycle()
        val deviceId = heartbeats().single().deviceId()

        val restarted = tracker(storage)
        restarted.cycle()
        assertEquals(1, heartbeats().size, "a restarted tracker does not heartbeat before the pushes")
        val state = restarted.state()
        assertEquals(false, state.hasTradingApiKey)
        assertNull(state.steamTokenExpiresAt)

        restarted.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED)
        restarted.pushSteamToken(token)
        restarted.loop.forceHeartbeatNow()
        restarted.cycle()
        assertEquals(deviceId, heartbeats().last().deviceId(), "the directive lease key survives the restart")
    }

    @Test
    fun a_session_id_is_twelve_random_bytes_in_hex() {
        val ids = List(8) { newSteamSessionId() }
        ids.forEach { assertTrue(Regex("^[0-9a-f]{24}$").matches(it), it) }
        assertEquals(ids.size, ids.toSet().size, "$ids")
    }

    private companion object {
        const val STEAM_ID = "76561198000000001"
        const val OTHER_STEAM_ID = "76561198000000099"
        const val PUBLIC_KEY = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
        const val OTHER_PUBLIC_KEY = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"
        const val SECRET_SEED = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"
    }
}
