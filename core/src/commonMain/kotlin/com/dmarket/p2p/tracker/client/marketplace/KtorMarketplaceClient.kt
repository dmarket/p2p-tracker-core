package com.dmarket.p2p.tracker.client.marketplace

import com.dmarket.p2p.tracker.client.HttpStatusException
import com.dmarket.p2p.tracker.client.captureErrorBody
import com.dmarket.p2p.tracker.config.MarketplaceRetryConfig
import com.dmarket.p2p.tracker.engine.ReportAcknowledgement
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.marketplace.Deal
import com.dmarket.p2p.tracker.model.marketplace.DealActionResult
import com.dmarket.p2p.tracker.model.marketplace.DirectiveAck
import com.dmarket.p2p.tracker.model.marketplace.DirectiveOutcome
import com.dmarket.p2p.tracker.model.marketplace.HeartbeatRequest
import com.dmarket.p2p.tracker.model.marketplace.HeartbeatResponse
import com.dmarket.p2p.tracker.model.marketplace.InventoryAck
import com.dmarket.p2p.tracker.model.marketplace.InventoryReport
import com.dmarket.p2p.tracker.model.marketplace.ProofResult
import com.dmarket.p2p.tracker.model.marketplace.ProofSubmission
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusReport
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusResult
import com.dmarket.p2p.tracker.policy.ExponentialBackoff
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceClient
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceServerErrorException
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceUnauthorizedException
import com.dmarket.p2p.tracker.port.marketplace.TradeStatusBatchInterruptedException
import com.dmarket.p2p.tracker.wire.DealActionResponseDto
import com.dmarket.p2p.tracker.wire.DealDto
import com.dmarket.p2p.tracker.wire.HeartbeatResponseDto
import com.dmarket.p2p.tracker.wire.ReportDirectivesResponseDto
import com.dmarket.p2p.tracker.wire.ReportInventoryResponseDto
import com.dmarket.p2p.tracker.wire.ReportTradeStatusResponseDto
import com.dmarket.p2p.tracker.wire.SubmitProofResponseDto
import com.dmarket.p2p.tracker.wire.TrackerJson
import com.dmarket.p2p.tracker.wire.chunkByEncodedSize
import com.dmarket.p2p.tracker.wire.toDomain
import com.dmarket.p2p.tracker.wire.toDto
import com.dmarket.p2p.tracker.wire.toRequestDto
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLPath
import io.ktor.http.fromHttpToGmtDate
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.Instant

/** Thrown on HTTP 429; [retryAfterSeconds] carries the `Retry-After` header when present. */
class RateLimitedException(val retryAfterSeconds: Long?) : RuntimeException("Rate limited (Retry-After=$retryAfterSeconds)")

/**
 * Ktor-backed [MarketplaceClient] for the golden **C1 trade-tracker** endpoints under
 * `/exchange/v1/p2p/ext/`, reached via the exchange-gateway. The deal reads (`accept`/`getDeal`)
 * are the C2 paths under `/exchange/v1/p2p/`.
 *
 * Uses manual JSON encoding via [TrackerJson] (no Ktor `ContentNegotiation` plugin) so the exact wire
 * shape is visible at each call site. Auth is delegated to [authenticator]: the DMarket JWT is fetched
 * per request and sent **raw** in `Authorization` (no `Bearer ` scheme — the live gateway rejects it;
 * see [authorizeAndSet]), and a `401` is handled per its policy. A `429` always surfaces a [RateLimitedException]
 * carrying `Retry-After`. The backend derives the account from the token, so no request sends an
 * `account_id`.
 *
 * [maxReportBodyBytes] caps the body of one `/trade-events` or `/trade-actions` request (`0` = no cap): a longer
 * batch goes out as several requests, in order, stopping at the first that fails. See
 * [com.dmarket.p2p.tracker.config.HttpConfig.maxReportBodyBytes] for why.
 *
 * [onServerDate] hears the `Date` of every reply that carries one, a refusal included, so a host whose requests
 * are signed over its own clock can tell a skewed clock from a refused key. `null` (the default) reads none.
 *
 * A failed heartbeat's body is read for the gateway's machine code (`P2P_…`, at most [ERROR_BODY_CHARS], redacted)
 * and carried as [MarketplaceServerErrorException.errorCode]: a 403 `P2P_STEAM_NOT_LINKED` is the account's to fix,
 * not the gateway's.
 *
 * Audit boundary: no method here accepts a Steam credential — by construction the Steam JWT can never
 * be sent to the marketplace. The client also uses a DMarket-only [HttpClient], physically separate
 * from the Steam reader's transport.
 */
class KtorMarketplaceClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val authenticator: MarketplaceAuthenticator = TransportManagedMarketplaceAuthenticator,
    private val retry: MarketplaceRetryConfig = MarketplaceRetryConfig(),
    private val random: Random = Random.Default,
    private val maxReportBodyBytes: Int = 0,
    private val onServerDate: ((Instant) -> Unit)? = null,
) : MarketplaceClient {

    /** C2 deal reads the host may proxy (`accept`/`getDeal`). */
    private val p2pPath = "/exchange/v1/p2p"

    /** C1 trade-tracker base; ingress prepends `/exchange/v1`, the gateway registers the ext routes at root. */
    private val extPath = "$p2pPath/ext"

    override suspend fun heartbeat(request: HeartbeatRequest): HeartbeatResponse {
        val text = postJson("$baseUrl$extPath/heartbeat", TrackerJson.encodeToString(request.toDto()), readErrorCode = true)
        return TrackerJson.decodeFromString<HeartbeatResponseDto>(text).toDomain()
    }

    /**
     * Over the cap, each request's results are paired with its own reports ([ReportAcknowledgement]) and
     * returned one per report, in order — the pairing a single request would have had, which a concatenation
     * of raw responses could lose. A failure after the first request ends the batch with a
     * [TradeStatusBatchInterruptedException] carrying what was delivered.
     */
    override suspend fun reportTradeStatus(reports: List<TradeStatusReport>): List<TradeStatusResult> {
        val chunks = chunkByEncodedSize(reports, maxReportBodyBytes) { TrackerJson.encodeToString(it.toRequestDto()) }
        if (chunks.size <= 1) return postTradeEvents(reports)
        val results = mutableListOf<TradeStatusResult>()
        for (chunk in chunks) {
            val answer = try {
                postTradeEvents(chunk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (results.isEmpty()) throw e
                throw TradeStatusBatchInterruptedException(results.size, results.toList(), e)
            }
            results += ReportAcknowledgement.match(chunk, answer).map {
                TradeStatusResult(it.report.dealId, it.accepted, it.reason, it.report.source)
            }
        }
        return results
    }

    private suspend fun postTradeEvents(reports: List<TradeStatusReport>): List<TradeStatusResult> {
        val text = postJson("$baseUrl$extPath/trade-events", TrackerJson.encodeToString(reports.toRequestDto()))
        return TrackerJson.decodeFromString<ReportTradeStatusResponseDto>(text).toDomain()
    }

    override suspend fun submitProof(proof: ProofSubmission): ProofResult {
        val text = postJson("$baseUrl$extPath/notary", TrackerJson.encodeToString(proof.toDto()))
        return TrackerJson.decodeFromString<SubmitProofResponseDto>(text).toDomain()
    }

    /**
     * Over the cap, the acks of every request are concatenated: they name their `directive_id`, so they pair
     * the same way whichever request carried them. A failure after the first request ends the batch with the
     * acks so far — the outcomes it never sent have none, which counts as unaccepted and keeps them for a resend.
     */
    override suspend fun reportDirectives(outcomes: List<DirectiveOutcome>): List<DirectiveAck> {
        if (outcomes.isEmpty()) return emptyList()
        val chunks = chunkByEncodedSize(outcomes, maxReportBodyBytes) { TrackerJson.encodeToString(it.toRequestDto()) }
        if (chunks.size == 1) return postTradeActions(outcomes)
        val acks = mutableListOf<DirectiveAck>()
        for ((index, chunk) in chunks.withIndex()) {
            try {
                acks += postTradeActions(chunk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (index == 0) throw e
                break
            }
        }
        return acks
    }

    private suspend fun postTradeActions(outcomes: List<DirectiveOutcome>): List<DirectiveAck> {
        val text = postJson("$baseUrl$extPath/trade-actions", TrackerJson.encodeToString(outcomes.toRequestDto()))
        return TrackerJson.decodeFromString<ReportDirectivesResponseDto>(text).toDomain()
    }

    override suspend fun reportInventory(report: InventoryReport): InventoryAck {
        val text = postJson("$baseUrl$extPath/inventory", TrackerJson.encodeToString(report.toDto()))
        return TrackerJson.decodeFromString<ReportInventoryResponseDto>(text).toDomain()
    }

    override suspend fun acceptDeal(id: DealId): DealActionResult {
        val text = postJson("$baseUrl$p2pPath/deals/${id.value.encodeURLPath()}/accept", "{}")
        return TrackerJson.decodeFromString<DealActionResponseDto>(text).toDomain()
    }

    override suspend fun getDeal(id: DealId): Deal {
        val text = getJson("$baseUrl$p2pPath/deals/${id.value.encodeURLPath()}")
        return TrackerJson.decodeFromString<DealDto>(text).toDomain()
    }

    // ---- private -----------------------------------------------------------------------------------

    /**
     * POST [body] as JSON with bearer auth; transparently handles 401-refresh-and-retry and 429. Every
     * other non-2xx (a deterministic 4xx like 404, or a 5xx) surfaces as [MarketplaceServerErrorException]
     * carrying the status, so the loop can tell a DMarket-side error apart from a transient transport blip.
     */
    private suspend fun postJson(url: String, body: String, readErrorCode: Boolean = false): String = try {
        send(url, body, readErrorCode)
    } catch (e: HttpStatusException) {
        mapStatus(e) { retryOrUnauthorized { send(url, body, readErrorCode) } }
    } catch (e: ResponseException) {
        // Only reachable if this client was built without sanitizeHttpFailures (the mobile host-supplied
        // path). Degrade to the same domain error rather than letting Ktor's URL+body message escape.
        throw MarketplaceServerErrorException(e.response.status.value)
    }

    private suspend fun send(url: String, body: String, readErrorCode: Boolean = false): String {
        val response: HttpResponse = httpClient.post(url) {
            if (readErrorCode) captureErrorBody(ERROR_BODY_CHARS)
            authorizeAndSet(body)
        }
        heardDate(response.headers[HttpHeaders.Date])
        return response.bodyAsText()
    }

    /** Hands [onServerDate] the instant a reply's `Date` names; a missing or malformed one is skipped. */
    private fun heardDate(header: String?) {
        val listener = onServerDate ?: return
        val millis = header?.let { runCatching { it.fromHttpToGmtDate().timestamp }.getOrNull() } ?: return
        listener(Instant.fromEpochMilliseconds(millis))
    }

    /** GET [url] with bearer auth; same 401-refresh-and-retry / 429 / server-error handling as POST. */
    private suspend fun getJson(url: String, block: HttpRequestBuilder.() -> Unit = {}): String = try {
        sendGet(url, block)
    } catch (e: HttpStatusException) {
        mapStatus(e) { retryOrUnauthorized { sendGet(url, block) } }
    } catch (e: ResponseException) {
        throw MarketplaceServerErrorException(e.response.status.value)
    }

    /**
     * The single status → domain-error mapping, shared by POST, GET and the 401-retry path so the three can
     * never drift (they did: `retryOrUnauthorized` used to rethrow a raw Ktor exception, and neither JSON
     * helper caught a 3xx).
     *
     * - `401` → [onUnauthorized] (refresh-and-retry).
     * - `429` → [RateLimitedException]. **Must stay distinct from a server error**: the loop treats a
     *   server error as non-transient unless the status is 5xx, so folding 429 in here would flip the UI to
     *   DM_CONNECTION_ERROR on a single burst of backpressure instead of idling the tick.
     * - `4xx`/`5xx` → [MarketplaceServerErrorException], which the loop debounces by status class.
     * - anything else (i.e. `3xx` — Ktor does not follow a redirect on a POST) → rethrown unchanged, which
     *   lands in the loop's catch-all statusCode-0 path. That path is *debounced*, so an unexpected
     *   gateway redirect stays a transient blip rather than becoming an immediate sticky error. Safe to
     *   rethrow now that the message carries no URL or body.
     */
    private suspend fun mapStatus(e: HttpStatusException, onUnauthorized: suspend () -> String): String {
        heardDate(e.serverDate)
        return when (e.statusCode) {
            HttpStatusCode.Unauthorized.value -> onUnauthorized()
            HttpStatusCode.TooManyRequests.value -> throw RateLimitedException(e.retryAfterSeconds)
            in 400..599 -> throw MarketplaceServerErrorException(e.statusCode, e.errorBody?.let(::gatewayErrorCode))
            else -> throw e
        }
    }

    /**
     * Shared 401 policy: retry [send] up to [MarketplaceRetryConfig.maxRetries] times, re-establishing
     * auth before each attempt and spacing attempts with full-jitter exponential backoff (so a
     * persistent/transient gateway 401 no longer fires back-to-back). Any outcome where auth is still
     * rejected surfaces as [MarketplaceUnauthorizedException] (the loop's "missing connection" signal)
     * rather than a raw Ktor exception:
     * - `refreshOnUnauthorized()` returns `false` (logged out, or the transport already refreshed+failed), or
     * - every retry still returns 401 (the refreshed token is still invalid).
     * A non-401 error from a retry propagates unchanged.
     */
    private suspend fun retryOrUnauthorized(send: suspend () -> String): String {
        repeat(retry.maxRetries) { i ->
            if (!authenticator.refreshOnUnauthorized()) throw MarketplaceUnauthorizedException()
            delay(
                ExponentialBackoff.fullJitterMillis(
                    attempt = i + 1,
                    baseMs = retry.retryBaseDelayMs.toLong(),
                    maxMs = retry.retryMaxDelayMs.toLong(),
                    random = random,
                ),
            )
            try {
                return send()
            } catch (e: HttpStatusException) {
                // Still unauthorized → fall through to the next attempt. Anything else goes through the
                // SAME mapping as a first-attempt failure: previously this rethrew the raw Ktor exception,
                // so a non-401 on a retry both leaked the URL+body and skipped the 429/5xx classification.
                if (e.statusCode != HttpStatusCode.Unauthorized.value) {
                    mapStatus(e) { throw MarketplaceUnauthorizedException() }
                }
            }
        }
        throw MarketplaceUnauthorizedException()
    }

    private suspend fun sendGet(url: String, block: HttpRequestBuilder.() -> Unit): String {
        val response: HttpResponse = httpClient.get(url) {
            block()
            authorize(HttpMethod.Get, body = "")
        }
        heardDate(response.headers[HttpHeaders.Date])
        return response.bodyAsText()
    }

    private suspend fun HttpRequestBuilder.authorizeAndSet(body: String) {
        authorize(HttpMethod.Post, body)
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    /**
     * Attaches [authenticator]'s headers for this exact request. Runs after the URL is final, so a signing
     * authenticator signs the path and query that go on the wire.
     *
     * The default authenticator sends the token RAW (no `Bearer ` scheme): the live exchange-gateway parses
     * the header value as the JWT itself and 401s on a `Bearer `-prefixed value (verified against dev2
     * 2026-07-07; the DMarket FE sends it the same way). Diverges from the golden contract text on purpose.
     * No header at all → sent unauthenticated: the transport authenticates itself (mobile) or the user is
     * logged out (web), in which case the 401 path / re-login signal takes over.
     */
    private suspend fun HttpRequestBuilder.authorize(method: HttpMethod, body: String) {
        val request = MarketplaceRequest(method.value, url.build().encodedPathAndQuery, body)
        authenticator.headersFor(request).forEach { (name, value) -> header(name, value) }
    }

    private companion object {
        /** Enough of a refusal's body for its machine code; the rest is never read. */
        const val ERROR_BODY_CHARS = 512
    }
}

/** The gateway's machine code in a refusal's body (`P2P_STEAM_NOT_LINKED`, …), whatever envelope carries it. */
internal fun gatewayErrorCode(body: String): String? = GATEWAY_ERROR_CODE.find(body)?.value

private val GATEWAY_ERROR_CODE = Regex("\\bP2P_[A-Z0-9_]+\\b")
