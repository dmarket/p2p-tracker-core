package com.dmarket.p2p.sdk

import com.dmarket.p2p.sdk.api.PushOutcome
import com.dmarket.p2p.sdk.health.Health
import com.dmarket.p2p.sdk.health.ProblemCode
import com.dmarket.p2p.tracker.adapter.host.InMemoryDeviceKeyValueStore
import com.dmarket.p2p.tracker.client.marketplace.TradingApiSignature
import com.dmarket.p2p.tracker.model.marketplace.TradingApiKey
import com.dmarket.p2p.tracker.port.marketplace.Ed25519Signer
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * The SDK shell over the library's headless tracker: how pushes map to the API's answers, and what health and the
 * log make of them and of the loop's events. The tracker itself is tested in the library. Cycles are driven by
 * hand: the runtime's scope runs on a dispatcher nothing advances, so no background wake can race an assertion.
 */
class SdkRuntimeTest {
    private val clock = TestClock()
    private val token = steamToken()
    private val logLines = mutableListOf<String>()
    private var heartbeatBody = """{"activeTracking":[],"directives":[],"ttlSeconds":100,"linkedSteamId":"$STEAM_ID"}"""

    /** What a heartbeat signed with [OTHER_PUBLIC_KEY] gets instead of [heartbeatBody]. */
    private var otherKeyHeartbeat: String? = null
    private var otherKeyStatus = HttpStatusCode.OK

    private val engine = MockEngine { request ->
        val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        val heartbeat = request.url.encodedPath.endsWith("/p2p/ext/heartbeat")
        when {
            heartbeat && request.headers[TradingApiSignature.API_KEY] == OTHER_PUBLIC_KEY ->
                respond(otherKeyHeartbeat ?: heartbeatBody, otherKeyStatus, json)
            heartbeat -> respond(heartbeatBody, headers = json)
            else -> respond("""{"response":{}}""", headers = json)
        }
    }

    private val idle = CoroutineScope(StandardTestDispatcher(TestCoroutineScheduler()))

    private fun runtime(signerFor: suspend (TradingApiKey) -> Ed25519Signer = { echoSigner }) = SdkRuntime(
        config = SdkConfig(managementSecret = "m".repeat(32), dataDir = "/unused", dmarketBaseUrl = "https://gateway.test"),
        platform = SdkPlatform(InMemoryDeviceKeyValueStore(), clock, SdkLog(clock) { logLines += it }, engine, signerFor),
        scope = idle,
    )

    private fun SdkRuntime.codes() = health().problems.map { it.code }

    @Test
    fun a_key_push_answers_held_or_invalid_without_quoting_the_secret() = runTest {
        val sdk = runtime()
        val malformed = sdk.putDmarketKey(PUBLIC_KEY, "${SECRET_SEED}zz")
        assertEquals(PushOutcome.Invalid("INVALID_KEY", "expected a hex Ed25519 key pair: a 32-byte public key and its secret"), malformed)
        assertEquals(PushOutcome.Held, sdk.putDmarketKey(PUBLIC_KEY, SECRET_SEED))
        assertEquals(PushOutcome.Held, sdk.putDmarketKey(PUBLIC_KEY, SECRET_SEED), "the same key again")

        val unsigned = runtime(signerFor = { throw IllegalArgumentException("not this key") })
        assertEquals(PushOutcome.Invalid("INVALID_KEY", "the secret key cannot sign"), unsigned.putDmarketKey(PUBLIC_KEY, SECRET_SEED))
    }

    @Test
    fun a_token_push_answers_held_invalid_or_conflict() = runTest {
        val sdk = runtime()
        val refresh = steamToken(aud = listOf("web", "renew"))
        assertEquals(PushOutcome.Invalid("INVALID_TOKEN", "a refresh token is never accepted"), sdk.putSteamToken(refresh))
        assertEquals(PushOutcome.Held, sdk.putSteamToken(token))
        assertEquals(
            PushOutcome.Conflict("STEAM_WRONG_ACCOUNT", "the token is for $OTHER_STEAM_ID, not the account of the token already held"),
            sdk.putSteamToken(steamToken(sub = OTHER_STEAM_ID)),
        )

        sdk.putDmarketKey(PUBLIC_KEY, SECRET_SEED)
        sdk.tracker.cycle()
        assertEquals(
            PushOutcome.Conflict("STEAM_WRONG_ACCOUNT", "the token is for $OTHER_STEAM_ID, not $STEAM_ID"),
            sdk.putSteamToken(steamToken(sub = OTHER_STEAM_ID)),
            "once DMarket names the account, the refusal names it too",
        )
    }

    @Test
    fun a_fresh_start_is_down_with_both_secrets_missing() {
        val health = runtime().health()
        assertEquals(listOf(ProblemCode.DMARKET_KEY_MISSING, ProblemCode.STEAM_TOKEN_MISSING), health.problems.map { it.code })
        assertEquals("down", health.status)
        assertEquals(SdkRuntime.VERSION, health.version)
    }

    @Test
    fun health_follows_the_held_token_and_the_loops_events() = runTest {
        heartbeatBody = """{"activeTracking":[],"ttlSeconds":100}"""
        val sdk = runtime()
        sdk.putDmarketKey(PUBLIC_KEY, SECRET_SEED)
        sdk.putSteamToken(token)
        sdk.tracker.cycle()

        val health = sdk.health()
        assertEquals(listOf(ProblemCode.STEAM_ACCOUNT_UNKNOWN), health.problems.map { it.code }, "the loop's event reached health")
        assertEquals((T0 + 24.hours).toString(), health.steamTokenExpiresAt)
        assertEquals(T0.toString(), health.lastHeartbeatAt)
    }

