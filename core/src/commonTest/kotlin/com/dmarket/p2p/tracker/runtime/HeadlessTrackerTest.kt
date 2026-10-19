package com.dmarket.p2p.tracker.runtime

import com.dmarket.p2p.tracker.client.marketplace.TradingApiSignature
import com.dmarket.p2p.tracker.client.marketplace.platformEd25519Signer
import com.dmarket.p2p.tracker.credential.marketplace.KeyChange
import com.dmarket.p2p.tracker.credential.marketplace.TradingKeyPushResult
import com.dmarket.p2p.tracker.credential.steam.SteamPushResult
import com.dmarket.p2p.tracker.loop.STEAM_SESSION_MISSING_DROP_REASON
import com.dmarket.p2p.tracker.model.DealId
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
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.http.toHttpDate
import io.ktor.util.date.GMTDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

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

    /** What a heartbeat signed with [OTHER_PUBLIC_KEY] gets instead of [heartbeatBody]. */
    private var otherKeyHeartbeat: String? = null
    private var otherKeyStatus = HttpStatusCode.OK
    private var otherKeyUnreachable = false
    private var heartbeatStatus = HttpStatusCode.OK

    /** The `Date` every DMarket reply carries; `null` sends none. */
    private var serverDate: String? = null

    /** The `Retry-After` every DMarket reply carries; `null` sends none. */
    private var retryAfter: String? = null

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

    private val json get() = headersOf(
        *listOfNotNull(
            HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
            serverDate?.let { HttpHeaders.Date to listOf(it) },
            retryAfter?.let { HttpHeaders.RetryAfter to listOf(it) },
        ).toTypedArray(),
    )

    private val engine = MockEngine { request ->
        requests += request
        val path = request.url.encodedPath
        when {
            path.endsWith("/p2p/ext/heartbeat") && request.headers[TradingApiSignature.API_KEY] == OTHER_PUBLIC_KEY -> {
                if (otherKeyUnreachable) throw IllegalStateException("connection refused")
                respond(otherKeyHeartbeat ?: heartbeatBody, otherKeyStatus, json)
            }
            path.endsWith("/p2p/ext/heartbeat") -> respond(heartbeatBody, heartbeatStatus, json)
            path.endsWith("/p2p/ext/trade-actions") -> respond("""{"results":[{"directiveId":"dir-1","accepted":true}]}""", headers = json)
            path.endsWith("/p2p/ext/trade-events") -> respond("""{"results":[]}""", headers = json)
            path.endsWith("/tradeoffer/new/send") -> respond("""{"tradeofferid":"555","needs_mobile_confirmation":true}""", headers = json)
            path.contains("GetTradeOffers") -> respond("""{"response":{"trade_offers_sent":[]}}""", headers = json)
            else -> respond("""{"response":{}}""", headers = json)
        }
    }

    private val idle = CoroutineScope(StandardTestDispatcher(TestCoroutineScheduler()))

    /** The push nudges launched so far: they wait on [idle], which nothing runs. */
    private fun nudges() = idle.coroutineContext.job.children.count()

    /** Signs nothing: returns the message bytes, so a test can read what was signed off the header. */
    private val echoSigner = Ed25519Signer { message -> message }

    private fun tracker(
        stores: MutableMap<String, RecordingStore> = mutableMapOf(),
        signerFor: suspend (TradingApiKey) -> Ed25519Signer = { echoSigner },
        steamWrites: Boolean = true,
        random: Random = Random(7),
    ) = HeadlessTracker(
        platform = HeadlessPlatform({
            stores.getOrPut(it.value) { RecordingStore() }
        }, clock, EventObserver { events += it }, engine, signerFor),
        scope = idle,
        baseUrl = "https://gateway.test",
        steamWrites = steamWrites,
        random = random,
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

    private fun HttpRequestData.foreground(): Boolean =
        Json.parseToJsonElement(text()).jsonObject.getValue("foreground").jsonPrimitive.content.toBoolean()

    private fun HttpRequestData.steamId(): String = Json.parseToJsonElement(text()).jsonObject.getValue("steamId").jsonPrimitive.content

    @Test
    fun the_tracker_is_foreground_from_the_first_heartbeat_while_the_token_has_thirty_minutes_left() = runTest {
        val tracker = tracker()
        tracker.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED)
        tracker.pushSteamToken(steamAccessToken(STEAM_ID, clock.now() + 2.hours))
        tracker.cycle()
        assertEquals(true, heartbeats().last().foreground(), "nothing is bound yet, and the token alone decides")

        tracker.loop.forceHeartbeatNow()
        tracker.cycle()
        assertEquals(true, heartbeats().last().foreground(), "bound, with two hours left")

        clock.advance(91.minutes)
        tracker.loop.forceHeartbeatNow()
        tracker.cycle()
        assertEquals(false, heartbeats().last().foreground(), "29 minutes left")
    }

    @Test
    fun a_first_heartbeat_on_a_token_with_less_than_thirty_minutes_is_not_foreground() = runTest {
        val tracker = tracker()
        tracker.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED)
        tracker.pushSteamToken(steamAccessToken(STEAM_ID, clock.now() + 29.minutes))
        tracker.cycle()
        assertEquals(false, heartbeats().single().foreground())
    }

    @Test
    fun once_another_account_is_named_as_linked_the_tracker_is_not_foreground() = runTest {
        heartbeatBody = """{"activeTracking":[],"directives":[],"ttlSeconds":60,"linkedSteamId":"$OTHER_STEAM_ID"}"""
        val tracker = tracker()
        tracker.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED)
        tracker.pushSteamToken(token)
        tracker.cycle()
        assertEquals(true, heartbeats().single().foreground(), "the gateway checks the account on this one itself")

        tracker.loop.forceHeartbeatNow()
        tracker.cycle()
        assertEquals(2, heartbeats().size)
        assertEquals(false, heartbeats().last().foreground(), "the binding is known now, and it is another account")
    }

    @Test
    fun a_token_that_lapses_after_the_binding_leaves_the_tracker_heartbeating_absent() = runTest {
        heartbeatBody = """
            {"activeTracking":[{"dealId":"deal-1","steamOfferId":"555","watch":["GetTradeOffer"],"role":"seller"}],
             "directives":[{"directiveId":"dir-1","action":"create_offer","dealId":"deal-2",
               "partnerSteamId":"$OTHER_STEAM_ID","assetIds":["asset-7"],"tradeToken":"tok","contextId":2}],
             "ttlSeconds":100,"linkedSteamId":"$STEAM_ID"}
        """.trimIndent()
        val tracker = tracker()
        tracker.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED)
        tracker.pushSteamToken(steamAccessToken(STEAM_ID, clock.now() + 20.minutes))
        tracker.cycle()
        assertEquals(1, heartbeats().size)
        val steamBefore = steamRequests().size

        clock.advance(21.minutes)
        tracker.loop.forceHeartbeatNow()
        tracker.cycle()
        val lapsed = heartbeats().drop(1).single()
        assertEquals(false, lapsed.foreground())
        assertEquals(STEAM_ID, lapsed.steamId(), "the bound account")
        assertEquals(steamBefore, steamRequests().size, "no Steam request on an expired token")
        assertTrue(requests.none { it.url.encodedPath.endsWith("/tradeoffer/new/send") }, "no create")
        assertTrue(tracker.state().readySince != null, "it still owes heartbeats")
        assertEquals(listOf("deal-1"), tracker.state().trackedDealIds.map { it.value })
        assertEquals(clock.now() - 1.minutes, tracker.state().steamTokenExpiresAt, "the expired token stays held")

        tracker.pushSteamToken(steamAccessToken(STEAM_ID, clock.now() + 24.hours))
        tracker.loop.forceHeartbeatNow()
        tracker.cycle()
        assertEquals(true, heartbeats().last().foreground(), "a fresh token makes it present again")
    }

    @Test
    fun a_cancel_leased_after_the_token_lapsed_is_told_by_deal_and_left_to_lapse() = runTest {
        val tracker = tracker()
        tracker.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED)
        tracker.pushSteamToken(steamAccessToken(STEAM_ID, clock.now() + 20.minutes))
        tracker.cycle()
        heartbeatBody = """
            {"activeTracking":[{"dealId":"deal-1","steamOfferId":"555","watch":["GetTradeOffer"],"role":"seller"}],
             "directives":[{"directiveId":"dir-2","action":"cancel_offer","dealId":"deal-1","steamOfferId":"555"}],
             "ttlSeconds":100,"linkedSteamId":"$STEAM_ID"}
        """.trimIndent()
        clock.advance(21.minutes)
        tracker.loop.forceHeartbeatNow()
        tracker.cycle()

        assertTrue(requests.none { "/tradeoffer/" in it.url.encodedPath }, "no cancel without a token")
        assertTrue(requests.none { it.url.encodedPath.endsWith("/p2p/ext/trade-actions") }, "and nothing answered: the lease lapses")
        val dropped = events.filterIsInstance<LifecycleEvent.DirectiveDropped>().single()
        assertEquals(
            Triple("cancel_offer", "deal-1", STEAM_SESSION_MISSING_DROP_REASON),
            Triple(dropped.kind, dropped.dealId, dropped.reason),
        )
    }

    @Test
    fun a_key_with_no_token_and_nothing_bound_does_not_heartbeat() = runTest {
        val tracker = tracker()
        tracker.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED)
        tracker.pushSteamToken(steamAccessToken(STEAM_ID, clock.now() + 1.minutes))
        clock.advance(2.minutes)
        tracker.cycle()
        assertTrue(requests.isEmpty(), "an expired token never bound anything: $requests")
        assertNull(tracker.state().readySince)
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
    fun without_steam_writes_no_offer_write_leaves_the_tracker() = runTest {
        heartbeatBody = """
            {"activeTracking":[{"dealId":"deal-1","steamOfferId":"555","watch":["GetTradeOffer"],"role":"seller"}],
             "directives":[
               {"directiveId":"dir-1","action":"create_offer","dealId":"deal-2","partnerSteamId":"$OTHER_STEAM_ID",
                "assetIds":["asset-7"],"tradeToken":"tok","contextId":2},
               {"directiveId":"dir-2","action":"cancel_offer","dealId":"deal-1","steamOfferId":"555"}],
             "ttlSeconds":100,"linkedSteamId":"$STEAM_ID"}
        """.trimIndent()
        val tracker = tracker(steamWrites = false)
        tracker.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED)
        tracker.pushSteamToken(token)
        tracker.cycle()

        assertTrue(requests.none { "/tradeoffer/" in it.url.encodedPath }, "no offer write: ${requests.map { it.url }}")
        assertTrue(requests.none { it.url.encodedPath.endsWith("/p2p/ext/trade-actions") }, "nothing reported: the leases lapse")
        val dropped = events.filterIsInstance<LifecycleEvent.DirectiveDropped>()
        assertEquals(listOf("dir-1" to "deal-2", "dir-2" to "deal-1"), dropped.map { it.directiveId to it.dealId })
        assertTrue(steamRequests().isNotEmpty(), "reads go on")
    }

    @Test
    fun a_create_that_never_reached_steam_is_never_reported_failed() = runTest {
        heartbeatBody = """
            {"activeTracking":[{"dealId":"deal-1","watch":["GetTradeOffer"],"role":"buyer"},
                               {"dealId":"deal-2","watch":["GetTradeOffer"],"role":"seller"}],
             "directives":[
               {"directiveId":"dir-1","action":"create_offer","dealId":"deal-1","partnerSteamId":"$OTHER_STEAM_ID",
                "assetIds":["asset-7"],"tradeToken":"tok","contextId":2},
               {"directiveId":"dir-2","action":"create_offer","dealId":"deal-2","partnerSteamId":"$OTHER_STEAM_ID",
                "assetIds":["asset-8"],"tradeToken":"tok","contextId":2}],
             "ttlSeconds":100,"linkedSteamId":"$STEAM_ID"}
        """.trimIndent()
        val tracker = tracker()
        tracker.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED)
        tracker.pushSteamToken(steamAccessToken(STEAM_ID, clock.now() + 20.minutes))
        tracker.cycle()

        assertTrue(requests.none { "/tradeoffer/" in it.url.encodedPath })
        assertTrue(
            requests.none { it.url.encodedPath.endsWith("/p2p/ext/trade-actions") },
            "a buyer-role create and a create held back for the token are left to lapse, not answered FAILED",
        )
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

    // ---- another key ------------------------------------------------------------------------------

    /** Key, token, one cycle: the held key is bound to [STEAM_ID]. */
    private suspend fun boundTracker(): HeadlessTracker = tracker().also {
        it.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED)
        it.pushSteamToken(token)
        it.cycle()
        assertEquals(SteamId(STEAM_ID), it.state().linkedSteamId)
    }

    private fun HttpRequestData.apiKey() = headers[TradingApiSignature.API_KEY]

    @Test
    fun a_key_for_the_same_account_rotates_with_one_heartbeat_and_signs_from_then_on() = runTest {
        val tracker = boundTracker()
        val before = heartbeats().size
        val wake = tracker.loop.nextWakeDelay()
        val nudged = nudges()

        assertEquals(
            TradingKeyPushResult.Held(OTHER_PUBLIC_KEY, KeyChange.ROTATED),
            tracker.pushTradingApiKey(OTHER_PUBLIC_KEY, SECRET_SEED),
        )
        assertEquals(nudged, nudges(), "the check was the heartbeat: no second one is asked for")
        val check = heartbeats().drop(before).single()
        assertEquals(OTHER_PUBLIC_KEY, check.apiKey(), "the check is signed with the pushed key")
        val body = Json.parseToJsonElement(check.text()).jsonObject
        assertEquals(STEAM_ID, body.getValue("steamId").jsonPrimitive.content)
        assertEquals("sdk", body.getValue("platform").jsonPrimitive.content)
        assertEquals(heartbeats().first().deviceId(), check.deviceId(), "the same device, not a second one")
        assertEquals(wake, tracker.loop.nextWakeDelay(), "the loop's schedule did not move")

        tracker.loop.forceHeartbeatNow()
        tracker.cycle()
        assertEquals(OTHER_PUBLIC_KEY, heartbeats().last().apiKey(), "the loop signs with the rotated key")
        assertEquals(SteamId(STEAM_ID), tracker.state().linkedSteamId)
    }

    @Test
    fun a_key_for_another_account_is_refused_and_the_held_key_and_binding_stay() = runTest {
        val tracker = boundTracker()
        otherKeyHeartbeat = """{"activeTracking":[],"directives":[],"ttlSeconds":100,"linkedSteamId":"$OTHER_STEAM_ID"}"""

        assertEquals(
            TradingKeyPushResult.AccountChanged(OTHER_PUBLIC_KEY, PUBLIC_KEY, SteamId(STEAM_ID), SteamId(OTHER_STEAM_ID)),
            tracker.pushTradingApiKey(OTHER_PUBLIC_KEY, SECRET_SEED),
        )
        otherKeyHeartbeat = """{"activeTracking":[],"directives":[],"ttlSeconds":100}"""
        assertEquals(
            TradingKeyPushResult.AccountChanged(OTHER_PUBLIC_KEY, PUBLIC_KEY, SteamId(STEAM_ID), null),
            tracker.pushTradingApiKey(OTHER_PUBLIC_KEY, SECRET_SEED),
            "an account that links no Steam account is another account too",
        )

        assertEquals(SteamId(STEAM_ID), tracker.state().linkedSteamId, "the binding stays")
        assertEquals(
            SteamPushResult.WrongAccount(SteamId(OTHER_STEAM_ID), SteamId(STEAM_ID)),
            tracker.pushSteamToken(steamAccessToken(OTHER_STEAM_ID, clock.now() + 24.hours)),
            "a token is still checked against the bound account",
        )
        tracker.loop.forceHeartbeatNow()
        tracker.cycle()
        assertEquals(PUBLIC_KEY, heartbeats().last().apiKey(), "the held key still signs")
        assertEquals(TradingKeyPushResult.Held(PUBLIC_KEY, KeyChange.SAME), tracker.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED))
    }

    @Test
    fun a_key_whose_check_does_not_come_back_is_not_held() = runTest {
        val tracker = boundTracker()

        otherKeyStatus = HttpStatusCode.Unauthorized
        assertEquals(TradingKeyPushResult.Invalid("DMarket refused the key"), tracker.pushTradingApiKey(OTHER_PUBLIC_KEY, SECRET_SEED))
        otherKeyStatus = HttpStatusCode.Forbidden
        assertEquals(TradingKeyPushResult.Invalid("DMarket refused the key"), tracker.pushTradingApiKey(OTHER_PUBLIC_KEY, SECRET_SEED))
        otherKeyStatus = HttpStatusCode.BadGateway
        assertEquals(
            TradingKeyPushResult.Unverified(OTHER_PUBLIC_KEY, "its heartbeat failed: HTTP 502"),
            tracker.pushTradingApiKey(OTHER_PUBLIC_KEY, SECRET_SEED),
        )
        otherKeyStatus = HttpStatusCode.TooManyRequests
        assertEquals(
            TradingKeyPushResult.Unverified(OTHER_PUBLIC_KEY, "its heartbeat was rate-limited"),
            tracker.pushTradingApiKey(OTHER_PUBLIC_KEY, SECRET_SEED),
        )
        otherKeyUnreachable = true
        assertEquals(
            TradingKeyPushResult.Unverified(OTHER_PUBLIC_KEY, "its heartbeat did not come back"),
            tracker.pushTradingApiKey(OTHER_PUBLIC_KEY, SECRET_SEED),
        )

        clock.advance(HeadlessTracker.RATE_LIMIT_BACKOFF_BASE * 2) // the check's 429 quieted DMarket calls
        tracker.loop.forceHeartbeatNow()
        tracker.cycle()
        assertEquals(PUBLIC_KEY, heartbeats().last().apiKey(), "the held key still signs")
        assertEquals(SteamId(STEAM_ID), tracker.state().linkedSteamId)
    }

    @Test
    fun with_no_account_bound_yet_a_key_whose_heartbeat_comes_back_replaces_the_held_one() = runTest {
        val tracker = tracker()
        tracker.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED)
        tracker.pushSteamToken(token)

        otherKeyUnreachable = true
        assertIs<TradingKeyPushResult.Unverified>(
            tracker.pushTradingApiKey(OTHER_PUBLIC_KEY, SECRET_SEED),
            "checked even with nothing bound",
        )
        otherKeyUnreachable = false
        val nudged = nudges()
        assertEquals(
            TradingKeyPushResult.Held(OTHER_PUBLIC_KEY, KeyChange.REPLACED),
            tracker.pushTradingApiKey(OTHER_PUBLIC_KEY, SECRET_SEED),
        )
        assertEquals(nudged + 1, nudges(), "a key with nothing bound heartbeats at once to take the binding")
        val signedWith = heartbeats().map { it.apiKey() }
        assertEquals(listOf(OTHER_PUBLIC_KEY, OTHER_PUBLIC_KEY), signedWith, "one check per push, signed with the pushed key")

        tracker.cycle()
        assertEquals(OTHER_PUBLIC_KEY, heartbeats().last().apiKey())
        assertEquals(SteamId(STEAM_ID), tracker.state().linkedSteamId, "the binding comes from the new key's heartbeat")
    }

    @Test
    fun with_no_token_ever_held_another_key_replaces_the_held_one_unchecked() = runTest {
        val tracker = tracker()
        tracker.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED)

        assertEquals(
            TradingKeyPushResult.Held(OTHER_PUBLIC_KEY, KeyChange.REPLACED),
            tracker.pushTradingApiKey(OTHER_PUBLIC_KEY, SECRET_SEED),
        )
        assertTrue(requests.isEmpty(), "no Steam account to heartbeat for, and the held key was never sent: $requests")
        assertNull(tracker.state().linkedSteamId, "nothing is bound yet")
    }

    @Test
    fun a_key_taken_unchecked_binds_with_the_first_heartbeat_and_later_keys_are_checked_against_it() = runTest {
        val tracker = tracker()
        tracker.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED)
        tracker.pushTradingApiKey(OTHER_PUBLIC_KEY, SECRET_SEED)

        tracker.pushSteamToken(token)
        tracker.cycle()
        assertEquals(OTHER_PUBLIC_KEY, heartbeats().single().apiKey(), "the first heartbeat is signed with the key held then")
        assertEquals(SteamId(STEAM_ID), tracker.state().linkedSteamId, "and takes the binding")

        // The first key, pushed again, is now checked: its account links another Steam account.
        heartbeatBody = """{"activeTracking":[],"ttlSeconds":100,"linkedSteamId":"$OTHER_STEAM_ID"}"""
        assertIs<TradingKeyPushResult.AccountChanged>(tracker.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED))
        assertEquals(SteamId(STEAM_ID), tracker.state().linkedSteamId)
    }

    @Test
    fun no_secret_reaches_storage_or_the_events() = runTest {
        val stores = mutableMapOf<String, RecordingStore>()
        val tracker = tracker(stores)
        tracker.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED + PUBLIC_KEY)
        tracker.pushSteamToken(token)
        tracker.cycle()
        tracker.pushSteamToken(steamAccessToken(OTHER_STEAM_ID, clock.now() + 24.hours))
        val checks = buildList {
            otherKeyHeartbeat = """{"activeTracking":[],"ttlSeconds":100,"linkedSteamId":"$OTHER_STEAM_ID"}"""
            add(tracker.pushTradingApiKey(OTHER_PUBLIC_KEY, SECRET_SEED))
            otherKeyStatus = HttpStatusCode.Unauthorized
            add(tracker.pushTradingApiKey(OTHER_PUBLIC_KEY, SECRET_SEED))
            otherKeyUnreachable = true
            add(tracker.pushTradingApiKey(OTHER_PUBLIC_KEY, SECRET_SEED))
        }
        val sentToDmarket = requests.filter { "gateway" in it.url.host }.map { it.headers.entries().toString() + it.text() }

        val payload = token.substringAfter('.').substringBefore('.')
        val everything = stores.values.flatMap { it.entries.values } + events.map { it.toString() } + tracker.state().toString() +
            checks.map { it.toString() } + sentToDmarket
        for (text in everything) {
            assertTrue(SECRET_SEED !in text, "the secret key leaked: $text")
            assertTrue(payload !in text, "the Steam token leaked: $text")
        }
        assertTrue(stores.getValue(STEAM_ID).entries.isNotEmpty(), "the loop's own state is persisted")
        assertTrue(events.any { it is LifecycleEvent.HeartbeatSent }, "the events were observed: $events")
    }

    @Test
    fun a_restart_holds_neither_secret_and_keeps_the_device_id() = runTest {
        val stores = mutableMapOf<String, RecordingStore>()
        val first = tracker(stores)
        first.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED)
        first.pushSteamToken(token)
        first.cycle()
        val deviceId = heartbeats().single().deviceId()

        val restarted = tracker(stores)
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

    // ---- what a refused heartbeat says --------------------------------------------------------------

    @Test
    fun a_heartbeat_refused_for_an_unlinked_account_says_so_until_one_comes_back() = runTest {
        heartbeatStatus = HttpStatusCode.Forbidden
        heartbeatBody = """{"code":"P2P_STEAM_NOT_LINKED","message":"no steam account linked"}"""
        val tracker = tracker()
        tracker.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED)
        tracker.pushSteamToken(token)
        tracker.cycle()

        assertTrue(tracker.state().steamNotLinked)
        assertTrue(steamRequests().isEmpty(), "no Steam action without a linked account")
        assertTrue(
            events.any { it is LifecycleEvent.MarketplaceServerError && it.statusCode == 403 },
            "the loop reads it as any refused heartbeat: $events",
        )

        heartbeatStatus = HttpStatusCode.OK
        heartbeatBody = """{"activeTracking":[],"ttlSeconds":100,"linkedSteamId":"$STEAM_ID"}"""
        tracker.loop.forceHeartbeatNow()
        tracker.cycle()
        assertEquals(false, tracker.state().steamNotLinked)
    }

    @Test
    fun another_403_is_not_an_unlinked_account() = runTest {
        heartbeatStatus = HttpStatusCode.Forbidden
        heartbeatBody = """{"code":"Forbidden"}"""
        val tracker = tracker()
        tracker.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED)
        tracker.pushSteamToken(token)
        tracker.cycle()
        assertEquals(false, tracker.state().steamNotLinked)
    }

    @Test
    fun dmarkets_date_measures_this_hosts_clock() = runTest {
        val tracker = tracker()
        assertNull(tracker.state().clockOffset, "nothing heard before a reply")
        tracker.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED)
        tracker.pushSteamToken(token)

        serverDate = httpDate(clock.now() + 30.seconds)
        tracker.cycle()
        assertEquals(30.seconds, tracker.state().clockOffset)
        assertEquals(false, tracker.state().clockSkewed, "within a minute")

        serverDate = httpDate(clock.now() - 3.minutes)
        heartbeatStatus = HttpStatusCode.Unauthorized
        tracker.loop.forceHeartbeatNow()
        tracker.cycle()
        assertEquals((-3).minutes, tracker.state().clockOffset, "a refusal's Date counts too")
        assertTrue(tracker.state().clockSkewed)
    }

    @Test
    fun a_key_refused_while_the_clock_is_off_is_unchecked_rather_than_invalid() = runTest {
        val tracker = boundTracker()
        serverDate = httpDate(clock.now() + 5.minutes)
        otherKeyStatus = HttpStatusCode.Unauthorized

        val refused = assertIs<TradingKeyPushResult.Unverified>(tracker.pushTradingApiKey(OTHER_PUBLIC_KEY, SECRET_SEED))
        assertTrue("clock" in refused.reason, refused.reason)
        tracker.loop.forceHeartbeatNow()
        tracker.cycle()
        assertEquals(PUBLIC_KEY, heartbeats().last().apiKey(), "the held key stays")
    }

    @Test
    fun a_key_whose_account_links_no_steam_account_is_another_account() = runTest {
        val tracker = boundTracker()
        otherKeyStatus = HttpStatusCode.Forbidden
        otherKeyHeartbeat = """{"code":"P2P_STEAM_NOT_LINKED"}"""

        assertEquals(
            TradingKeyPushResult.AccountChanged(OTHER_PUBLIC_KEY, PUBLIC_KEY, SteamId(STEAM_ID), null),
            tracker.pushTradingApiKey(OTHER_PUBLIC_KEY, SECRET_SEED),
        )
        assertEquals(false, tracker.state().steamNotLinked, "the probe does not speak for the held key's account")
    }

    private fun httpDate(at: Instant): String = GMTDate(at.toEpochMilliseconds()).toHttpDate()

    // ---- state per account --------------------------------------------------------------------------

    @Test
    fun the_state_is_kept_under_the_linked_account_and_a_kept_device_id_rides_the_first_heartbeat() = runTest {
        val stores = mutableMapOf(STEAM_ID to RecordingStore().apply { entries["device_id"] = "device-kept" })
        stores[OTHER_STEAM_ID] = RecordingStore().apply { entries["device_id"] = "device-of-another-account" }
        val tracker = tracker(stores)
        tracker.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED)
        tracker.pushSteamToken(token)
        tracker.cycle()

        assertEquals("device-kept", heartbeats().single().deviceId(), "the account's own device id, from the first heartbeat")
        assertTrue(stores.getValue(STEAM_ID).entries.size > 1, "the loop writes under the linked account: ${stores.keys}")
        assertEquals(mapOf("device_id" to "device-of-another-account"), stores.getValue(OTHER_STEAM_ID).entries, "untouched")
    }

    @Test
    fun a_first_device_id_is_written_only_once_the_heartbeat_names_the_tokens_account() = runTest {
        val stores = mutableMapOf<String, RecordingStore>()
        val tracker = tracker(stores)
        tracker.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED)
        tracker.pushSteamToken(token)
        heartbeatStatus = HttpStatusCode.BadGateway
        tracker.cycle()
        assertTrue(stores.values.all { it.entries.isEmpty() }, "nothing is written before an account is named: $stores")

        heartbeatStatus = HttpStatusCode.OK
        tracker.loop.forceHeartbeatNow()
        tracker.cycle()
        val sent = heartbeats().map { it.deviceId() }
        assertEquals(1, sent.toSet().size, "one device id throughout: $sent")
        assertEquals(sent.first(), stores.getValue(STEAM_ID).entries["device_id"])
    }

    @Test
    fun a_token_for_another_account_leaves_its_state_alone_and_the_linked_accounts_is_used() = runTest {
        val stores = mutableMapOf<String, RecordingStore>()
        val tracker = tracker(stores)
        tracker.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED)
        tracker.pushSteamToken(steamAccessToken(OTHER_STEAM_ID, clock.now() + 24.hours))
        tracker.cycle()

        assertTrue(stores[OTHER_STEAM_ID]?.entries.isNullOrEmpty(), "the token's account is not written: $stores")
        assertTrue(steamRequests().isEmpty(), "a token for another account takes no Steam action")
        val probe = heartbeats().single().deviceId()

        assertIs<SteamPushResult.Held>(tracker.pushSteamToken(token))
        tracker.loop.forceHeartbeatNow()
        tracker.cycle()
        val linkedDevice = stores.getValue(STEAM_ID).entries.getValue("device_id")
        assertEquals(linkedDevice, heartbeats().last().deviceId(), "the linked account's own device id")
        assertTrue(linkedDevice != probe, "the first heartbeat's id was the other account's and leased nothing")
    }

    @Test
    fun the_minimum_version_signals_are_read_from_a_forwarded_heartbeat_only() = runTest {
        heartbeatBody = """{"ttlSeconds":100,"linkedSteamId":"$STEAM_ID","updateRequired":true,"updateDueTime":"2026-11-01T00:00:00Z"}"""
        val tracker = tracker()
        tracker.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED)
        tracker.pushSteamToken(token)
        tracker.cycle()
        assertEquals(true, tracker.state().updateRequired)
        assertEquals(Instant.parse("2026-11-01T00:00:00Z"), tracker.state().updateDueTime)

        heartbeatBody = """{"ttlSeconds":100,"linkedSteamId":"$OTHER_STEAM_ID"}"""
        tracker.loop.forceHeartbeatNow()
        tracker.cycle()
        assertEquals(true, tracker.state().updateRequired, "another account's answer says nothing about this version")

        heartbeatBody = """{"ttlSeconds":100,"linkedSteamId":"$STEAM_ID"}"""
        tracker.loop.forceHeartbeatNow()
        tracker.cycle()
        assertEquals(false, tracker.state().updateRequired)
        assertNull(tracker.state().updateDueTime)
    }

    @Test
    fun the_deal_deadlines_are_read_from_the_last_heartbeat() = runTest {
        heartbeatBody = """
            {"activeTracking":[{"dealId":"deal-1","deadlineTime":"2026-10-16T14:00:00Z"},{"dealId":"deal-2"}],
             "directives":[{"directiveId":"dir-1","action":"create_offer","dealId":"deal-3","deadlineTime":"2026-10-16T15:00:00Z"}],
             "ttlSeconds":100,"linkedSteamId":"$STEAM_ID"}
        """.trimIndent()
        val tracker = tracker()
        tracker.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED)
        tracker.pushSteamToken(token)
        tracker.cycle()
        assertEquals(
            mapOf(DealId("deal-1") to Instant.parse("2026-10-16T14:00:00Z"), DealId("deal-3") to Instant.parse("2026-10-16T15:00:00Z")),
            tracker.state().dealDeadlines,
        )

        heartbeatBody = """{"activeTracking":[{"dealId":"deal-1"}],"ttlSeconds":100,"linkedSteamId":"$STEAM_ID"}"""
        tracker.loop.forceHeartbeatNow()
        tracker.cycle()
        assertEquals(emptyMap(), tracker.state().dealDeadlines, "a deadline the backend stops sending is gone")
    }

    // ---- 429 and jitter -----------------------------------------------------------------------------

    /** Every draw is 0: no jitter. */
    private object Lowest : Random() {
        override fun nextBits(bitCount: Int): Int = 0
    }

    /** Every draw is just under 1: the most jitter. */
    private object Highest : Random() {
        override fun nextBits(bitCount: Int): Int = -1 ushr (32 - bitCount)
    }

    private suspend fun rateLimitedTracker(random: Random = Lowest): HeadlessTracker = tracker(random = random).also {
        it.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED)
        it.pushSteamToken(token)
        heartbeatStatus = HttpStatusCode.TooManyRequests
    }

    @Test
    fun a_429_quiets_every_call_until_its_retry_after_has_passed() = runTest {
        retryAfter = "120"
        val tracker = rateLimitedTracker()
        tracker.cycle()
        assertEquals(1, requests.size)

        heartbeatStatus = HttpStatusCode.OK
        retryAfter = null
        clock.advance(119.seconds)
        tracker.loop.forceHeartbeatNow()
        tracker.cycle()
        assertEquals(1, requests.size, "nothing goes out before Retry-After, a push's nudge included")

        clock.advance(1.seconds)
        tracker.cycle()
        assertEquals(2, heartbeats().size, "once it has passed, the heartbeat goes out")
    }

    @Test
    fun without_retry_after_429s_back_off_doubling_with_jitter() = runTest {
        val tracker = rateLimitedTracker(Highest)
        tracker.cycle()
        clock.advance(HeadlessTracker.RATE_LIMIT_BACKOFF_BASE)
        tracker.cycle()
        assertEquals(1, heartbeats().size, "the first wait is the base, plus jitter")

        clock.advance(HeadlessTracker.RATE_LIMIT_BACKOFF_BASE / 4)
        tracker.cycle()
        assertEquals(2, heartbeats().size)

        clock.advance(HeadlessTracker.RATE_LIMIT_BACKOFF_BASE * 2)
        tracker.cycle()
        assertEquals(2, heartbeats().size, "the second doubles")
        clock.advance(HeadlessTracker.RATE_LIMIT_BACKOFF_BASE / 2)
        tracker.cycle()
        assertEquals(3, heartbeats().size)
    }

    @Test
    fun a_retry_after_shorter_than_the_backoff_does_not_shorten_it() = runTest {
        retryAfter = "1"
        val tracker = rateLimitedTracker()
        tracker.cycle()
        clock.advance(2.seconds)
        tracker.cycle()
        assertEquals(1, heartbeats().size)
        clock.advance(HeadlessTracker.RATE_LIMIT_BACKOFF_BASE)
        tracker.cycle()
        assertEquals(2, heartbeats().size)
    }

    @Test
    fun the_heartbeat_follows_the_advertised_ttl_a_few_seconds_inside_it() = runTest {
        heartbeatBody = """{"activeTracking":[],"ttlSeconds":80,"linkedSteamId":"$STEAM_ID"}"""
        val tracker = tracker().also {
            it.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED)
            it.pushSteamToken(token)
            it.cycle()
        }
        assertEquals(75.seconds, tracker.loop.nextWakeDelay(), "an 80 s ttl beats at 75 s, not on a 60 s floor")

        heartbeatBody = """{"activeTracking":[],"ttlSeconds":95,"linkedSteamId":"$STEAM_ID"}"""
        tracker.loop.forceHeartbeatNow()
        tracker.cycle()
        assertEquals(90.seconds, tracker.loop.nextWakeDelay())

        clock.advance(88.seconds)
        assertEquals(5.seconds, tracker.loop.nextWakeDelay(), "a wake 2 s short of the beat still lands inside the ttl")
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
