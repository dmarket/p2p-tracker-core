package com.dmarket.p2p.sdk.prover

import com.dmarket.p2p.sdk.node.NodeModules
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
        println("prover: notary connected rssMb=${(NodeModules.process.memoryUsage().rss as Number).toLong() / BYTES_PER_MB}")
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
    val ws = NodeModules.ws
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
 * The prover's target leg: a TCP connection to [address] (`host:port`), as the `IoChannel` the prover reads and
 * writes. The TLS is the prover's own, inside MPC; this is the bare byte stream a browser can only reach through
 * the `wss://` proxy. A failed dial or a reset goes into [record].
 */
internal fun tcpChannel(address: String, record: ProverSessionRecord): Promise<Any?> {
    val host = address.substringBeforeLast(':')
    val port = address.substringAfterLast(':').toInt()
    val onError = { message: String? -> record.targetError = message ?: "the Steam connection failed" }
    val open: dynamic = js(
        """
        (function (net, host, port, onError) {
          return new Promise((resolve, reject) => {
            const socket = net.connect({ host: host, port: port });
            const chunks = [];
            const waiters = [];
            let ended = false;
            const finish = () => { ended = true; while (waiters.length) waiters.shift()(null); };
            socket.on('data', (data) => {
              const bytes = new Uint8Array(data.buffer, data.byteOffset, data.length).slice();
              const waiter = waiters.shift();
              if (waiter) waiter(bytes); else chunks.push(bytes);
            });
            socket.on('end', finish);
            socket.on('close', finish);
            socket.on('error', (error) => { onError(error && error.message); finish(); });
            const channel = {
              read: () => chunks.length ? Promise.resolve(chunks.shift()) : (ended ? Promise.resolve(null) : new Promise((r) => waiters.push(r))),
              write: (data) => new Promise((resolve, reject) => {
                if (ended) { reject(new Error('cannot write: the Steam connection is closed')); return; }
                socket.write(Buffer.from(data.slice()), (error) => error ? reject(error) : resolve());
              }),
              close: () => { if (!socket.destroyed) socket.end(); return Promise.resolve(); },
            };
            socket.once('connect', () => resolve(channel));
            socket.once('error', (error) => reject(error));
          });
        })
        """,
    )
    return open(NodeModules.net, host, port, onError).unsafeCast<Promise<Any?>>()
}
