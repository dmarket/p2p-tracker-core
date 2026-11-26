package com.dmarket.p2p.tracker.adapter.notary

import okhttp3.OkHttpClient
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The notary and target connections against local servers: a minimal RFC 6455 server (MockWebServer is not a
 * dependency of core) and a plain TCP one.
 */
class NotaryTransportsTest {
    private val client = OkHttpClient()
    private val servers = mutableListOf<AutoCloseable>()

    @AfterTest
    fun tearDown() = servers.forEach { runCatching { it.close() } }

    private fun notary(refuseWith: Int? = null) = TinyWebSocketServer(refuseWith).also { servers += it }

    private fun connect(server: TinyWebSocketServer) =
        OkHttpNotaryTransport.connect(client, server.url, "tlsn.notary.v2", TOKEN, connectTimeoutMs = 5_000)

    @Test
    fun the_upgrade_offers_the_marker_and_the_bearer_and_binary_frames_reach_read() {
        val server = notary()
        val transport = connect(server)

        assertEquals("tlsn.notary.v2, bearer.$TOKEN", server.headers.get(5, TimeUnit.SECONDS)["sec-websocket-protocol"])
        assertEquals("tlsn.notary.v2", transport.selectedProtocol)
        server.sendBinary(byteArrayOf(1, 2, 3))
        val buffer = ByteArray(16)
        assertEquals(3, transport.read(buffer))
        assertContentEquals(byteArrayOf(1, 2, 3), buffer.copyOf(3))
        transport.close()
    }

    @Test
    fun a_write_returns_only_once_okhttps_send_queue_is_drained() {
        val server = notary()
        val transport = connect(server)
        val payload = ByteArray(2 * 1024 * 1024) { it.toByte() }

        transport.write(payload, payload.size)

        assertEquals(0L, transport.queueSize(), "flush may answer only after the queue drained to the socket")
        var received = 0
        while (received < payload.size) received += assertNotNull(server.received.poll(5, TimeUnit.SECONDS)).size
        assertEquals(payload.size, received)
        transport.close()
    }

    @Test
    fun the_notarys_close_is_the_end_of_the_stream_and_keeps_its_code() {
        val server = notary()
        val transport = connect(server)
        server.sendBinary(byteArrayOf(9))
        server.sendClose(4001)

        val buffer = ByteArray(8)
        assertEquals(1, transport.read(buffer))
        assertEquals(-1, transport.read(buffer))
        assertEquals(-1, transport.read(buffer))
        assertEquals(4001, transport.closeCode)
        transport.close()
    }

    @Test
    fun the_notarys_close_on_a_full_queue_keeps_every_frame_before_the_end() {
        val server = notary()
        val transport = connect(server)
        // Exactly the queue's capacity, then the close: onClosing and onClosed both arrive while nothing is read.
        repeat(256) { server.sendBinary(byteArrayOf(it.toByte())) }
        server.sendClose(1000)
        assertTrue(server.closeFrame.await(5, TimeUnit.SECONDS), "the close was handled before the first read")
        Thread.sleep(200)

        val buffer = ByteArray(1)
        val frames = generateSequence { transport.read(buffer).takeIf { it > 0 }?.let { buffer[0].toInt() and 0xFF } }.toList()

        assertEquals((0 until 256).toList(), frames)
        assertEquals(-1, transport.read(buffer))
        transport.close()
    }

    @Test
    fun shutting_the_output_sends_a_close_frame_and_reading_goes_on() {
        val server = notary()
        val transport = connect(server)
        transport.shutdownOutput()

        assertTrue(server.closeFrame.await(5, TimeUnit.SECONDS))
        server.sendBinary(byteArrayOf(7))
        assertEquals(1, transport.read(ByteArray(4)))
        transport.close()
    }

    @Test
    fun release_closes_the_socket_and_ends_a_pending_read() {
        val server = notary()
        val transport = connect(server)
        val read = CompletableFuture<Result<Int>>()
        thread { read.complete(runCatching { transport.read(ByteArray(4)) }) }

        transport.close()

        assertTrue(read.get(5, TimeUnit.SECONDS).isFailure)
        assertTrue(server.disconnected.await(5, TimeUnit.SECONDS), "the notary's socket is closed")
    }

