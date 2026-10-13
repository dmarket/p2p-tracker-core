package com.dmarket.p2p.sdk

import com.dmarket.p2p.sdk.api.PushOutcome
import com.dmarket.p2p.sdk.health.ProblemCode
import com.dmarket.p2p.sdk.signing.TradingApiSignature
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.port.host.DeviceKeyValueStore
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The composed SDK against a scripted gateway and Steam. Cycles are driven by hand: the runtime's own scope runs
 * on a dispatcher nothing advances, so no background wake can race an assertion.
 */
class SdkRuntimeTest {
    private val clock = TestClock()
    private val token = steamToken()
    private val requests = mutableListOf<HttpRequestData>()
    private val logLines = mutableListOf<String>()
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

    private fun runtime(storage: DeviceKeyValueStore = RecordingStore()) = SdkRuntime(
        config = SdkConfig(managementSecret = "m".repeat(32), dataDir = "/unused", dmarketBaseUrl = "https://gateway.test"),
        platform = SdkPlatform(storage, { echoSigner }, { bytes -> "ab".repeat(bytes) }, clock, SdkLog(clock) { logLines += it }, engine),
        scope = idle,
    )

    private fun heartbeats() = requests.filter { it.url.encodedPath.endsWith("/p2p/ext/heartbeat") }

    private fun steamRequests() = requests.filter { "steam" in it.url.host }

    private fun HttpRequestData.text(): String = when (val content = body) {
        is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
        else -> ""
    }

    @Test
    fun no_heartbeat_until_both_the_key_and_the_token_are_pushed() = runTest {
        val sdk = runtime()
        sdk.cycle()
        assertTrue(requests.isEmpty(), "nothing is sent before a push")

        assertEquals(PushOutcome.Held, sdk.putDmarketKey(PUBLIC_KEY, SECRET_SEED + PUBLIC_KEY))
        sdk.cycle()
        assertTrue(requests.isEmpty(), "a key alone does not heartbeat: there is no Steam account to report")

        assertEquals(PushOutcome.Held, sdk.putSteamToken(token))
        sdk.cycle()
        val heartbeat = heartbeats().single()
        assertEquals(PUBLIC_KEY, heartbeat.headers[TradingApiSignature.API_KEY])
        assertTrue(heartbeat.headers[TradingApiSignature.REQUEST_SIGN].orEmpty().startsWith("dmar ed25519 "))
        assertEquals(null, heartbeat.headers[HttpHeaders.Authorization], "no session token rides an API-key call")
        val body = Json.parseToJsonElement(heartbeat.text()).jsonObject
        assertEquals("sdk", body.getValue("platform").jsonPrimitive.content)
        assertEquals(STEAM_ID, body.getValue("steamId").jsonPrimitive.content)
    }

    @Test
    fun without_a_linked_account_the_sdk_heartbeats_but_takes_no_steam_action() = runTest {
        heartbeatBody = """{"activeTracking":[{"dealId":"deal-1","steamOfferId":"555","watch":["GetTradeOffer"]}],"ttlSeconds":100}"""
        val sdk = runtime()
        sdk.putDmarketKey(PUBLIC_KEY, SECRET_SEED)
        sdk.putSteamToken(token)
        sdk.cycle()

        assertEquals(1, heartbeats().size)
        assertTrue(steamRequests().isEmpty(), "no Steam request while DMarket names no linked account")
        assertTrue(ProblemCode.STEAM_ACCOUNT_UNKNOWN in sdk.health().problems.map { it.code })
    }

