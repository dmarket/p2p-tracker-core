package com.dmarket.p2p.sdk.node

import com.dmarket.p2p.sdk.SdkLog
import com.dmarket.p2p.sdk.api.ApiResponse
import com.dmarket.p2p.sdk.api.ManagementApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The management API's listener over `node:http`. Reads at most [ManagementApi.MAX_BODY_BYTES] of body, hands
 * the request to [api], and writes its answer. Logs the method, the route and the status of each request —
 * never a header value or a body, both of which carry secrets.
 */
class NodeManagementServer(private val api: ManagementApi, private val scope: CoroutineScope, private val log: SdkLog) {
    private var server: dynamic = null

    suspend fun listen(host: String, port: Int) {
        val created = NodeModules.http.createServer { request: dynamic, response: dynamic -> serve(request, response) }
        server = created
        suspendCancellableCoroutine { continuation ->
            created.once("error") { error: Throwable -> continuation.resumeWithException(error) }
            created.listen(port, host) { continuation.resume(Unit) }
        }
    }

    /** The port actually bound — the one asked for, or the one the OS picked for `0`. */
    fun boundPort(): Int = server.address().port as Int

    fun close() {
        server?.close()
    }

    private fun serve(request: dynamic, response: dynamic) {
        val method = request.method as String
        val route = (request.url as String).substringBefore('?')
        val chunks = mutableListOf<dynamic>()
        var size = 0
        var tooLarge = false
        request.on("data") { chunk: dynamic ->
            if (tooLarge) return@on
            size += chunk.length as Int
            if (size > ManagementApi.MAX_BODY_BYTES) {
                tooLarge = true
                write(response, ApiResponse(413, """{"error":"BODY_TOO_LARGE","reason":"the body exceeds the limit"}"""))
                request.destroy()
            } else {
                chunks.add(chunk)
            }
        }
        request.on("end") {
            if (tooLarge) return@on
            val body = NodeModules.buffer.concat(chunks.toTypedArray()).toString("utf8") as String
            val authorization = request.headers.authorization as? String
            scope.launch {
                val answer = runCatching { api.handle(method, route, authorization, body) }
                    .getOrElse { ApiResponse(500, """{"error":"INTERNAL","reason":"the request could not be handled"}""") }
                write(response, answer)
                log.info("management request", "method" to method, "route" to route, "status" to answer.status.toString())
            }
        }
    }

    private fun write(response: dynamic, answer: ApiResponse) {
        val headers: dynamic = js("({'content-type': 'application/json', 'cache-control': 'no-store'})")
        response.writeHead(answer.status, headers)
        response.end(answer.body)
    }
}
