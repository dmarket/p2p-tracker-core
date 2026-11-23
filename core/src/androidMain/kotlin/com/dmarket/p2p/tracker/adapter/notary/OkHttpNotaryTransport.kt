package com.dmarket.p2p.tracker.adapter.notary

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/** The notary did not open: [upgradeStatus] is its answer to the upgrade, `null` when it never answered. */
internal class NotaryConnectException(val upgradeStatus: Int?, message: String) : IOException(message)

/**
 * The notary's WebSocket as a [NativeProverTransport]: the binary payload of the authenticated WSS
 * (`notary/CLIENT_CONTRACT.md` §1–2). Opened by [connect], which waits for the `101` before the prover's stream
 * exists, so a refused upgrade keeps its status.
 *
 * - [read] hands over the binary frames in order and answers EOF only when the notary closes the socket: the TLSN
 *   phase-two attestation travels on the same socket, so nothing between the phases is an end.
 * - [write] returns once OkHttp's own send queue is empty. The stream acknowledges a write when this returns and
 *   answers the prover's flush only after every write is acknowledged, so a flush means "on the socket", not
 *   "queued in OkHttp" (whose queue also fails the socket above 16 MiB; an MPC proof sends 40–60 MB).
 * - [shutdownOutput] sends the close frame (no more data from us) and keeps reading until the notary's close.
 * - [close] cancels the socket and unblocks a pending [read].
 */
internal class OkHttpNotaryTransport private constructor(private val onBytes: (sent: Long, received: Long) -> Unit) :
    NativeProverTransport {
    private val incoming = LinkedBlockingQueue<Chunk>(INCOMING_FRAMES)
    private val opened = CountDownLatch(1)
    private lateinit var socket: WebSocket
    private var current: ByteArray = ByteArray(0)
    private var offset = 0

    @Volatile private var open = false

    @Volatile private var failure: Throwable? = null

    /** Set by [close] and by the socket's end: no more frames are queued or waited for. */
    @Volatile private var closed = false

    @Volatile private var upgradeStatus: Int? = null

    /** The notary's close code after the `101`, with [closeReason]; `null` while it has not closed. */
    @Volatile var closeCode: Int? = null
        private set

    @Volatile var closeReason: String? = null
        private set

    /** The subprotocol the notary selected. */
    @Volatile var selectedProtocol: String? = null
        private set

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            selectedProtocol = response.header("Sec-WebSocket-Protocol")
            open = true
            opened.countDown()
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            onBytes(0, bytes.size.toLong())
            // Holds OkHttp's reader thread while the prover has not caught up (read-side back-pressure), but never past
            // the release: nobody reads a cancelled proof's queue.
            val chunk = Chunk.Data(bytes.toByteArray())
            while (!closed && !incoming.offer(chunk, OFFER_WAIT_MS, TimeUnit.MILLISECONDS)) Unit
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            closeCode = code
            closeReason = reason.ifEmpty { null }
            webSocket.close(NORMAL_CLOSE, null)
            end(Chunk.End)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closed = true
            end(Chunk.End)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            failure = t
            if (!open) upgradeStatus = response?.code
            end(Chunk.Failed)
            opened.countDown()
        }
    }

    override fun read(buffer: ByteArray): Int {
        while (offset == current.size) {
            when (val chunk = incoming.take()) {
                is Chunk.Data -> {
                    current = chunk.bytes
                    offset = 0
                }
                Chunk.End -> return -1.also { end(Chunk.End) }
                Chunk.Failed -> throw IOException("the notary socket failed", failure).also { end(Chunk.Failed) }
            }
        }
        val n = minOf(buffer.size, current.size - offset)
        current.copyInto(buffer, 0, offset, offset + n)
        offset += n
        return n
    }

    override fun write(buffer: ByteArray, length: Int) {
        if (!socket.send(buffer.toByteString(0, length))) throw IOException("the notary socket is closed", failure)
        onBytes(length.toLong(), 0)
        while (socket.queueSize() > 0) {
            failure?.let { throw IOException("the notary socket failed", it) }
            if (closed) throw IOException("the notary socket was closed")
            LockSupport.parkNanos(DRAIN_POLL_NANOS)
        }
    }

    override fun shutdownOutput() {
        socket.close(NORMAL_CLOSE, null)
    }

    override fun close() {
        closed = true
        socket.cancel()
        end(Chunk.Failed)
    }

    /** The queue of the transport's send side, in bytes: what [write] waits to drain. */
    internal fun queueSize(): Long = socket.queueSize()

    /** Ends [read] with [chunk] even when the queue is full: a stale frame is worth nothing once the socket ended. */
    private fun end(chunk: Chunk) {
        while (!incoming.offer(chunk)) incoming.poll()
    }

    private sealed interface Chunk {
        class Data(val bytes: ByteArray) : Chunk

        data object End : Chunk

        data object Failed : Chunk
    }

    companion object {
        private const val NORMAL_CLOSE = 1000
        private const val INCOMING_FRAMES = 256
        private const val DRAIN_POLL_NANOS = 100_000L
        private const val OFFER_WAIT_MS = 100L

        /**
         * Opens the notary's socket at [url] offering [subprotocol] and `bearer.<token>` (the DMarket token, opaque to
         * the notary), and waits up to [connectTimeoutMs] for the `101`. Throws [NotaryConnectException] when the
         * notary refuses or does not answer. [client]'s read timeout is lifted: a proof's phases can be quiet longer
         * than an HTTP call.
         */
        fun connect(
            client: OkHttpClient,
            url: String,
            subprotocol: String,
            token: String,
            connectTimeoutMs: Long,
            onBytes: (sent: Long, received: Long) -> Unit = { _, _ -> },
        ): OkHttpNotaryTransport {
            val transport = OkHttpNotaryTransport(onBytes)
            val request = Request.Builder().url(url).header("Sec-WebSocket-Protocol", "$subprotocol, bearer.$token").build()
            val socketClient = client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).callTimeout(0, TimeUnit.MILLISECONDS).build()
            transport.socket = socketClient.newWebSocket(request, transport.listener)
            if (!transport.opened.await(connectTimeoutMs, TimeUnit.MILLISECONDS)) {
                transport.socket.cancel()
                throw NotaryConnectException(null, "the notary did not answer the upgrade within $connectTimeoutMs ms")
            }
            if (!transport.open) {
                val status = transport.upgradeStatus
                val cause = transport.failure?.let { it.message ?: it::class.simpleName }
                throw NotaryConnectException(
                    status,
                    status?.let { "the notary answered the upgrade with HTTP $it" } ?: "no answer to the upgrade: $cause",
                )
            }
            return transport
        }
    }
}
