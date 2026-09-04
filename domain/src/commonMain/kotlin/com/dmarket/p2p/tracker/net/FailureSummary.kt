package com.dmarket.p2p.tracker.net

/**
 * A short, redacted, length-capped description of a [Throwable], for the handful of places where a failure
 * has to be described in a string that **leaves the core**:
 * - `LifecycleEvent.SteamReadFailed.reason` / `DirectiveReportFailed.reason` → the host (and, on the web
 *   target, its crash reporter),
 * - a `create_offer` directive outcome's `error` → POSTed to DMarket, persisted, and handed to the web page.
 *
 * The core's own exceptions are already sanitized at the source (see
 * [com.dmarket.p2p.tracker.client.HttpStatusException] and the `exceptionsWithDebugInfo = false` posture in
 * [com.dmarket.p2p.tracker.wire.trackerJson]). This is the second layer, and the only one that also covers
 * throwables the core does not own — the platform engine, a future Ktor, a host-supplied client.
 *
 * [NetworkRedaction.redactBody] — not `redactUrl` — is the right scrubber for a free-form message:
 * `redactUrl` assumes the whole string is a URL (it splits on the first `?`), while `redactBody`'s patterns
 * match `name=value` embedded in prose, JSON and percent-encoded shapes, catch a bare JWT by shape, **and**
 * cap the length.
 */
fun Throwable.redactedSummary(
    secretParamNames: Set<String> = NetworkRedaction.DEFAULT_SECRET_PARAM_NAMES,
    maxLen: Int = DEFAULT_SUMMARY_MAX_LEN,
): String {
    val name = this::class.simpleName ?: "Throwable"
    val detail = NetworkRedaction.redactBody(message, secretParamNames, maxLen) ?: "-"
    return "$name: $detail"
}

/**
 * The same treatment for a string that came from **the other side of the wire** — a backend rejection
 * `reason`, echoed onward into a [com.dmarket.p2p.tracker.model.LifecycleEvent] a host may forward to a
 * warehouse.
 *
 * A remote party's free text is not this client's to vouch for: it is unbounded, and a backend that echoes
 * the request it rejected can hand back whatever the request contained. Scrubbed and capped exactly like a
 * failure summary, so the events' secret-free contract holds for fields the core did not author.
 */
fun String?.redactedRemoteText(
    secretParamNames: Set<String> = NetworkRedaction.DEFAULT_SECRET_PARAM_NAMES,
    maxLen: Int = DEFAULT_SUMMARY_MAX_LEN,
): String? = NetworkRedaction.redactBody(this, secretParamNames, maxLen)

/**
 * Cap for a summary that crosses a boundary. Deliberately small: these strings are stored and forwarded,
 * and the diagnosis is in the exception's class plus the first line of its message, not in a payload dump.
 */
const val DEFAULT_SUMMARY_MAX_LEN: Int = 200
