package com.dmarket.p2p.headless.node

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * An HTTP `CONNECT` proxy on 127.0.0.1 that records each `CONNECT` it is sent ([connects]: target and
 * `Proxy-Authorization`), and either tunnels to the target ([refuseWith] `null`) or answers the status [refuseWith] —
 * which keeps a test whose target is a real Steam host off the network. [user] and [password] are the credentials
 * in [url]; the proxy itself accepts any.
 */
class LoopbackProxy private constructor(
    private val server: dynamic,
    val connects: MutableList<Pair<String, String?>>,
    private val user: String,
    private val password: String,
) {
    val port: Int get() = (server.address().port as Number).toInt()

    val url: String get() = "http://$user:$password@127.0.0.1:$port"

    fun close() {
        server.close()
        runCatching { server.closeAllConnections() }
    }

    companion object {
        suspend fun start(
            user: String = "trader",
            password: String = "s3cret-pass",
            refuseWith: Int? = null,
            establishedWith: Int = 200,
        ): LoopbackProxy {
            val connects = mutableListOf<Pair<String, String?>>()
            val server = create(nodeHttp, nodeNet, refuseWith, establishedWith, { target: String, auth: String? ->
                connects +=
                    target to auth
            })
            suspendCancellableCoroutine { continuation -> server.listen(0, "127.0.0.1") { continuation.resume(Unit) } }
            return LoopbackProxy(server, connects, user, password)
        }

        /** A port nothing listens on: a proxy that cannot be reached. */
        suspend fun closedPort(): Int {
            val server: dynamic = nodeNet.createServer()
            suspendCancellableCoroutine { continuation -> server.listen(0, "127.0.0.1") { continuation.resume(Unit) } }
            val port = (server.address().port as Number).toInt()
            suspendCancellableCoroutine { continuation -> server.close { continuation.resume(Unit) } }
            return port
        }

        private val create: dynamic = js(
            """
            (function (http, net, refuseWith, establishedWith, onConnect) {
              const server = http.createServer((req, res) => { res.statusCode = 405; res.end(); });
              server.on('connect', (req, socket, head) => {
                onConnect(req.url, req.headers['proxy-authorization'] || null);
                socket.on('error', () => {});
                if (refuseWith) {
                  socket.end('HTTP/1.1 ' + refuseWith + ' Refused\r\n\r\n');
                  return;
                }
                const colon = req.url.lastIndexOf(':');
                const upstream = net.connect(Number(req.url.slice(colon + 1)), req.url.slice(0, colon).replace(/^\[|\]$/g, ''), () => {
                  socket.write('HTTP/1.1 ' + establishedWith + ' Connection Established\r\n\r\n');
                  if (head.length) upstream.write(head);
                  upstream.pipe(socket);
                  socket.pipe(upstream);
                });
                upstream.on('error', () => socket.destroy());
                socket.on('close', () => upstream.destroy());
              });
              return server;
            })
            """,
        )
    }
}

@JsModule("node:http")
private external val nodeHttp: dynamic
