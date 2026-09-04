package com.dmarket.p2p.tracker.model

/**
 * Which transport axis an observed [NetworkExchange] rode. The two Ktor clients are kept on separate
 * engines (see `client/HttpClients.kt`) precisely so a captured exchange can never be mis-attributed:
 * a Steam read and a DMarket call are structurally distinct, and the observer tags each accordingly.
 */
enum class ExchangeOrigin {
    STEAM,
    MARKETPLACE,
}

/**
 * One observed HTTP request/response pair, surfaced to a [com.dmarket.p2p.tracker.port.host.NetworkObserver]
 * for diagnostics (the debug harness renders these as a session log; native hosts can forward them to
 * their own telemetry). This type is **environment-agnostic** — no Ktor, no platform handle — so the
 * same observability surface is reusable on web, Android and iOS.
 *
 * **Audit boundary:** by default every field here — including [requestBody] and [responseBody] — is
 * **redacted** by [com.dmarket.p2p.tracker.net.NetworkRedaction] before the record is constructed. The
 * Steam JWT (carried as an `access_token` query param on IEconService reads), the `Authorization`
 * header, session cookies, and any token assignment in a body never reach this record verbatim — they
 * appear only as [com.dmarket.p2p.tracker.net.NetworkRedaction.REDACTED]. The one body deliberately kept
 * out entirely (never captured, redacted or not) is the Steam session-scrape HTML, whose sole payload is
 * that JWT. Consumers may persist and display these records freely; they cannot leak a credential. The
 * sole exception is a [com.dmarket.p2p.tracker.port.host.NetworkObserver] that opts out via
 * `redactSecrets = false`; that override exists only in the unpublished dev `:debug-harness` and is
 * forbidden in production wiring — see [com.dmarket.p2p.tracker.port.host.NetworkObserver.redactSecrets].
 *
 * [url] carries the full request URL **including its (redacted) query string**, so a consumer that
 * wants to display query parameters can split on `?` without any secret exposure.
 */
data class NetworkExchange(
    val origin: ExchangeOrigin,
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val requestBody: String?,
    val responseStatus: Int?,
    val responseBody: String?,
    val startedAtEpochMs: Long,
    val durationMs: Long,
    val error: String? = null,
)
