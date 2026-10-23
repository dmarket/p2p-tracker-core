package com.dmarket.p2p.headless

import com.dmarket.p2p.headless.node.LoopbackServer
import com.dmarket.p2p.headless.node.tempDir
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

/**
 * The facade as a host sees it: what it accepts, the plain objects it answers with, and that none of them, nor
 * the handle, reaches a secret. The shapes asserted here are the ones the package's TypeScript types describe.
 */
class HeadlessFacadeTest {
    private val host = TestHost()
    private val token = steamToken()

    private fun json(value: dynamic): String = js("JSON.stringify")(value) as String

    /** Everything a host could print, checked for both secrets. */
    private fun assertNoSecret(vararg texts: String) {
        for (text in texts) {
            assertTrue(SECRET_SEED !in text && OTHER_SECRET_SEED !in text, "a secret key leaked: $text")
            assertTrue(token.jwtPayload() !in text, "the Steam token leaked: $text")
        }
    }

    private suspend fun <T> Promise<T>.rejection(): Throwable {
        try {
            await()
        } catch (e: Throwable) {
            return e
        }
        fail("expected a rejection")
    }

    private fun Throwable.texts(): Array<String> = arrayOf(message.orEmpty(), asDynamic().stack as? String ?: "")

    @Test
    fun options_are_refused_by_name_without_quoting_a_value() = runTest {
        val dir = tempDir()
        val secret = "sk-$SECRET_SEED"
        val cases = listOf<Pair<String, (dynamic) -> Unit>>(
            "unknown option: secretKey" to { o -> o.secretKey = secret },
            "unknown option: (a key that is not an option name)" to { o -> o[token] = true },
            "dataDir must be a non-empty string" to { o -> o.dataDir = "" },
            "steamWrites must be a boolean" to { o -> o.steamWrites = secret },
            "baseUrl must be an http:// or https:// URL" to { o -> o.baseUrl = "ftp://$secret" },
            "notaryUrl must be a ws:// or wss:// URL" to { o -> o.notaryUrl = "https://$secret" },
            "onEvent must be a function" to { o -> o.onEvent = secret },
            "nowMs must be a function" to { o -> o.nowMs = 5 },
        )
        for ((message, spoil) in cases) {
            val options = host.options(dir)
            spoil(options)
            val error = assertFailsWith<IllegalArgumentException>(message) { createHeadlessTracker(options) }
            assertEquals(message, error.message)
            assertNoSecret(*error.texts())
            assertTrue(secret !in error.texts().joinToString(), "the value was quoted: ${error.message}")
        }
        assertEquals("options must be an object", assertFailsWith<IllegalArgumentException> { createHeadlessTracker(null) }.message)
        assertFailsWith<IllegalArgumentException>("dataDir is required") {
            createHeadlessTracker(js("({})"))
        }
    }

    @Test
    fun the_handle_is_an_empty_frozen_object_that_reaches_no_secret() = runTest {
        val handle = host.tracker()
        pushTradingApiKey(handle, PUBLIC_KEY, SECRET_SEED).await()
        pushSteamToken(handle, token).await()
        cycle(handle).await()
        assertEquals(true, trackerState(handle).hasTradingApiKey, "both secrets are held")

        val shown = inspect(handle, js("({depth: null, showHidden: true})"))
        assertEquals("[Object: null prototype] {}", shown)
        assertEquals("{}", json(handle))
        assertEquals(0, (js("Object.getOwnPropertyNames")(handle) as Array<String>).size)
        assertEquals(0, (js("Object.getOwnPropertySymbols")(handle) as Array<Any>).size)
        assertEquals(null, js("Object.getPrototypeOf")(handle))
        assertTrue(js("Object.isFrozen")(handle) as Boolean)
        assertNoSecret(shown, json(trackerState(handle)), *host.events.toTypedArray())
        assertTrue(host.events.isNotEmpty(), "the cycle's events reached the host")
    }

