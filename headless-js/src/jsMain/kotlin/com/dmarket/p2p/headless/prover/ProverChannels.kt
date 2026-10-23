package com.dmarket.p2p.headless.prover

import com.dmarket.p2p.headless.node.nodePath
import com.dmarket.p2p.headless.node.nodeProcess
import com.dmarket.p2p.headless.node.nodeUrl
import com.dmarket.p2p.headless.node.wsWebSocket
import kotlin.js.Promise

/**
 * What one proof's sockets saw, for [com.dmarket.p2p.tracker.notary.ProofFailures.classify]: the notary's refused
 * upgrade or close code, and Steam's dial error.
 */
internal class ProverSessionRecord {
    var notaryAttempted = false
    var notaryOpened = false
    var upgradeStatus: Int? = null
    var closeCode: Int? = null
    var closeReason: String? = null
    var targetError: String? = null
    var targetStatus: Int? = null
    var signingError: String? = null

    /** Called when the notary ends the session on purpose (any close but 1000, 1005 and 1006). */
    var onDeliberateClose: ((code: Int, reason: String?) -> Unit)? = null
}

/**
 * A `WebSocket` constructor for the transport's `connectNotary`, over the `ws` package: it sends [headers] on the
 * upgrade, drops the `bearer.` subprotocol entry the transport offers (the signed headers are the credential), and
 * writes into [record] what ended the session. A browser's `WebSocket` cannot tell a 401 from a 503; `ws` names the
 * refused status in its error ("Unexpected server response: 401"), and its close event carries the code.
 */
internal fun headerWebSocket(headers: Map<String, String>, record: ProverSessionRecord): dynamic {
    val headerObject: dynamic = js("({})")
    headers.forEach { (name, value) -> headerObject[name] = value }
    val onOpen = {
        record.notaryOpened = true
        // What a child holds while the notary queues it after the 101: the wasm initialised, no MPC yet.
        println("prover: notary connected rssMb=${(nodeProcess.memoryUsage().rss as Number).toLong() / BYTES_PER_MB}")
    }
    val onError = { message: String? ->
        UNEXPECTED_RESPONSE.find(message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()?.let { record.upgradeStatus = it }
    }
    val onClose = { code: Int, reason: String? ->
        if (record.notaryOpened) {
            record.closeCode = code
            record.closeReason = reason?.takeIf { it.isNotBlank() }
            if (code !in UNDELIBERATE_CLOSES) record.onDeliberateClose?.invoke(code, record.closeReason)
        }
    }
    val ws = wsWebSocket
    val factory: dynamic = js(
        """
        (function (WebSocket, headers, onOpen, onError, onClose) {
          return function HeaderWebSocket(url, protocols) {
            const offered = (Array.isArray(protocols) ? protocols : [protocols]).filter((p) => typeof p === 'string' && !p.startsWith('bearer.'));
            const socket = new WebSocket(url, offered, { headers: headers });
            socket.on('open', () => onOpen());
            socket.on('error', (error) => onError(error && error.message));
            socket.on('close', (code, reason) => onClose(code, reason ? reason.toString() : null));
            return socket;
          };
        })
        """,
    )
    return factory(ws, headerObject, onOpen, onError, onClose)
}

private const val BYTES_PER_MB = 1024L * 1024L

/** Normal ends, and a dropped connection: none of them is the notary refusing the session. */
private val UNDELIBERATE_CLOSES = setOf(1000, 1005, 1006)

private val UNEXPECTED_RESPONSE = Regex("""Unexpected server response: (\d{3})""")

/**
 * The prover's target leg: [connect] dials [address] (`host:port`) and resolves the `IoChannel` the prover reads and
 * writes. In a prover process [connect] is the transport's own Node connector ([nodeTcpConnector]): a raw TCP socket
 * with `TCP_NODELAY`, its two directions adapted independently, destroyed when the dial fails. The TLS is the
 * prover's own, inside MPC; this is the bare byte stream a browser can only reach through the `wss://` proxy.
 *
 * What the transport leaves to its host is added here, without touching the socket: a dial that has not connected
 * within [dialTimeoutMs] fails (and the channel it may still bring is closed), and [record] is told why the leg failed,
 * from the dial or from the first read or write the socket refused. A failure after the prover closed the channel is
 * its own doing, not Steam's, and is not recorded.
 */
internal fun recordedTargetChannel(
    address: String,
    record: ProverSessionRecord,
    dialTimeoutMs: Int = TARGET_DIAL_TIMEOUT_MS,
    connect: (host: String, port: Int) -> Promise<Any?>,
): Promise<Any?> {
    val host = address.substringBeforeLast(':')
    val port = address.substringAfterLast(':').toInt()
    val onError = { message: String? -> if (record.targetError == null) record.targetError = message ?: "the Steam connection failed" }
    val open: dynamic = js(
        """
        (function (dial, host, port, timeoutMs, onError) {
          return new Promise((resolve, reject) => {
            let settled = false;
            const timer = setTimeout(() => {
              settled = true;
              const error = new Error('the Steam connection timed out after ' + timeoutMs + ' ms');
              onError(error.message);
              reject(error);
            }, timeoutMs);
            dial(host, port).then((io) => {
              clearTimeout(timer);
              if (settled) { io.close().catch(() => {}); return; }
              settled = true;
              let closed = false;
              const watch = (promise) => promise.catch((error) => {
                if (!closed) onError(error && error.message);
                throw error;
              });
              resolve({
                read: () => watch(io.read()),
                write: (data) => watch(io.write(data)),
                close: () => { closed = true; return io.close(); },
              });
            }, (error) => {
              clearTimeout(timer);
              if (settled) return;
              settled = true;
              onError(error && error.message);
              reject(error);
            });
          });
        })
        """,
    )
    return open(connect, host, port, dialTimeoutMs, onError).unsafeCast<Promise<Any?>>()
}

/**
 * The transport's Node connector, `connectTcp(host, port)` from `transport/dist/node.js` under [assetsDir]: the
 * module behind `client-wasm-transport`'s `/node` export, imported on the first dial. It imports `node:` built-ins, so
 * only a Node host loads it; the browser path never reaches this file.
 */
internal fun nodeTcpConnector(assetsDir: String): (host: String, port: Int) -> Promise<Any?> {
    val url = nodeUrl.pathToFileURL(nodePath.join(assetsDir, "transport", "dist", "node.js")).href as String
    val dial: dynamic = js(
        """
        (function (url) {
          let loading = null;
          return (host, port) => {
            if (loading === null) loading = import(url);
            return loading.then((module) => module.connectTcp(host, port));
          };
        })
        """,
    )
    return dial(url).unsafeCast<(String, Int) -> Promise<Any?>>()
}

/** How long the target's TCP dial may take: Steam answers a connect in well under a second. */
internal const val TARGET_DIAL_TIMEOUT_MS: Int = 15_000
