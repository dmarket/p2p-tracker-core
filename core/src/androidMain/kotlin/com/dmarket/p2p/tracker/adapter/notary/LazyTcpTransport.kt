package com.dmarket.p2p.tracker.adapter.notary

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import javax.net.SocketFactory

/**
 * The MPC target leg: raw TCP to Steam (the prover does the TLS), dialled on the **first write**. MPC preprocessing
 * can outlast the target's idle timeout, and TLS is client-first, so nothing can arrive before the prover's
 * `ClientHello`: a [read] before the dial waits for it, as the web's `lazyTargetChannel` does. `TCP_NODELAY` is set.
 *
 * [targetError] says why the dial failed, for the proof's failure.
 */
internal class LazyTcpTransport(
    private val socketFactory: SocketFactory,
    private val host: String,
    private val port: Int,
    private val connectTimeoutMs: Int,
    private val onBytes: (sent: Long, received: Long) -> Unit = { _, _ -> },
) : NativeProverTransport {
    private val dialled = CountDownLatch(1)

    @Volatile private var socket: Socket? = null

    @Volatile private var closed = false

    @Volatile var targetError: String? = null
        private set

    override fun read(buffer: ByteArray): Int {
        dialled.await()
        val socket = socket ?: return -1
        return socket.getInputStream().read(buffer).also { if (it > 0) onBytes(0, it.toLong()) }
    }

    override fun write(buffer: ByteArray, length: Int) {
        val socket = socket ?: dial()
        socket.getOutputStream().write(buffer, 0, length)
        onBytes(length.toLong(), 0)
    }

    override fun shutdownOutput() {
        socket?.shutdownOutput()
    }

    override fun close() {
        closed = true
        runCatching { socket?.close() }
        dialled.countDown()
    }

    @Synchronized
    private fun dial(): Socket {
        socket?.let { return it }
        if (closed) throw IOException("the target connection was closed before it was dialled")
        val dialling = socketFactory.createSocket()
        try {
            dialling.tcpNoDelay = true
            dialling.connect(InetSocketAddress(host, port), connectTimeoutMs)
        } catch (e: IOException) {
            runCatching { dialling.close() }
            targetError = "could not connect to $host:$port: ${e.message ?: e::class.simpleName}"
            dialled.countDown()
            throw e
        }
        socket = dialling
        dialled.countDown()
        if (closed) runCatching { dialling.close() }
        return dialling
    }
}