    @Test
    fun a_key_push_answers_a_plain_object_per_outcome() = runTest {
        val handle = host.tracker()
        val answers = mutableListOf<dynamic>()
        suspend fun push(public: String, secret: String): dynamic {
            val answer = pushTradingApiKey(handle, public, secret).await()
            answers.add(answer)
            return answer
        }

        val malformed = push(PUBLIC_KEY, "${SECRET_SEED}zz")
        assertEquals(mapOf("reason" to "string", "type" to "string"), shapeOf(malformed))
        assertEquals("invalid", malformed.type)
        assertEquals("expected a hex Ed25519 key pair: a 32-byte public key and its secret", malformed.reason)

        val held = push(PUBLIC_KEY, SECRET_SEED)
        assertEquals(mapOf("change" to "string", "publicKey" to "string", "type" to "string"), shapeOf(held))
        assertEquals(listOf("held", PUBLIC_KEY, "FIRST"), listOf(held.type, held.publicKey, held.change))
        assertEquals("SAME", push(PUBLIC_KEY, SECRET_SEED).change)

        pushSteamToken(handle, token).await()
        cycle(handle).await()
        assertEquals("ROTATED", push(OTHER_PUBLIC_KEY, OTHER_SECRET_SEED).change, "its heartbeat names the bound account")
        assertEquals("ROTATED", push(PUBLIC_KEY, SECRET_SEED).change)

        host.backend.otherKeyHeartbeat = """{"activeTracking":[],"ttlSeconds":100,"linkedSteamId":"$OTHER_STEAM_ID"}"""
        val changed = push(OTHER_PUBLIC_KEY, OTHER_SECRET_SEED)
        assertEquals(
            mapOf(
                "heldPublicKey" to "string",
                "keyLinkedSteamId" to "string",
                "linkedSteamId" to "string",
                "publicKey" to "string",
                "type" to "string",
            ),
            shapeOf(changed),
        )
        assertEquals(
            listOf("accountChanged", OTHER_PUBLIC_KEY, PUBLIC_KEY, STEAM_ID, OTHER_STEAM_ID),
            listOf(changed.type, changed.publicKey, changed.heldPublicKey, changed.linkedSteamId, changed.keyLinkedSteamId),
        )

        host.backend.otherKeyStatus = HttpStatusCode.Forbidden
        host.backend.otherKeyHeartbeat = """{"code":"P2P_STEAM_NOT_LINKED"}"""
        val unlinked = push(OTHER_PUBLIC_KEY, OTHER_SECRET_SEED)
        assertEquals("accountChanged", unlinked.type)
        assertEquals("null", kindOf(unlinked.keyLinkedSteamId), "an absent account is null, not undefined")

        host.backend.otherKeyStatus = HttpStatusCode.BadGateway
        host.backend.otherKeyHeartbeat = null
        val unchecked = push(OTHER_PUBLIC_KEY, OTHER_SECRET_SEED)
        assertEquals(mapOf("publicKey" to "string", "reason" to "string", "type" to "string"), shapeOf(unchecked))
        assertEquals(
            listOf("unverified", OTHER_PUBLIC_KEY, "its heartbeat failed: HTTP 502"),
            listOf(unchecked.type, unchecked.publicKey, unchecked.reason),
        )

        assertNoSecret(*answers.map { json(it) }.toTypedArray(), *host.events.toTypedArray())
    }

    @Test
    fun a_token_push_answers_a_plain_object_per_outcome() = runTest {
        val handle = host.tracker()
        val refresh = steamToken(aud = listOf("web", "renew"))
        val refused = pushSteamToken(handle, refresh).await()
        assertEquals(mapOf("reason" to "string", "type" to "string"), shapeOf(refused))
        assertEquals(listOf("invalid", "a refresh token is never accepted"), listOf(refused.type, refused.reason))
        assertTrue(refresh.jwtPayload() !in json(refused))

        val broken = pushSteamToken(handle, "eyJ.${token.jwtPayload()}x.!!").await()
        assertEquals("invalid", broken.type)
        assertTrue(token.jwtPayload() !in json(broken), json(broken))

        val held = pushSteamToken(handle, steamToken(exp = T0 + 3.hours)).await()
        assertEquals(mapOf("expiresAtMs" to "number", "steamId" to "string", "type" to "string"), shapeOf(held))
        assertEquals(listOf("held", STEAM_ID), listOf(held.type, held.steamId))
        assertEquals((T0 + 3.hours).toEpochMilliseconds().toDouble(), held.expiresAtMs)

        val other = pushSteamToken(handle, steamToken(sub = OTHER_STEAM_ID)).await()
        assertEquals(mapOf("linkedSteamId" to "null", "tokenSteamId" to "string", "type" to "string"), shapeOf(other))
        assertEquals(listOf("wrongAccount", OTHER_STEAM_ID), listOf(other.type, other.tokenSteamId))

        pushTradingApiKey(handle, PUBLIC_KEY, SECRET_SEED).await()
        cycle(handle).await()
        val named = pushSteamToken(handle, steamToken(sub = OTHER_STEAM_ID)).await()
        assertEquals(STEAM_ID, named.linkedSteamId, "once DMarket names the account, the refusal names it too")
    }

