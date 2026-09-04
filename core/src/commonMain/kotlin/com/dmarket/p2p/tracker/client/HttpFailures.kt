package com.dmarket.p2p.tracker.client

import com.dmarket.p2p.tracker.net.NetworkRedaction
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.util.AttributeKey

/**
 * Sanitized HTTP failures. **Security-critical**: these types exist so that no request URL and no response
 * body can reach an exception message, because those messages leave the core — they are carried in
 * [com.dmarket.p2p.tracker.model.LifecycleEvent.SteamReadFailed], in a `create_offer` directive outcome
 * (which is POSTed to DMarket, persisted, and handed to the web page), and — on the web target — to the
 * host's crash reporter via the coroutine machinery's `globalThis.reportError`.
 *
 * Ktor's own defaults do the opposite. With `expectSuccess = true`, `addDefaultResponseValidation` throws
 * [io.ktor.client.plugins.ClientRequestException], whose message is
 * `Client request(<METHOD> <FULL URL>) invalid: <status>. Text: "<ENTIRE RESPONSE BODY>"` — untruncated,
 * with the URL still carrying Steam's `?access_token=<jwt>`. [HttpRequestTimeoutException] is the same
 * family (`Request timeout has expired [url=<FULL URL incl. query>…]`).
 *
 * Neither type below retains a `cause` or the `HttpResponse`. That is deliberate on both counts:
 * `Throwable.stackTraceToString()` walks `cause` (and suppressed exceptions), and Kotlin/JS keeps a
 * `ResponseException`'s response in a plain enumerable own property, which `JSON.stringify(err)` dumps.
 */
sealed class HttpCallFailureException(message: String) : RuntimeException(message)

/**
 * A non-2xx reply. [redactedUrl] has been through [NetworkRedaction.redactUrl]; the message never carries
 * a body. [errorBody] is `null` unless the request opted in via [captureErrorBody], and is redacted and
 * capped when present.
 */
class HttpStatusException internal constructor(
    val statusCode: Int,
    val method: String,
    val redactedUrl: String,
    val retryAfterSeconds: Long?,
    val errorBody: String?,
) : HttpCallFailureException("HTTP $statusCode on $method $redactedUrl")

/**
 * A transport failure whose upstream message embeds the request URL. Today that is only Ktor's request
 * timeout; the JS engine's own `Fail to fetch` is already clean and diagnostically useful, so it is left
 * alone.
 */
class HttpTransportException internal constructor(val kind: String, val method: String, val redactedUrl: String) :
    HttpCallFailureException("$kind on $method $redactedUrl")

/** Per-request opt-in: how many characters of the **redacted** error body to keep. See [captureErrorBody]. */
private val CaptureErrorBodyKey = AttributeKey<Int>("HttpFailureCaptureErrorBody")

/**
 * Opt this request in to carrying up to [maxLen] characters of its error body on [HttpStatusException]
 * .errorBody. Off by default, and never raw: the body is [NetworkRedaction.redactBody]-scrubbed and capped.
 *
 * Only worth it where the server's own error text is the diagnosis — Steam's `{"strError":…}` / EResult
 * envelope on a failed offer create is the one such case in this core.
 */
fun HttpRequestBuilder.captureErrorBody(maxLen: Int = 512) {
    attributes.put(CaptureErrorBodyKey, maxLen)
}

/**
 * Replaces Ktor's default non-2xx handling with [HttpStatusException] / [HttpTransportException], so no
 * URL or response body is ever built into an exception message. Call this instead of setting
 * `expectSuccess`.
 *
 * A host that supplies its own pre-authenticated [io.ktor.client.HttpClient] (the mobile path — see
 * [createMarketplaceHttpClient]) **must** call this too; otherwise Ktor's leaky defaults apply and
 * `KtorMarketplaceClient`'s status mapping does not match. The [ResponseException] branch in
 * [HttpResponseValidator] below is the safety net for exactly that case, and for a caller that sets
 * `expectSuccess = true` on an individual request (which wins, because the default validator reads it
 * per request).
 *
 * @param secretParamNames redaction set for the URL. A caller that renamed the Steam access-token query
 *   param passes [NetworkRedaction.plusSecretParam] so the renamed param is still scrubbed.
 */
fun HttpClientConfig<*>.sanitizeHttpFailures(secretParamNames: Set<String> = NetworkRedaction.DEFAULT_SECRET_PARAM_NAMES) {
    // false so `addDefaultResponseValidation`'s validator early-returns BEFORE it saves the call and reads
    // the body — i.e. the leaky string is never constructed, not merely discarded. Our own validator below
    // runs regardless of this flag. Side effect: that early return logs "Skipping default response
    // validation for <url>" at TRACE. Harmless on the web target (Ktor's JS logger resolves to INFO, and
    // trace() returns before touching the console), but a JVM host with TRACE enabled would print request
    // URLs — do not point this at a Steam client on JVM without re-checking that.
    expectSuccess = false
    HttpResponseValidator {
        validateResponse { response ->
            if (response.status.value < 300) return@validateResponse
            val request = response.call.request
            val cap = response.call.attributes.getOrNull(CaptureErrorBodyKey)
            throw HttpStatusException(
                statusCode = response.status.value,
                method = request.method.value,
                redactedUrl = NetworkRedaction.redactUrl(request.url.toString(), secretParamNames),
                retryAfterSeconds = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull(),
                errorBody = cap?.let { maxLen ->
                    NetworkRedaction.redactBody(
                        runCatching { response.bodyAsText() }.getOrNull(),
                        secretParamNames,
                        maxLen,
                    )
                },
            )
        }
        handleResponseExceptionWithRequest { cause, request ->
            val redactedUrl = { NetworkRedaction.redactUrl(request.url.toString(), secretParamNames) }
            when (cause) {
                // Our own exception, on its way out through the request pipeline — pass it through.
                is HttpCallFailureException -> return@handleResponseExceptionWithRequest
                is HttpRequestTimeoutException ->
                    throw HttpTransportException("timeout", request.method.value, redactedUrl())
                // Ktor's leaky types, reachable only when something re-armed `expectSuccess` (a
                // per-request override, or a host-built client that skipped sanitizeHttpFailures).
                // Re-mint as ours so neither the message nor the retained response escapes.
                is ResponseException -> throw HttpStatusException(
                    statusCode = cause.response.status.value,
                    method = request.method.value,
                    redactedUrl = redactedUrl(),
                    retryAfterSeconds = cause.response.headers[HttpHeaders.RetryAfter]?.toLongOrNull(),
                    errorBody = null,
                )
                else -> return@handleResponseExceptionWithRequest
            }
        }
    }
}