    @Test
    fun a_refused_token_is_a_wrong_account_until_one_is_held() = runTest {
        val sdk = runtime()
        sdk.putDmarketKey(PUBLIC_KEY, SECRET_SEED)
        sdk.putSteamToken(token)
        sdk.putSteamToken(steamToken(sub = OTHER_STEAM_ID))
        assertEquals(listOf(ProblemCode.STEAM_WRONG_ACCOUNT), sdk.codes())

        sdk.putSteamToken(token)
        assertTrue(sdk.codes().isEmpty(), "${sdk.codes()}")
    }

    /** Key, token, one cycle: the held key is bound to [STEAM_ID]. */
    private suspend fun boundRuntime(): SdkRuntime = runtime().also {
        it.putDmarketKey(PUBLIC_KEY, SECRET_SEED)
        it.putSteamToken(token)
        it.tracker.cycle()
    }

    @Test
    fun a_key_for_the_same_account_rotates() = runTest {
        val sdk = boundRuntime()
        assertEquals(PushOutcome.Held, sdk.putDmarketKey(OTHER_PUBLIC_KEY, SECRET_SEED))
        assertTrue(logLines.any { "\"msg\":\"dmarket key rotated\"" in it && OTHER_PUBLIC_KEY in it }, "$logLines")
        assertTrue(sdk.codes().isEmpty(), "${sdk.codes()}")
    }

    @Test
    fun a_key_for_another_account_is_a_conflict_until_a_key_is_held() = runTest {
        val sdk = boundRuntime()
        otherKeyHeartbeat = """{"activeTracking":[],"ttlSeconds":100,"linkedSteamId":"$OTHER_STEAM_ID"}"""

        assertEquals(
            PushOutcome.Conflict(
                "DMARKET_KEY_ACCOUNT_CHANGED",
                "the key's account links Steam account $OTHER_STEAM_ID, not $STEAM_ID; the held key stays: switching accounts takes a restart",
            ),
            sdk.putDmarketKey(OTHER_PUBLIC_KEY, SECRET_SEED),
        )
        val health = sdk.health()
        assertEquals(listOf(ProblemCode.DMARKET_KEY_ACCOUNT_CHANGED), health.problems.map { it.code })
        assertEquals("degraded", health.status, "the held key still serves its own account")
        val line = logLines.single { "\"msg\":\"dmarket key for another account refused\"" in it }
        assertTrue(PUBLIC_KEY in line && OTHER_PUBLIC_KEY in line && OTHER_STEAM_ID in line, line)

        assertEquals(PushOutcome.Held, sdk.putDmarketKey(PUBLIC_KEY, SECRET_SEED))
        assertTrue(sdk.codes().isEmpty(), "a held key ends the refusal: ${sdk.codes()}")
    }

    @Test
    fun a_key_that_cannot_be_checked_is_refused_by_why() = runTest {
        val sdk = boundRuntime()
        otherKeyStatus = HttpStatusCode.Unauthorized
        assertEquals(PushOutcome.Invalid("INVALID_KEY", "DMarket refused the key"), sdk.putDmarketKey(OTHER_PUBLIC_KEY, SECRET_SEED))
        otherKeyStatus = HttpStatusCode.BadGateway
        assertEquals(
            PushOutcome.Unavailable(
                "DMARKET_UNREACHABLE",
                "the key could not be checked: its heartbeat failed: HTTP 502; the held key stays",
            ),
            sdk.putDmarketKey(OTHER_PUBLIC_KEY, SECRET_SEED),
        )
        assertTrue(logLines.any { "\"msg\":\"dmarket key unchecked\"" in it }, "$logLines")
        assertTrue(sdk.codes().isEmpty(), "a key that was never held raises nothing: ${sdk.codes()}")
    }

    @Test
    fun the_log_records_every_push_and_no_secret_reaches_it_or_health() = runTest {
        val sdk = runtime()
        sdk.putDmarketKey(PUBLIC_KEY, SECRET_SEED + PUBLIC_KEY)
        sdk.putSteamToken(steamToken(aud = listOf("web", "renew")))
        sdk.putSteamToken(token)
        sdk.tracker.cycle()
        sdk.putSteamToken(steamToken(sub = OTHER_STEAM_ID))
        otherKeyHeartbeat = """{"activeTracking":[],"ttlSeconds":100,"linkedSteamId":"$OTHER_STEAM_ID"}"""
        val answers = listOf(sdk.putDmarketKey(OTHER_PUBLIC_KEY, SECRET_SEED))
        otherKeyStatus = HttpStatusCode.BadGateway
        val unchecked = answers + sdk.putDmarketKey(OTHER_PUBLIC_KEY, SECRET_SEED)

        for (msg in listOf("dmarket key pushed", "steam token refused", "steam token held", "steam token for another account refused")) {
            assertTrue(logLines.any { "\"msg\":\"$msg\"" in it }, "no \"$msg\" in $logLines")
        }
        assertTrue(logLines.any { "\"msg\":\"event\"" in it }, "the loop's events are logged")
        val payload = token.substringAfter('.').substringBefore('.')
        for (text in logLines + Json.encodeToString(Health.serializer(), sdk.health()) + unchecked.map { it.toString() }) {
            assertTrue(SECRET_SEED !in text, "the secret key leaked: $text")
            assertTrue(payload !in text, "the Steam token leaked: $text")
        }
    }
}