    @Test
    fun the_state_has_the_documented_shape_with_null_for_nothing() = runTest {
        val handle = host.tracker()
        val fresh = trackerState(handle)
        val expected = mapOf(
            "clockOffsetMs" to "null",
            "clockSkewed" to "boolean",
            "dealDeadlines" to "object",
            "hasTradingApiKey" to "boolean",
            "linkedSteamId" to "null",
            "readySinceMs" to "null",
            "steamNotLinked" to "boolean",
            "steamTokenExpiresAtMs" to "null",
            "steamTokenSteamId" to "null",
            "trackedDealIds" to "array",
            "updateDueTimeMs" to "null",
            "updateRequired" to "boolean",
        )
        assertEquals(expected, shapeOf(fresh))
        assertEquals("{}", json(fresh.dealDeadlines))

        host.backend.heartbeatBody = """
            {"activeTracking":[{"dealId":"deal-1","watch":["GetTradeOffer"],"deadlineTime":"2026-06-16T13:00:00Z"}],
             "ttlSeconds":100,"linkedSteamId":"$STEAM_ID","updateRequired":true,"updateDueTime":"2026-07-01T00:00:00Z"}
        """.trimIndent()
        pushTradingApiKey(handle, PUBLIC_KEY, SECRET_SEED).await()
        pushSteamToken(handle, steamToken(exp = T0 + 3.hours)).await()
        cycle(handle).await()

        val state = trackerState(handle)
        assertEquals(
            expected + mapOf(
                "linkedSteamId" to "string",
                "readySinceMs" to "number",
                "steamTokenExpiresAtMs" to "number",
                "steamTokenSteamId" to "string",
                "updateDueTimeMs" to "number",
            ),
            shapeOf(state),
            "the mock sends no Date, so the clock offset stays unknown",
        )
        assertEquals((T0 + 3.hours).toEpochMilliseconds().toDouble(), state.steamTokenExpiresAtMs, "the token's exp")
        assertEquals(T0.toEpochMilliseconds().toDouble(), state.readySinceMs)
        assertEquals(listOf(STEAM_ID, STEAM_ID), listOf(state.steamTokenSteamId, state.linkedSteamId))
        assertEquals(listOf("deal-1"), (state.trackedDealIds as Array<String>).toList())
        assertEquals("""{"deal-1":${(T0 + 1.hours).toEpochMilliseconds()}}""", json(state.dealDeadlines))
        assertEquals(true, state.updateRequired)
        assertEquals(1_782_864_000_000.0, state.updateDueTimeMs)
    }

    @Test
    fun a_stopped_tracker_refuses_every_call_and_leaves_nothing_unhandled() = runTest {
        val unhandled = mutableListOf<Any?>()
        val listener = { reason: Any? -> unhandled += reason }
        val process: dynamic = js("process")
        process.on("unhandledRejection", listener)
        try {
            // On the handle's own scope: a push's cycle is in flight when the tracker stops.
            val handle = createHeadlessTrackerForTest(host.options(tempDir()), host.backend.engine)
            pushTradingApiKey(handle, PUBLIC_KEY, SECRET_SEED).await()
            pushSteamToken(handle, token).await()
            stop(handle).await()
            stop(handle).await()

            assertEquals("tracker stopped", cycle(handle).rejection().message)
            assertEquals("tracker stopped", pushSteamToken(handle, token).rejection().message)
            assertEquals("tracker stopped", assertFailsWith<IllegalStateException> { trackerState(handle) }.message)
            assertEquals("tracker stopped", assertFailsWith<IllegalStateException> { start(handle) }.message)

            val stranger: Any = js("({})")
            assertEquals("unknown tracker handle", cycle(stranger).rejection().message)
            assertEquals("unknown tracker handle", stop(stranger).rejection().message)
            assertEquals("unknown tracker handle", assertFailsWith<IllegalArgumentException> { trackerState(stranger) }.message)

            val requests = host.backend.requested.size
            withContext(Dispatchers.Default) { delay(200) }
            assertEquals(requests, host.backend.requested.size, "nothing runs after stop")
            assertEquals(emptyList(), unhandled)
        } finally {
            process.off("unhandledRejection", listener)
        }
    }

    @Test
    fun a_wrongly_typed_push_is_refused_without_quoting_it() = runTest {
        val handle = host.tracker()
        val number: dynamic = 42
        val error = pushTradingApiKey(handle, PUBLIC_KEY, number.unsafeCast<String>()).rejection()
        assertEquals("publicKey and secretKey must be strings", error.message)
        assertEquals("accessToken must be a string", pushSteamToken(handle, number.unsafeCast<String>()).rejection().message)
    }

    @Test
    fun a_loopback_gateway_is_accepted_and_reached_through_the_platform_engine() = runTest {
        val server = LoopbackServer.start {
            200 to """{"activeTracking":[],"directives":[],"ttlSeconds":100,"linkedSteamId":"$STEAM_ID"}"""
        }
        val options: dynamic = js("({})")
        options.dataDir = tempDir()
        options.baseUrl = server.base
        val handle = createHeadlessTracker(options)
        try {
            val live = steamToken(exp = Clock.System.now() + 24.hours)
            assertEquals("held", pushTradingApiKey(handle, PUBLIC_KEY, SECRET_SEED).await().type)
            assertEquals("held", pushSteamToken(handle, live).await().type)
            cycle(handle).await()

            val heartbeat = server.seen.first { it.url == "/exchange/v1/p2p/ext/heartbeat" }
            assertEquals("POST", heartbeat.method)
            assertEquals(PUBLIC_KEY, heartbeat.headers["x-api-key"] as String?)
            assertEquals(STEAM_ID, trackerState(handle).linkedSteamId)
            assertTrue(server.seen.none { live.jwtPayload() in it.body || SECRET_SEED in it.body }, "no secret goes to DMarket")
        } finally {
            stop(handle).await()
            server.close()
        }
    }
}
