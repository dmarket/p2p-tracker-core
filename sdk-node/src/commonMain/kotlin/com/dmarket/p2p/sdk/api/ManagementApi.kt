package com.dmarket.p2p.sdk.api

import com.dmarket.p2p.sdk.health.Health
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One answer of the management API: an HTTP status and a JSON body (empty for `204`). */
data class ApiResponse(val status: Int, val body: String = "")

/** The outcome of a push, as the API reports it. [reason] is ours and never echoes the secret. */
sealed interface PushOutcome {
    data object Held : PushOutcome

    data class Invalid(val error: String, val reason: String) : PushOutcome

    data class Conflict(val error: String, val reason: String) : PushOutcome

    /** The push could not be checked right now; pushing it again may succeed. */
    data class Unavailable(val error: String, val reason: String) : PushOutcome
}

/** What the API acts on. Implemented by the SDK runtime. */
interface ManagementActions {
    suspend fun putDmarketKey(publicKey: String, secretKey: String): PushOutcome

    suspend fun putSteamToken(accessToken: String): PushOutcome

    fun health(): Health
}

/**
 * The local management API, as a pure function of one request: routing, the bearer check and the JSON in and
 * out. The listener that feeds it is the platform's.
 *
 * - `PUT /v1/dmarket/key {publicKey, secretKey}` → `204`; `400 INVALID_KEY` for a malformed pair or one DMarket
 *   refuses; `409 DMARKET_KEY_ACCOUNT_CHANGED` for a key of another DMarket account; `503 DMARKET_UNREACHABLE`
 *   when the key's check heartbeat does not come back, `503 CLOCK_SKEW` when it was refused while this host's
 *   clock is off DMarket's. The held key stays on every refusal.
 * - `PUT /v1/steam/session {accessToken}` → `204`; `400` for a token the SDK will not hold; `409
 *   STEAM_WRONG_ACCOUNT` for a token of another Steam account (the held one stays).
 * - `GET /v1/health` → [Health]: `503` while it is `down` (the SDK counts as absent to DMarket), `200` otherwise.
 *
 * Every route requires `Authorization: Bearer <managementSecret>`; anything else is `401` before the body is
 * read. Both pushes are write-only: no answer, error or log line repeats what was pushed.
 */
class ManagementApi(private val managementSecret: String, private val actions: ManagementActions) {

    suspend fun handle(method: String, path: String, authorization: String?, body: String): ApiResponse {
        if (!authorized(authorization)) return error(401, "UNAUTHORIZED", "a valid bearer is required")
        val route = path.substringBefore('?').trimEnd('/')
        return when (route) {
            KEY_PATH -> if (method == "PUT") putKey(body) else methodNotAllowed()
            STEAM_PATH -> if (method == "PUT") putSteam(body) else methodNotAllowed()
            HEALTH_PATH -> if (method == "GET") health() else methodNotAllowed()
            else -> error(404, "NOT_FOUND", "no such route")
        }
    }

    /** `down` answers 503, so a container health check marks the SDK unhealthy; the body is the same either way. */
    private fun health(): ApiResponse {
        val health = actions.health()
        return ApiResponse(if (health.isDown) 503 else 200, json.encodeToString(Health.serializer(), health))
    }

    private suspend fun putKey(body: String): ApiResponse {
        val request = decode(body, KeyRequest.serializer()) ?: return error(400, "INVALID_BODY", "expected {publicKey, secretKey}")
        return answer(actions.putDmarketKey(request.publicKey, request.secretKey))
    }

    private suspend fun putSteam(body: String): ApiResponse {
        val request = decode(body, SteamRequest.serializer()) ?: return error(400, "INVALID_BODY", "expected {accessToken}")
        return answer(actions.putSteamToken(request.accessToken))
    }

    private fun answer(outcome: PushOutcome): ApiResponse = when (outcome) {
        PushOutcome.Held -> ApiResponse(204)
        is PushOutcome.Invalid -> error(400, outcome.error, outcome.reason)
        is PushOutcome.Conflict -> error(409, outcome.error, outcome.reason)
        is PushOutcome.Unavailable -> error(503, outcome.error, outcome.reason)
    }

    /** Compares in time independent of where the two differ, so the secret cannot be guessed a byte at a time. */
    private fun authorized(header: String?): Boolean {
        val presented = header?.takeIf { it.startsWith(BEARER) }?.substring(BEARER.length) ?: return false
        val expected = managementSecret
        var diff = presented.length xor expected.length
        for (i in expected.indices) diff = diff or (expected[i].code xor (presented.getOrNull(i)?.code ?: 0))
        return diff == 0
    }

    // A decode failure's message can quote the body, which holds the secret being pushed: it is never surfaced.
    private fun <T> decode(body: String, serializer: kotlinx.serialization.KSerializer<T>): T? =
        runCatching { json.decodeFromString(serializer, body) }.getOrNull()

    private fun methodNotAllowed() = error(405, "METHOD_NOT_ALLOWED", "method not allowed on this route")

    private fun error(status: Int, code: String, reason: String) =
        ApiResponse(status, json.encodeToString(ErrorBody.serializer(), ErrorBody(code, reason)))

    @Serializable
    private data class KeyRequest(@SerialName("publicKey") val publicKey: String, @SerialName("secretKey") val secretKey: String) {
        override fun toString(): String = "KeyRequest(publicKey=$publicKey, secretKey=<redacted>)"
    }

    @Serializable
    private data class SteamRequest(@SerialName("accessToken") val accessToken: String) {
        override fun toString(): String = "SteamRequest(accessToken=<redacted>)"
    }

    @Serializable
    private data class ErrorBody(@SerialName("error") val error: String, @SerialName("reason") val reason: String)

    companion object {
        const val KEY_PATH = "/v1/dmarket/key"
        const val STEAM_PATH = "/v1/steam/session"
        const val HEALTH_PATH = "/v1/health"

        /** A push body is a key pair or one token: anything past this is not one. */
        const val MAX_BODY_BYTES = 16 * 1024

        private const val BEARER = "Bearer "
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
