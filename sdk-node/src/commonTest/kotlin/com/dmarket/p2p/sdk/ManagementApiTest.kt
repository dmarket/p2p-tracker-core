package com.dmarket.p2p.sdk

import com.dmarket.p2p.sdk.api.ManagementActions
import com.dmarket.p2p.sdk.api.ManagementApi
import com.dmarket.p2p.sdk.api.PushOutcome
import com.dmarket.p2p.sdk.health.Health
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ManagementApiTest {
    private val secret = "s".repeat(40)
    private val bearer = "Bearer $secret"

    private class RecordingActions(
        var steamOutcome: PushOutcome = PushOutcome.Held,
        var keyOutcome: PushOutcome = PushOutcome.Held,
        var status: String = "ok",
    ) : ManagementActions {
        val keys = mutableListOf<Pair<String, String>>()
        val tokens = mutableListOf<String>()

        override suspend fun putDmarketKey(publicKey: String, secretKey: String): PushOutcome {
            keys += publicKey to secretKey
            return keyOutcome
        }

        override suspend fun putSteamToken(accessToken: String): PushOutcome {
            tokens += accessToken
            return steamOutcome
        }

        override fun health(): Health = Health(status = status, problems = emptyList(), version = "1.2.3")
    }

    @Test
    fun every_route_requires_the_bearer() = runTest {
        val actions = RecordingActions()
        val api = ManagementApi(secret, actions)
        for (auth in listOf(null, "Bearer wrong", secret, "Bearer ${secret}x", "bearer $secret")) {
            assertEquals(401, api.handle("GET", ManagementApi.HEALTH_PATH, auth, "").status, "auth=$auth")
            assertEquals(401, api.handle("PUT", ManagementApi.STEAM_PATH, auth, """{"accessToken":"t"}""").status)
        }
        assertTrue(actions.tokens.isEmpty(), "an unauthorized push never reaches the SDK")
    }

    @Test
    fun a_key_push_is_accepted_without_echoing_it() = runTest {
        val actions = RecordingActions()
        val response = ManagementApi(
            secret,
            actions,
        ).handle("PUT", ManagementApi.KEY_PATH, bearer, """{"publicKey":"pub","secretKey":"sec"}""")
        assertEquals(204, response.status)
        assertEquals("", response.body)
        assertEquals(listOf("pub" to "sec"), actions.keys)
    }

    @Test
    fun a_token_for_another_account_is_a_conflict() = runTest {
        val actions = RecordingActions(PushOutcome.Conflict("STEAM_WRONG_ACCOUNT", "the token is for 2, not 1"))
        val response = ManagementApi(secret, actions).handle("PUT", ManagementApi.STEAM_PATH, bearer, """{"accessToken":"eyJsecret"}""")
        assertEquals(409, response.status)
        assertTrue("STEAM_WRONG_ACCOUNT" in response.body)
        assertTrue("eyJsecret" !in response.body)
    }

    @Test
    fun a_key_for_another_account_is_a_conflict_and_an_unchecked_one_is_unavailable() = runTest {
        val body = """{"publicKey":"pk","secretKey":"sk-secret"}"""
        val actions = RecordingActions(keyOutcome = PushOutcome.Conflict("DMARKET_KEY_ACCOUNT_CHANGED", "another account"))
        val api = ManagementApi(secret, actions)
        val conflict = api.handle("PUT", ManagementApi.KEY_PATH, bearer, body)
        assertEquals(409, conflict.status)
        assertTrue("DMARKET_KEY_ACCOUNT_CHANGED" in conflict.body, conflict.body)

        actions.keyOutcome = PushOutcome.Unavailable("DMARKET_UNREACHABLE", "the key could not be checked")
        val unavailable = api.handle("PUT", ManagementApi.KEY_PATH, bearer, body)
        assertEquals(503, unavailable.status)
        assertTrue("DMARKET_UNREACHABLE" in unavailable.body, unavailable.body)
        assertTrue("sk-secret" !in conflict.body + unavailable.body)
    }

    @Test
    fun a_malformed_body_is_refused_without_quoting_it() = runTest {
        val api = ManagementApi(secret, RecordingActions())
        val response = api.handle("PUT", ManagementApi.KEY_PATH, bearer, """{"publicKey":"pub","secretKey":"leaked-secret"""")
        assertEquals(400, response.status)
        assertTrue("leaked-secret" !in response.body, response.body)
    }

    @Test
    fun health_answers_503_while_down_and_200_otherwise() = runTest {
        val actions = RecordingActions(status = "down")
        val api = ManagementApi(secret, actions)
        val down = api.handle("GET", ManagementApi.HEALTH_PATH, bearer, "")
        assertEquals(503, down.status)
        assertTrue(""""status":"down"""" in down.body, down.body)
        for (status in listOf("degraded", "ok")) {
            actions.status = status
            assertEquals(200, api.handle("GET", ManagementApi.HEALTH_PATH, bearer, "").status, status)
        }
    }

    @Test
    fun health_is_served_as_json_and_unknown_routes_and_methods_are_refused() = runTest {
        val api = ManagementApi(secret, RecordingActions())
        val health = api.handle("GET", "${ManagementApi.HEALTH_PATH}?verbose=1", bearer, "")
        assertEquals(200, health.status)
        assertTrue(""""status":"ok"""" in health.body && """"version":"1.2.3"""" in health.body, health.body)

        assertEquals(404, api.handle("GET", "/v1/deals", bearer, "").status)
        assertEquals(405, api.handle("GET", ManagementApi.KEY_PATH, bearer, "").status, "the key is write-only")
        assertEquals(405, api.handle("POST", ManagementApi.HEALTH_PATH, bearer, "").status)
    }
}
