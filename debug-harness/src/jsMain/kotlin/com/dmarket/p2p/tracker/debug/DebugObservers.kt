package com.dmarket.p2p.tracker.debug

import com.dmarket.p2p.tracker.model.LifecycleEvent
import com.dmarket.p2p.tracker.model.NetworkExchange
import com.dmarket.p2p.tracker.model.toWireJson
import com.dmarket.p2p.tracker.port.host.EventObserver
import com.dmarket.p2p.tracker.port.host.NetworkObserver
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * A bounded ring buffer of session-log entry JSON strings. Single-threaded (JS), so no locking. The
 * authoritative persistent store is the service worker's IndexedDB; this in-memory mirror lets the
 * facade answer `getSessionLog` after a fresh worker boot without a round-trip.
 */
internal class SessionLogBuffer(private val maxEntries: Int) {
    private val entries = ArrayDeque<String>()

    fun add(entryJson: String) {
        entries.addLast(entryJson)
        while (entries.size > maxEntries) entries.removeFirst()
    }

    /** Returns the buffered entries as a JSON array string. */
    fun snapshotJson(): String = buildString {
        append('[')
        entries.forEachIndexed { i, e ->
            if (i > 0) append(',')
            append(e)
        }
        append(']')
    }

    fun clear() = entries.clear()
}

/** Pushes a built entry to the buffer and the (optional) JS callback. */
internal class LogSink(private val buffer: SessionLogBuffer, private val callback: ((String) -> Unit)?) {
    fun emit(entry: JsonObject) {
        val json = entry.toString()
        buffer.add(json)
        callback?.invoke(json)
    }
}

/**
 * The [NetworkObserver] the debug harness installs on both Ktor clients + the raw-fetch Steam actuals.
 * Builds a `category:"network"` log entry from the [NetworkExchange]. The `ts`/`seq` are stamped by the
 * service worker on receipt.
 *
 * [redactSecrets] follows the audited default (`true`) unless the session was started through
 * `startDebugSession(..., revealSecrets = true)`, which sets [reveal]. With it on, request/response
 * bodies and the URL are captured **verbatim** — raw `sessionid`, tokens, cookies and all — which is
 * tolerable only because this observer lives exclusively in the unpublished `:debug-harness`, whose
 * session log has **no automatic external sink**: it stays in a local Chrome debug extension, and the
 * one way out is the dashboard's Export button, which a human presses. That is precisely why the
 * default is redacted — a reveal-secrets log is one click away from a file on disk. The production
 * `JsApi` never touches this class.
 */
internal class JsNetworkObserver(private val sink: LogSink, reveal: Boolean = false) : NetworkObserver {
    override val redactSecrets: Boolean = !reveal

    override suspend fun onExchange(exchange: NetworkExchange) {
        sink.emit(
            buildJsonObject {
                put("category", "network")
                put("origin", exchange.origin.name)
                put("method", exchange.method)
                put("url", exchange.url)
                put("status", exchange.responseStatus)
                put("durationMs", exchange.durationMs)
                put("requestBody", exchange.requestBody)
                put("responseBody", exchange.responseBody)
                put("error", exchange.error)
                putJsonObject("headers") {
                    for ((name, value) in exchange.headers) put(name, value)
                }
            },
        )
    }
}

/**
 * The [EventObserver] the debug harness installs — surfaces loop lifecycle as `category:"lifecycle"`.
 *
 * The frame itself comes from [toWireJson], the canonical encoder next to the sealed type, rather than a
 * second `when` over every variant: the copy that used to live here had to be updated for each new event,
 * and the compiler only ever complained about the copy — so the harness silently spoke a different
 * vocabulary from every other host until someone noticed.
 */
internal class JsEventObserver(private val sink: LogSink) : EventObserver {
    override suspend fun onEvent(event: LifecycleEvent) {
        val frame = Json.parseToJsonElement(event.toWireJson()).jsonObject
        sink.emit(
            buildJsonObject {
                put("category", "lifecycle")
                for ((key, value) in frame) put(key, value)
            },
        )
    }
}
