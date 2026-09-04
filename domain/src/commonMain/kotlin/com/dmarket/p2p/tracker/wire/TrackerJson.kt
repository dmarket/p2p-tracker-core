package com.dmarket.p2p.tracker.wire

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonBuilder

/**
 * The **only** way this project should construct a [Json]. Applies [configure], then forces
 * `exceptionsWithDebugInfo = false` — last, so no caller can re-enable it.
 *
 * **Why this is security-critical.** kotlinx-serialization ships that flag `true`, which appends
 * `"\nJSON input: " + input` to every `JsonDecodingException` message and returns the input **verbatim**
 * when it is under 200 characters. Every body this core decodes is under that threshold or close to it:
 * a Steam `jwt/ajaxrefresh` reply (`nonce`, `auth`), a DMarket heartbeat carrying
 * `directives[].tradeToken`, a stored credential. Those messages do not stay local — they surface in
 * `LifecycleEvent.DirectiveReportFailed.reason`, in a `create_offer` outcome that is POSTed to DMarket and
 * handed to the web page, and on the web target they reach the host via `globalThis.reportError`.
 *
 * Upstream agrees with the posture: the flag's own KDoc says it "will be changed to `false` when API
 * stabilizes to assume data is sensitive and unsafe by default".
 *
 * What is still reported on a decode failure: the short message, the byte offset, the JSON path and the
 * serial name — enough to identify a schema drift. What is lost: the offending input, and map **keys**
 * inside `at path:` (they render as `<debug info disabled>`).
 */
@OptIn(ExperimentalSerializationApi::class)
fun trackerJson(configure: JsonBuilder.() -> Unit = {}): Json = Json {
    configure()
    exceptionsWithDebugInfo = false
}

/**
 * The single JSON configuration used for every `/exchange/v1/p2p/ext/` payload. Centralised so all
 * three clients encode identically.
 *
 * - [Json.ignoreUnknownKeys]: response schemas grow additively, so clients must tolerate fields the
 *   backend adds later (this is also what keeps the provisional [PushEnvelopeDto] forward-compatible).
 * - [Json.explicitNulls] off: omit null fields (e.g. an absent `steam_id`) rather than emit `null`.
 * - `exceptionsWithDebugInfo` off via [trackerJson] — see there.
 */
val TrackerJson: Json = trackerJson {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
