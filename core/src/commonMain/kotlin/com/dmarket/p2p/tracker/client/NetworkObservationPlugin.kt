package com.dmarket.p2p.tracker.client

import com.dmarket.p2p.tracker.adapter.host.NoOpNetworkObserver
import com.dmarket.p2p.tracker.model.ExchangeOrigin
import com.dmarket.p2p.tracker.model.NetworkExchange
import com.dmarket.p2p.tracker.net.NetworkRedaction
import com.dmarket.p2p.tracker.port.host.NetworkObserver
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.bodyAsText
import io.ktor.http.content.OutgoingContent
import io.ktor.util.AttributeKey
import kotlin.time.Clock
import kotlin.time.TimeSource

/** Configuration for [NetworkObservation]. */
class NetworkObservationConfig {
    var observer: NetworkObserver = NoOpNetworkObserver
    var origin: ExchangeOrigin = ExchangeOrigin.MARKETPLACE
    var secretParamNames: Set<String> = NetworkRedaction.DEFAULT_SECRET_PARAM_NAMES
    var maxBodyLen: Int = 4096
}

/** Snapshot captured at request time, recovered at response time to build one [NetworkExchange]. */
private class ExchangeStart(
    val method: String,
    val redactedUrl: String,
    val redactedHeaders: Map<String, String>,
    val redactedRequestBody: String?,
    val suppressResponseBody: Boolean,
    val startedAtEpochMs: Long,
    val mark: TimeSource.Monotonic.ValueTimeMark,
)

private val ExchangeStartKey = AttributeKey<ExchangeStart>("NetworkObservationStart")

/**
 * Marker attribute: when present on a request, the [NetworkObservation] plugin records the exchange
 * (method/url/status/timing + the redacted request body) but **omits the response body** from the
 * observed [NetworkExchange]. Use it for Steam responses that carry a secret redaction cannot be
 * relied on to catch — the loyalty-token HTML (session scrape) and the session-refresh `ajaxrefresh`
 * nonce. Set it via [suppressResponseBodyCapture]. (Request bodies are still captured and redacted —
 * the `settoken` form's nonce/auth are scrubbed by [NetworkRedaction], matching the pre-Ktor path.)
 */
val SuppressResponseBodyCapture = AttributeKey<Unit>("NetworkObservationSuppressResponseBody")

/** Marks this request so [NetworkObservation] logs status/timing but never the response body. */
fun HttpRequestBuilder.suppressResponseBodyCapture() {
    attributes.put(SuppressResponseBodyCapture, Unit)
}

/**
 * A passive Ktor client plugin that reports one redacted [NetworkExchange] per call to a
 * [NetworkObserver]. Installed only when the observer is non-no-op (see `HttpClients.kt`), so it adds
 * nothing in production.
 *
 * **Audit boundary:** redaction ([NetworkRedaction]) runs at capture time — the URL (Steam's
 * `?access_token=`), the `Authorization` header, and any token-bearing request/response body are
 * scrubbed *before* the snapshot is stored, so no un-redacted secret is ever held or emitted. The
 * response body is read via [io.ktor.client.statement.bodyAsText]; Ktor caches non-streaming bodies,
 * so this does not consume the copy the caller later reads (the read is guarded so a failure never
 * corrupts the real call).
 */
val NetworkObservation = createClientPlugin("NetworkObservation", ::NetworkObservationConfig) {
    val observer = pluginConfig.observer
    val origin = pluginConfig.origin
    val secrets = pluginConfig.secretParamNames
    val maxBody = pluginConfig.maxBodyLen
    // Audited default: redact secrets at capture. Only the dev-only debug-harness observer opts out
    // (see NetworkObserver.redactSecrets) to show raw bodies in its local session log.
    val redact = observer.redactSecrets

    onRequest { request, content ->
        val rawBody = when (content) {
            is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
            is String -> content
            else -> null
        }
        val rawHeaders = request.headers.entries().associate { (name, values) -> name to values.joinToString(",") }
        request.attributes.put(
            ExchangeStartKey,
            ExchangeStart(
                method = request.method.value,
                redactedUrl = if (redact) NetworkRedaction.redactUrl(request.url.buildString(), secrets) else request.url.buildString(),
                redactedHeaders = if (redact) NetworkRedaction.redactHeaders(rawHeaders) else rawHeaders,
                redactedRequestBody = if (redact) NetworkRedaction.redactBody(rawBody, secrets, maxBody) else rawBody,
                suppressResponseBody = request.attributes.getOrNull(SuppressResponseBodyCapture) != null,
                startedAtEpochMs = Clock.System.now().toEpochMilliseconds(),
                mark = TimeSource.Monotonic.markNow(),
            ),
        )
    }

    onResponse { response ->
        val start = response.call.attributes.getOrNull(ExchangeStartKey) ?: return@onResponse
        // Ktor caches the non-streaming body, so reading it here leaves the caller's later read intact;
        // guarded so a read failure can never corrupt the real call. Skipped when the request opted out
        // via [suppressResponseBodyCapture] (secret-bearing responses: loyalty-token HTML / refresh nonce).
        val rawResponseBody = if (start.suppressResponseBody) null else runCatching { response.bodyAsText() }.getOrNull()
        val exchange = NetworkExchange(
            origin = origin,
            method = start.method,
            url = start.redactedUrl,
            headers = start.redactedHeaders,
            requestBody = start.redactedRequestBody,
            responseStatus = response.status.value,
            responseBody = if (redact) NetworkRedaction.redactBody(rawResponseBody, secrets, maxBody) else rawResponseBody,
            startedAtEpochMs = start.startedAtEpochMs,
            durationMs = start.mark.elapsedNow().inWholeMilliseconds,
        )
        runCatching { observer.onExchange(exchange) }
    }
}