    @Test
    fun a_refused_upgrade_keeps_its_status_and_an_absent_notary_has_none() {
        val refused = assertFailsWith<NotaryConnectException> { connect(notary(refuseWith = 401)) }
        assertEquals(401, refused.upgradeStatus)

        val port = ServerSocket(0).use { it.localPort }
        val absent = assertFailsWith<NotaryConnectException> {
            OkHttpNotaryTransport.connect(client, "ws://127.0.0.1:$port/", "tlsn.notary.v2", TOKEN, connectTimeoutMs = 5_000)
        }
        assertNull(absent.upgradeStatus)
    }

    @Test
    fun a_close_while_the_upgrade_is_pending_ends_the_connect_at_once() {
        // Accepts the TCP connection and never answers the upgrade.
        val silent = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).also { servers += it }
        thread(isDaemon = true) { runCatching { silent.accept().also { servers += it } } }
        val started = CompletableFuture<OkHttpNotaryTransport>()
        val connect = CompletableFuture<Result<OkHttpNotaryTransport>>()
        thread {
            connect.complete(
                runCatching {
                    OkHttpNotaryTransport.connect(
                        client,
                        "ws://127.0.0.1:${silent.localPort}/",
                        "tlsn.notary.v2",
                        TOKEN,
                        connectTimeoutMs = 30_000,
                        onStarted = started::complete,
                    )
                },
            )
        }

        started.get(5, TimeUnit.SECONDS).close()

