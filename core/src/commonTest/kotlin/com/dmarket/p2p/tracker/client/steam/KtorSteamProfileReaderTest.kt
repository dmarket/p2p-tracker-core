package com.dmarket.p2p.tracker.client.steam

import com.dmarket.p2p.tracker.client.createHttpClient
import com.dmarket.p2p.tracker.config.SteamEndpointsConfig
import com.dmarket.p2p.tracker.config.SteamProfileConfig
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.steam.InvalidSteamId64Exception
import com.dmarket.p2p.tracker.port.steam.SteamProfileAuthException
import com.dmarket.p2p.tracker.port.steam.UserNotFoundException
import com.dmarket.p2p.tracker.support.fakeSteamCredential
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KtorSteamProfileReaderTest {

    private val credential = fakeSteamCredential()
    private fun id(n: Int) = SteamId("76561198" + (100_000_000 + n)) // 8 + 9 = 17 digits, "7656…"

    // ---- response body helpers ---------------------------------------------------------------------

    private data class Resp(val body: String, val status: HttpStatusCode = HttpStatusCode.OK, val retryAfter: String? = null)

    private fun playersBody(ids: List<SteamId>) = playersBodyFrom(ids.joinToString(",") { it.value })

    private fun playersBodyFrom(steamIds: String): String {
        val arr = steamIds.split(",").filter { it.isNotBlank() }.joinToString(",") { sid ->
            """{"steamid":"$sid","personaname":"nick-$sid","avatar":"a-$sid","avatarmedium":"m-$sid","avatarfull":"f-$sid"}"""
        }
        return """{"response":{"players":[$arr]}}"""
    }

    private fun levelBody(level: Int?) = if (level == null) """{"response":{}}""" else """{"response":{"player_level":$level}}"""

    // ---- URL-aware recording mock ------------------------------------------------------------------

    private inner class SteamMock(
        val endpoints: SteamEndpointsConfig = SteamEndpointsConfig(),
        val config: SteamProfileConfig = SteamProfileConfig(),
        val onSummaries: (steamIds: String, call: Int) -> Resp = { ids, _ -> Resp(playersBodyFrom(ids)) },
        val onLevel: (steamId: String) -> Resp = { Resp(levelBody(1)) },
    ) {
        val summariesSteamIds = mutableListOf<String>()
        val levelSteamIds = mutableListOf<String>()
        val accessTokens = mutableListOf<String>()

        val reader: KtorSteamProfileReader

        init {
            val engine = MockEngine { request ->
                val params = request.url.parameters
                params[endpoints.paramAccessToken]?.let { accessTokens += it }
                val r = when (request.url.encodedPath) {
                    endpoints.getPlayerSummariesPath -> {
                        val ids = params[endpoints.paramSteamIds].orEmpty()
                        val call = summariesSteamIds.size
                        summariesSteamIds += ids
                        onSummaries(ids, call)
                    }
                    endpoints.getSteamLevelPath -> {
                        val sid = params[endpoints.paramSteamId].orEmpty()
                        levelSteamIds += sid
                        onLevel(sid)
                    }
                    else -> Resp("{}")
                }
                respond(content = r.body, status = r.status, headers = headersFor(r))
            }
            reader = KtorSteamProfileReader(createHttpClient(engine), endpoints, config, Random(0))
        }
    }

    private fun headersFor(r: Resp): Headers = HeadersBuilder().apply {
        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        if (r.retryAfter != null) append(HttpHeaders.RetryAfter, r.retryAfter)
    }.build()

    // ---- happy paths -------------------------------------------------------------------------------

    @Test
    fun single_profile_maps_all_fields() = runTest {
        val mock = SteamMock(
            onSummaries = { _, _ -> Resp(playersBody(listOf(id(1)))) },
            onLevel = { Resp(levelBody(42)) },
        )
        val p = mock.reader.getUserProfile(credential, id(1))
        assertEquals(id(1), p.steamId64)
        assertEquals("nick-${id(1).value}", p.nickname)
        assertEquals("a-${id(1).value}", p.avatarSmallUrl)
        assertEquals("m-${id(1).value}", p.avatarMediumUrl)
        assertEquals("f-${id(1).value}", p.avatarFullUrl)
        assertEquals(42, p.level)
        assertTrue(mock.accessTokens.all { it == credential.token }, "every call must forward the access_token")
    }

    @Test
    fun batch_sends_one_comma_joined_summaries_call_and_preserves_order() = runTest {
        val mock = SteamMock(onLevel = { sid -> Resp(levelBody(sid.takeLast(1).toInt())) })
        val profiles = mock.reader.getUserProfiles(credential, listOf(id(1), id(2), id(3)))

        assertEquals(listOf(id(1), id(2), id(3)), profiles.map { it.steamId64 })
        assertEquals(1, mock.summariesSteamIds.size, "3 ids fit one batch")
        assertEquals("${id(1).value},${id(2).value},${id(3).value}", mock.summariesSteamIds.single())
        assertEquals(setOf(id(1).value, id(2).value, id(3).value), mock.levelSteamIds.toSet())
    }

    @Test
    fun private_profile_still_returns_identity_with_null_level() = runTest {
        val mock = SteamMock(
            onSummaries = { _, _ -> Resp(playersBody(listOf(id(1)))) },
            onLevel = { Resp(levelBody(null)) }, // {"response":{}} — private
        )
        val p = mock.reader.getUserProfile(credential, id(1))
        assertEquals("nick-${id(1).value}", p.nickname)
        assertNull(p.level)
    }

    // ---- not-found & partial -----------------------------------------------------------------------

    @Test
    fun single_unknown_id_throws_user_not_found() = runTest {
        val mock = SteamMock(onSummaries = { _, _ -> Resp("""{"response":{"players":[]}}""") })
        assertFailsWith<UserNotFoundException> { mock.reader.getUserProfile(credential, id(1)) }
    }

    @Test
    fun batch_omits_ids_steam_does_not_return() = runTest {
        val mock = SteamMock(onSummaries = { _, _ -> Resp(playersBody(listOf(id(1)))) }) // only id(1) comes back
        val profiles = mock.reader.getUserProfiles(credential, listOf(id(1), id(2)))
        assertEquals(listOf(id(1)), profiles.map { it.steamId64 })
    }

    @Test
    fun a_failing_level_call_degrades_that_profiles_level_to_null() = runTest {
        val mock = SteamMock(
            onSummaries = { _, _ -> Resp(playersBody(listOf(id(1), id(2)))) },
            onLevel = { sid ->
                if (sid == id(2).value) Resp("boom", HttpStatusCode.InternalServerError) else Resp(levelBody(10))
            },
        )
        val byId = mock.reader.getUserProfiles(credential, listOf(id(1), id(2))).associate { it.steamId64 to it.level }
        assertEquals(mapOf(id(1) to 10, id(2) to null), byId)
    }

    // ---- validation --------------------------------------------------------------------------------

    @Test
    fun malformed_id_is_rejected_before_any_request() = runTest {
        val mock = SteamMock()
        assertFailsWith<InvalidSteamId64Exception> { mock.reader.getUserProfile(credential, SteamId("123")) }
        assertTrue(mock.summariesSteamIds.isEmpty(), "validation must precede the network call")
    }

    // ---- rate limiting -----------------------------------------------------------------------------

    @Test
    fun rate_limited_then_succeeds_after_retry() = runTest {
        val mock = SteamMock(
            onSummaries = { ids, call ->
                if (call == 0) Resp("rl", HttpStatusCode.TooManyRequests, retryAfter = "1") else Resp(playersBodyFrom(ids))
            },
        )
        val p = mock.reader.getUserProfile(credential, id(1))
        assertEquals(id(1), p.steamId64)
        assertEquals(2, mock.summariesSteamIds.size, "one 429 then one success = 2 attempts")
    }

    @Test
    fun rate_limited_beyond_budget_throws_with_retry_after() = runTest {
        val mock = SteamMock(
            config = SteamProfileConfig(maxRetries = 3),
            onSummaries = { _, _ -> Resp("rl", HttpStatusCode.TooManyRequests, retryAfter = "2") },
        )
        val ex = assertFailsWith<SteamRateLimitedException> { mock.reader.getUserProfile(credential, id(1)) }
        assertEquals(2L, ex.retryAfterSeconds)
        assertEquals(3, mock.summariesSteamIds.size, "attempts capped at maxRetries")
    }

    @Test
    fun forbidden_maps_to_auth_exception_without_retry() = runTest {
        val mock = SteamMock(onSummaries = { _, _ -> Resp("nope", HttpStatusCode.Forbidden) })
        assertFailsWith<SteamProfileAuthException> { mock.reader.getUserProfile(credential, id(1)) }
        assertEquals(1, mock.summariesSteamIds.size, "403 is not retried")
    }

    // ---- chunking ----------------------------------------------------------------------------------

    @Test
    fun more_than_batch_size_ids_are_split_into_multiple_summaries_calls() = runTest {
        val ids = (1..150).map(::id)
        val mock = SteamMock() // default onSummaries echoes requested ids back as players
        val profiles = mock.reader.getUserProfiles(credential, ids)

        assertEquals(150, profiles.size)
        assertEquals(2, mock.summariesSteamIds.size, "150 ids → 2 chunks")
        val chunkSizes = mock.summariesSteamIds.map { it.split(",").size }.sortedDescending()
        assertEquals(listOf(100, 50), chunkSizes)
    }
}