    @Test
    fun a_create_directive_reaches_steam_with_the_pushed_session_and_is_reported() = runTest {
        heartbeatBody = """
            {"activeTracking":[{"dealId":"deal-1","watch":["GetTradeOffer"],"role":"seller"}],
             "directives":[{"directiveId":"dir-1","action":"create_offer","dealId":"deal-1",
               "partnerSteamId":"$OTHER_STEAM_ID","assetIds":["asset-7"],"tradeToken":"tok","contextId":2}],
             "ttlSeconds":100,"linkedSteamId":"$STEAM_ID"}
        """.trimIndent()
        val sdk = runtime()
        sdk.putDmarketKey(PUBLIC_KEY, SECRET_SEED)
        sdk.putSteamToken(token)
        sdk.cycle()

        val create = requests.single { it.url.encodedPath.endsWith("/tradeoffer/new/send") }
        assertEquals("steamLoginSecure=$STEAM_ID%7C%7C$token; sessionid=${"ab".repeat(12)}", create.headers[HttpHeaders.Cookie])
        assertEquals("https://steamcommunity.com", create.headers[HttpHeaders.Origin])
        val report = requests.single { it.url.encodedPath.endsWith("/p2p/ext/trade-actions") }
        assertTrue(""""status":"needs_confirmation"""" in report.text() && """"steamOfferId":"555"""" in report.text(), report.text())
        assertTrue(report.headers[TradingApiSignature.REQUEST_SIGN] != null, "the report is signed too")
    }

    @Test
    fun once_dmarket_names_the_account_a_token_for_another_is_refused_and_the_held_one_stays() = runTest {
        val sdk = runtime()
        sdk.putDmarketKey(PUBLIC_KEY, SECRET_SEED)
        sdk.putSteamToken(token)
        sdk.cycle()

        val refused = sdk.putSteamToken(steamToken(sub = OTHER_STEAM_ID))
        assertIs<PushOutcome.Conflict>(refused)
        assertEquals("STEAM_WRONG_ACCOUNT", refused.error)
        assertEquals(SteamId(STEAM_ID), sdk.secrets.steamCredential?.subjectSteamId)
        assertTrue(ProblemCode.STEAM_WRONG_ACCOUNT in sdk.health().problems.map { it.code })
    }

    @Test
    fun no_secret_reaches_storage_the_logs_or_health() = runTest {
        val storage = RecordingStore()
        val sdk = runtime(storage)
        sdk.putDmarketKey(PUBLIC_KEY, SECRET_SEED + PUBLIC_KEY)
        sdk.putSteamToken(token)
        sdk.cycle()
        sdk.putSteamToken(steamToken(sub = OTHER_STEAM_ID))

        val payload = token.substringAfter('.')
        val everything =
            storage.entries.values + logLines + listOf(Json.encodeToString(com.dmarket.p2p.sdk.health.Health.serializer(), sdk.health()))
        for (text in everything) {
            assertTrue(SECRET_SEED !in text, "the secret key leaked: $text")
            assertTrue(payload !in text, "the Steam token leaked: $text")
        }
        assertTrue(storage.entries.isNotEmpty(), "the loop's own state is persisted")
    }

    @Test
    fun a_restart_reports_both_secrets_missing_and_keeps_the_device_id() = runTest {
        val storage = RecordingStore()
        val first = runtime(storage)
        first.putDmarketKey(PUBLIC_KEY, SECRET_SEED)
        first.putSteamToken(token)
        first.cycle()
        val deviceId = Json.parseToJsonElement(heartbeats().single().text()).jsonObject.getValue("deviceId").jsonPrimitive.content

        val restarted = runtime(storage)
        restarted.cycle()
        assertEquals(1, heartbeats().size, "a restarted SDK does not heartbeat before the pushes")
        val codes = restarted.health().problems.map { it.code }
        assertTrue(ProblemCode.DMARKET_KEY_MISSING in codes && ProblemCode.STEAM_TOKEN_MISSING in codes, "$codes")

        restarted.putDmarketKey(PUBLIC_KEY, SECRET_SEED)
        restarted.putSteamToken(token)
        restarted.loop.forceHeartbeatNow()
        restarted.cycle()
        val again = Json.parseToJsonElement(heartbeats().last().text()).jsonObject.getValue("deviceId").jsonPrimitive.content
        assertEquals(deviceId, again, "the directive lease key survives the restart")
    }
}