        val failure = assertIs<NotaryConnectException>(connect.get(5, TimeUnit.SECONDS).exceptionOrNull())
        assertNull(failure.upgradeStatus)
    }

    @Test
    fun the_target_is_dialled_on_the_first_write_and_a_read_before_it_waits() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).also { servers += it }
        val accepted = CompletableFuture<Socket>()
        thread { accepted.complete(runCatching { server.accept() }.getOrNull()) }
        val target = LazyTcpTransport(SocketFactory.getDefault(), "127.0.0.1", server.localPort, 5_000)
        val read = CompletableFuture<Int>()
        val buffer = ByteArray(8)
        thread { read.complete(target.read(buffer)) }

        Thread.sleep(200)
        assertTrue(!accepted.isDone && !read.isDone, "nothing dials before the first write")
        target.write(byteArrayOf(5, 6), 2)
        val peer = accepted.get(5, TimeUnit.SECONDS)
        assertEquals(5, peer.getInputStream().read())
        peer.getOutputStream().write(byteArrayOf(42))
        assertEquals(1, read.get(5, TimeUnit.SECONDS))
        assertEquals(42, buffer[0].toInt())
        target.close()
        peer.close()
    }

    @Test
    fun closing_an_undialled_target_ends_its_read_and_a_failed_dial_names_why() {
        val target = LazyTcpTransport(SocketFactory.getDefault(), "127.0.0.1", 1, 5_000)
        val read = CompletableFuture<Int>()
        thread { read.complete(target.read(ByteArray(4))) }
        target.close()
        assertEquals(-1, read.get(5, TimeUnit.SECONDS))

        val port = ServerSocket(0).use { it.localPort }
        val unreachable = LazyTcpTransport(SocketFactory.getDefault(), "127.0.0.1", port, 5_000)
        assertFailsWith<IOException> { unreachable.write(byteArrayOf(1), 1) }
        assertNotNull(unreachable.targetError)
    }

    @Test
    fun closing_the_target_ends_a_dial_in_progress_and_one_closed_before_the_connect() {
        val connectStarted = CountDownLatch(1)
        val connects = AtomicInteger()
        lateinit var onCreate: () -> Unit
        val factory = object : SocketFactory() {
            override fun createSocket(): Socket = HangingSocket(connectStarted, connects).also { onCreate() }

            override fun createSocket(host: String, port: Int) = throw UnsupportedOperationException()

            override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int) =
                throw UnsupportedOperationException()

            override fun createSocket(host: InetAddress, port: Int) = throw UnsupportedOperationException()

            override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int) =
                throw UnsupportedOperationException()
        }

        onCreate = {}
        val dialling = LazyTcpTransport(factory, "127.0.0.1", 9, 30_000)
        val write = CompletableFuture<Result<Unit>>()
        thread { write.complete(runCatching { dialling.write(byteArrayOf(1), 1) }) }
        assertTrue(connectStarted.await(5, TimeUnit.SECONDS))
        dialling.close()
        assertIs<IOException>(write.get(2, TimeUnit.SECONDS).exceptionOrNull(), "the close ended the dial, not its timeout")

        // A close that lands after the dial's own check but before the connect.
        lateinit var late: LazyTcpTransport
        onCreate = { late.close() }
        late = LazyTcpTransport(factory, "127.0.0.1", 9, 30_000)
        assertFailsWith<IOException> { late.write(byteArrayOf(1), 1) }
        assertEquals(1, connects.get(), "the late-closed target never connects")
    }

    /** Its connect waits until the socket is closed, as a dial to a host that does not answer. */
    private class HangingSocket(private val connectStarted: CountDownLatch, private val connects: AtomicInteger) : Socket() {
        private val closedLatch = CountDownLatch(1)

        override fun connect(endpoint: SocketAddress, timeout: Int) {
            connects.incrementAndGet()
            connectStarted.countDown()
            if (!closedLatch.await(10, TimeUnit.SECONDS)) throw SocketTimeoutException("connect timed out")
            throw SocketException("Socket is closed")
        }

        override fun close() {
            closedLatch.countDown()
            super.close()
        }
    }

    /** One connection: answers the upgrade (or [refuseWith]), collects binary payloads, sends frames on demand. */
    private class TinyWebSocketServer(private val refuseWith: Int?) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        private val connection = CompletableFuture<Socket>()
        val url = "ws://127.0.0.1:${server.localPort}/"
        val headers = CompletableFuture<Map<String, String>>()
        val received = LinkedBlockingQueue<ByteArray>()
        val closeFrame = CountDownLatch(1)
        val disconnected = CountDownLatch(1)

        init {
            thread(isDaemon = true) { runCatching { serve() }.onFailure { disconnected.countDown() } }
        }

        private fun serve() {
            val socket = server.accept()
            connection.complete(socket)
            val input = DataInputStream(socket.getInputStream())
            val head = readHead(input)
            headers.complete(head)
            val out = socket.getOutputStream()
            if (refuseWith != null) {
                out.write("HTTP/1.1 $refuseWith Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
                socket.close()
                return
            }
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest((head.getValue("sec-websocket-key") + GUID).toByteArray()),
            )
            out.write(
                (
                    "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: $accept\r\nSec-WebSocket-Protocol: tlsn.notary.v2\r\n\r\n"
                    ).toByteArray(),
            )
            out.flush()
            while (true) {
                val first = input.read()
                if (first < 0) break
                val opcode = first and 0x0F
                val second = input.readUnsignedByte()
                var length = (second and 0x7F).toLong()
                if (length == 126L) length = input.readUnsignedShort().toLong()
                if (length == 127L) length = input.readLong()
                val mask = ByteArray(4).also { input.readFully(it) }
                val payload = ByteArray(length.toInt()).also { input.readFully(it) }
                for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                when (opcode) {
                    BINARY -> received.put(payload)
                    CLOSE -> closeFrame.countDown()
                }
            }
            disconnected.countDown()
        }

        fun sendBinary(bytes: ByteArray) = frame(BINARY, bytes)

        fun sendClose(code: Int) = frame(CLOSE, byteArrayOf((code shr 8).toByte(), code.toByte()))

        private fun frame(opcode: Int, payload: ByteArray) {
            val out: OutputStream = connection.get(5, TimeUnit.SECONDS).getOutputStream()
            out.write(0x80 or opcode)
            out.write(payload.size) // short frames only
            out.write(payload)
            out.flush()
        }

        override fun close() {
            runCatching { connection.getNow(null)?.close() }
            server.close()
        }

        private fun readHead(input: InputStream): Map<String, String> {
            val lines = mutableListOf<String>()
            val line = StringBuilder()
            while (true) {
                val c = input.read()
                check(c >= 0) { "the client closed during the upgrade" }
                if (c == '\n'.code) {
                    val text = line.toString().trimEnd('\r')
                    if (text.isEmpty()) break
                    lines += text
                    line.clear()
                } else {
                    line.append(c.toChar())
                }
            }
            return lines.drop(1).associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        }

        private companion object {
            const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
            const val BINARY = 2
            const val CLOSE = 8
        }
    }

    private companion object {
        const val TOKEN = "eyJhbGciOiJIUzI1NiJ9.dmarket.sig"
    }
}
