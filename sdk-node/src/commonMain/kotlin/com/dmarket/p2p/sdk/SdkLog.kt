package com.dmarket.p2p.sdk

import com.dmarket.p2p.tracker.engine.FailedCancelKind
import com.dmarket.p2p.tracker.model.LifecycleEvent
import com.dmarket.p2p.tracker.model.toWireJson
import com.dmarket.p2p.tracker.port.host.Clock
import com.dmarket.p2p.tracker.port.host.EventObserver
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Structured logs: one JSON object per line on stdout, `{ts, level, msg, …fields}`.
 *
 * Callers pass only what is safe to print — a public key, a Steam id, a deal id — and never a secret: there is
 * no field here that a pushed key or token could reach. Lifecycle events go out as their own wire JSON, which
 * the core keeps secret-free.
 */
class SdkLog(private val clock: Clock, private val writeLine: (String) -> Unit) : EventObserver {

    fun info(msg: String, vararg fields: Pair<String, String?>) = write("info", msg, fields)

    fun warn(msg: String, vararg fields: Pair<String, String?>) = write("warn", msg, fields)

    fun error(msg: String, vararg fields: Pair<String, String?>) = write("error", msg, fields)

    override suspend fun onEvent(event: LifecycleEvent) {
        val payload = Json.parseToJsonElement(event.toWireJson()).jsonObject
        writeLine(line(levelOf(event), "event", payload))
        // A trade that went through after DMarket gave up on the deal: no problem code is ever sent to DMarket, so this
        // line is the lasting record a person acts on.
        if (event is LifecycleEvent.FailedCancelChecked && event.kind == FailedCancelKind.ALREADY_TRADED.wireName) {
            warn(
                "offer accepted after cancel",
                "dealId" to event.dealId,
                "offerId" to event.steamOfferId,
                "offerState" to event.offerState?.toString(),
            )
        }
    }

    private fun write(level: String, msg: String, fields: Array<out Pair<String, String?>>) {
        writeLine(line(level, msg, buildJsonObject { fields.forEach { (k, v) -> put(k, v) } }))
    }

    private fun line(level: String, msg: String, fields: JsonObject): String = buildJsonObject {
        put("ts", clock.now().toString())
        put("level", level)
        put("msg", msg)
        fields.forEach { (k, v) -> put(k, v) }
    }.toString()

    private fun levelOf(event: LifecycleEvent): String = when (event) {
        is LifecycleEvent.CycleFailed,
        is LifecycleEvent.LinkedSteamIdMismatch,
        is LifecycleEvent.MarketplaceServerError,
        is LifecycleEvent.ReLoginNeeded,
        -> "error"
        is LifecycleEvent.SteamReadFailed,
        is LifecycleEvent.ProofFailed,
        is LifecycleEvent.DirectiveReportFailed,
        is LifecycleEvent.TradeStatusReportFailed,
        is LifecycleEvent.LinkedSteamIdUnknown,
        is LifecycleEvent.SteamWriteDeferred,
        is LifecycleEvent.CreateUnanswered,
        -> "warn"
        is LifecycleEvent.DirectiveExecuted -> if (event.status == "FAILED") "warn" else "info"
        else -> "info"
    }
}
