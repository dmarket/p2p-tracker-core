package com.dmarket.p2p.headless

import com.dmarket.p2p.tracker.config.NotaryConfig
import com.dmarket.p2p.tracker.config.TrackerConfig
import com.dmarket.p2p.tracker.model.LifecycleEvent
import com.dmarket.p2p.tracker.model.toWireJson
import com.dmarket.p2p.tracker.port.host.Clock
import com.dmarket.p2p.tracker.port.host.EventObserver
import kotlin.time.Instant

/**
 * The options of [createHeadlessTracker], read off the host's plain object.
 *
 * Every refusal names the option and never its value: a host that put a secret in the wrong field must not find
 * it again in an error message or a stack trace.
 */
internal class HeadlessOptions(
    val dataDir: String,
    val baseUrl: String,
    val steamWrites: Boolean,
    val notaryUrl: String,
    val onEvent: ((String) -> Unit)?,
    val nowMs: (() -> Double)?,
) {
    /** The host's clock hook, or `null` for the system clock. */
    fun clock(): Clock? = nowMs?.let { now ->
        object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(now().toLong())
        }
    }

    /** Each event as its wire JSON, to the host's callback; `null` without one. */
    fun eventObserver(): EventObserver? = onEvent?.let { callback ->
        object : EventObserver {
            override suspend fun onEvent(event: LifecycleEvent) = callback(event.toWireJson())
        }
    }

    companion object {
        private val KNOWN = setOf("dataDir", "baseUrl", "steamWrites", "notaryUrl", "onEvent", "nowMs")

        fun parse(options: dynamic): HeadlessOptions {
            require(jsTypeOf(options) == "object" && options != null) { "options must be an object" }
            val keys = js("Object.keys")(options).unsafeCast<Array<String>>()
            keys.firstOrNull { it !in KNOWN }?.let { throw IllegalArgumentException("unknown option: ${safeName(it)}") }

            val dataDir = options.dataDir
            require(jsTypeOf(dataDir) == "string" && (dataDir as String).isNotBlank()) { "dataDir must be a non-empty string" }
            val baseUrl = optional<String>(options.baseUrl, "baseUrl", "string") ?: TrackerConfig.DEFAULT_DMARKET_BASE_URL
            require(baseUrl.startsWith("https://") || baseUrl.startsWith("http://")) { "baseUrl must be an http:// or https:// URL" }
            val notaryUrl = optional<String>(options.notaryUrl, "notaryUrl", "string") ?: NotaryConfig.PRODUCTION_NOTARY_URL
            require(notaryUrl.startsWith("wss://") || notaryUrl.startsWith("ws://")) { "notaryUrl must be a ws:// or wss:// URL" }
            val steamWrites = optional<Boolean>(options.steamWrites, "steamWrites", "boolean") ?: true
            val onEvent = optional<(String) -> Unit>(options.onEvent, "onEvent", "function")
            val nowMs = optional<() -> Double>(options.nowMs, "nowMs", "function")
            return HeadlessOptions(dataDir, baseUrl, steamWrites, notaryUrl, onEvent, nowMs)
        }

        /** [value] when it is of JS type [type], `null` when absent; anything else is refused by [name]. */
        private fun <T> optional(value: dynamic, name: String, type: String): T? {
            if (value == null) return null
            require(jsTypeOf(value) == type) { "$name must be a $type" }
            return value.unsafeCast<T>()
        }

        /** An unknown key, quoted only while it looks like an option name rather than data. */
        private fun safeName(key: String): String = if (OPTION_NAME.matches(key)) key else "(a key that is not an option name)"

        private val OPTION_NAME = Regex("^[A-Za-z][A-Za-z0-9]{0,39}$")
    }
}
