package com.dmarket.p2p.tracker.client

import io.ktor.client.engine.js.JsError

/**
 * Ktor's JS engine rejects a failed fetch as `Error("Fail to fetch", JsError(<the fetch's rejection>))`. In a
 * browser that rejection is a `TypeError: Failed to fetch` with nothing under it. On Node it is a
 * `TypeError: fetch failed` whose `cause` says what happened: a system error (`ECONNRESET`, `ETIMEDOUT`, a DNS
 * `ENOTFOUND`) or an undici one (`UND_ERR_SOCKET`, `UND_ERR_CONNECT_TIMEOUT`), each with a `code` and a message.
 * That cause, and the one under it if any, as `code: message`.
 */
internal actual fun Throwable.transportCause(): String? {
    val origin = (cause as? JsError)?.origin ?: return null
    val described = mutableListOf<String>()
    var current: dynamic = origin.cause
    while (current != null && current != undefined && described.size < MAX_CAUSES) {
        describe(current)?.let { described += it }
        current = current.cause
    }
    return described.takeIf { it.isNotEmpty() }?.joinToString(" <- ")
}

private const val MAX_CAUSES = 2

/** `code: message`, or whichever of the two the error has; `null` for a value that is neither. */
private fun describe(error: dynamic): String? {
    val code = (error.code as? String)?.takeIf { it.isNotBlank() }
    val message = (error.message as? String)?.takeIf { it.isNotBlank() }
    return when {
        code != null && message != null -> if (code in message) message else "$code: $message"
        else -> code ?: message
    }
}
