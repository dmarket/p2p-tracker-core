package com.dmarket.p2p.tracker.client.steam

import com.dmarket.p2p.tracker.client.HttpStatusException
import com.dmarket.p2p.tracker.config.SteamEndpointsConfig
import com.dmarket.p2p.tracker.config.SteamProfileConfig
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.steam.InvalidSteamId64Exception
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.model.steam.SteamProfile
import com.dmarket.p2p.tracker.model.steam.isValidSteamId64
import com.dmarket.p2p.tracker.policy.ExponentialBackoff
import com.dmarket.p2p.tracker.port.steam.SteamProfileAuthException
import com.dmarket.p2p.tracker.port.steam.SteamProfileReader
import com.dmarket.p2p.tracker.port.steam.UserNotFoundException
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Ktor-backed [SteamProfileReader]: reads public profiles from `ISteamUser/GetPlayerSummaries/v2`
 * (nickname + avatars, batched ≤[SteamProfileConfig.batchSize] ids/call) and
 * `IPlayerService/GetSteamLevel/v1` (level, one id/call, parallel & concurrency-capped). Auth is the
 * device session token as the `access_token` query param (the [KtorSteamReadClient] convention), not
 * an Authorization header and not a classic `key=` Web API key.
 *
 * **Rate limits:** a 429 is retried in-call with exponential backoff + full jitter (honouring
 * `Retry-After`), up to [SteamProfileConfig.maxRetries] total attempts, then surfaced as
 * [SteamRateLimitedException]. Unlike the loop-driven [KtorSteamReadClient] (whose caller owns the
 * retry cadence), this is a standalone request/response API with no owning loop, so the retry
 * lives here. A 403 maps to [SteamProfileAuthException]; other non-2xx propagate as
 * [HttpStatusException].
 *
 * Not yet confirmed against live Steam that these endpoints accept the web-session `access_token`
 * (support varies per endpoint) or that they rate-limit with HTTP 429 + `Retry-After` — unit-tested
 * but pending real-world verification, matching the caveat on the other Steam clients.
 *
 * @param random injectable so backoff jitter is deterministic under test; defaults to [Random.Default].
 */
class KtorSteamProfileReader(
    private val httpClient: HttpClient,
    private val endpoints: SteamEndpointsConfig = SteamEndpointsConfig(),
    private val config: SteamProfileConfig = SteamProfileConfig(),
    private val random: Random = Random.Default,
) : SteamProfileReader {

    override suspend fun getUserProfile(credential: SteamCredential, steamId64: SteamId): SteamProfile {
        validate(steamId64)
        return getUserProfiles(credential, listOf(steamId64)).firstOrNull()
            ?: throw UserNotFoundException(steamId64.value)
    }

    override suspend fun getUserProfiles(credential: SteamCredential, steamId64s: List<SteamId>): List<SteamProfile> {
        steamId64s.forEach(::validate) // fail fast, before any network call
        return SteamProfileStrategy.resolve(
            ids = steamId64s,
            batchSize = config.batchSize,
            maxConcurrency = config.maxConcurrency,
            fetchSummaries = { chunk -> fetchSummaries(credential, chunk) },
            fetchLevel = { id -> fetchLevel(credential, id) },
        )
    }

    // ---- private -----------------------------------------------------------------------------------

    private fun validate(id: SteamId) {
        if (!id.isValidSteamId64()) throw InvalidSteamId64Exception(id.value)
    }

    /** One `GetPlayerSummaries` call for a chunk of ≤100 ids → the returned players keyed by id. */
    private suspend fun fetchSummaries(credential: SteamCredential, chunk: List<SteamId>) = withRateLimitRetry {
        val responseText = httpClient.get("${endpoints.steamApiBaseUrl}${endpoints.getPlayerSummariesPath}") {
            parameter(endpoints.paramAccessToken, credential.token)
            parameter(endpoints.paramSteamIds, chunk.joinToString(",") { it.value })
        }.bodyAsText()
        SteamProfileResponses.players(responseText)
    }

    /** One `GetSteamLevel` call for a single id → its level, or `null` for a private profile. */
    private suspend fun fetchLevel(credential: SteamCredential, id: SteamId): Int? = withRateLimitRetry {
        val responseText = httpClient.get("${endpoints.steamApiBaseUrl}${endpoints.getSteamLevelPath}") {
            parameter(endpoints.paramAccessToken, credential.token)
            parameter(endpoints.paramSteamId, id.value)
        }.bodyAsText()
        SteamProfileResponses.level(responseText)
    }

    /**
     * Runs [block], retrying HTTP 429 with exponential backoff + full jitter up to [config].maxRetries
     * total attempts. Maps 403 → [SteamProfileAuthException]; rethrows any other error.
     */
    private suspend fun <T> withRateLimitRetry(block: suspend () -> T): T {
        var attempt = 1
        while (true) {
            try {
                return block()
            } catch (e: HttpStatusException) {
                val retryAfter = e.retryAfterSeconds
                when (e.statusCode) {
                    HttpStatusCode.Forbidden.value -> throw SteamProfileAuthException(e)
                    HttpStatusCode.TooManyRequests.value -> {
                        if (attempt >= config.maxRetries) throw SteamRateLimitedException(retryAfter)
                        delay(backoffMillis(attempt, retryAfter))
                        attempt++
                    }
                    else -> throw e
                }
            }
        }
    }

    /** Full-jitter backoff: `random[0, min(base·2^(attempt-1), cap)]`, floored by `Retry-After`. */
    private fun backoffMillis(attempt: Int, retryAfterSeconds: Long?): Long = ExponentialBackoff.fullJitterMillis(
        attempt = attempt,
        baseMs = config.retryBaseDelayMs.toLong(),
        maxMs = config.retryMaxDelayMs.toLong(),
        random = random,
        retryAfterMs = (retryAfterSeconds ?: 0L) * 1_000L,
    )
}
