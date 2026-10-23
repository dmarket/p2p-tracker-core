package com.dmarket.p2p.headless.node

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** One request a [LoopbackServer] saw: its method, path with query, headers (lower-case names) and body. */
class SeenRequest(val method: String, val url: String, val headers: dynamic, val body: String)

/**
 * A real HTTP server on 127.0.0.1, so a test goes through the platform's own HTTP engine rather than a mock.
 * [answer] gives each request's status and JSON body. The server sends no `Date` header.
 */
class LoopbackServer private constructor(private val server: dynamic, val seen: MutableList<SeenRequest>) {
    val base: String get() = "http://127.0.0.1:${server.address().port}"

    fun close() {
        server.close()
    }

    companion object {
        suspend fun start(answer: (SeenRequest) -> Pair<Int, String>): LoopbackServer {
            val seen = mutableListOf<SeenRequest>()
            val server = createServer { request, response ->
                val chunks = mutableListOf<String>()
                request.setEncoding("utf8")
                request.on("data") { chunk: String -> chunks += chunk }
                request.on("end") {
                    val seenRequest = SeenRequest(request.method as String, request.url as String, request.headers, chunks.joinToString(""))
                    seen += seenRequest
                    val (status, body) = answer(seenRequest)
                    response.sendDate = false
                    response.writeHead(status, js("({'content-type': 'application/json'})"))
                    response.end(body)
                }
            }
            suspendCancellableCoroutine { continuation -> server.listen(0, "127.0.0.1") { continuation.resume(Unit) } }
            return LoopbackServer(server, seen)
        }
    }
}
