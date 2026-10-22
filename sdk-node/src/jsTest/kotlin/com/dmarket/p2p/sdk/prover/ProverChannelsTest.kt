package com.dmarket.p2p.sdk.prover

import com.dmarket.p2p.sdk.node.NodeModules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The target leg of a prover process: the transport's own Node connector, taken from the installed prover package
 * exactly as a prover process loads it, against loopback sockets; and what the SDK adds around it (the dial timeout,
 * the recorded failure) against fake channels.
 */
class ProverChannelsTest {
    private val servers = mutableListOf<dynamic>()
    private val accepted = mutableListOf<dynamic>()
    private val channels = mutableListOf<dynamic>()

    /** Everything a test opened, whether or not it got to close it: an open socket keeps the test process alive. */
    @AfterTest
    fun closeAll() {
        channels.forEach { it.close() }
        accepted.forEach { it.destroy() }
        servers.forEach { it.close() }
    }

    private fun connector() = nodeTcpConnector(checkNotNull(proverAssetsDir(null)) { "the installed prover package was not found" })

    /** A loopback server calling [onConnection] with each accepted socket; answers its port. */
    private suspend fun listen(onConnection: (dynamic) -> Unit = {}): Int {
        val server: dynamic = NodeModules.net.createServer { socket: dynamic ->
            accepted.add(socket)
            onConnection(socket)
        }
        servers.add(server)
        Promise<Unit> { resolve, _ -> server.listen(0, "127.0.0.1") { resolve(Unit) } }.await()
        return server.address().port as Int
    }

    private suspend fun pause(ms: Int) = Promise<Unit> { resolve, _ -> js("setTimeout")({ resolve(Unit) }, ms) }.await()

    private fun bytes(): dynamic = js("new Uint8Array([22, 3, 3, 0, 2, 0, 1])")

    private fun fakeChannel(write: () -> Promise<Any?> = { Promise.resolve(null) }, onClose: () -> Unit = {}): dynamic {
        val io: dynamic = js("({})")
        io.read = { Promise.resolve<Any?>(null) }
        io.write = { _: dynamic -> write() }
        io.close = {
            onClose()
            Promise.resolve<Any?>(null)
        }
        return io
    }

    private suspend fun open(address: String, record: ProverSessionRecord, timeoutMs: Int = TARGET_DIAL_TIMEOUT_MS): dynamic =
        recordedTargetChannel(address, record, timeoutMs, connector()).await().also { channels.add(it) }

    @Test
    fun the_transports_connector_carries_raw_bytes_and_reads_eof() = runTest {
        withContext(Dispatchers.Default) {
            val port = listen { socket ->
                socket.setNoDelay(true)
                socket.once("data") { data: dynamic -> socket.end(data) }
            }
            val record = ProverSessionRecord()
            val channel = open("127.0.0.1:$port", record)
            channel.write(bytes()).unsafeCast<Promise<Any?>>().await()
            val echoed: dynamic = channel.read().unsafeCast<Promise<dynamic>>().await()
            assertEquals(listOf(22, 3, 3, 0, 2, 0, 1), js("Array.from")(echoed).unsafeCast<Array<Int>>().toList())
            assertNull(channel.read().unsafeCast<Promise<Any?>>().await())
            channel.close().unsafeCast<Promise<Any?>>().await()
            assertNull(record.targetError)
        }
    }

    @Test
    fun a_refused_dial_is_recorded() = runTest {
        withContext(Dispatchers.Default) {
            val server: dynamic = NodeModules.net.createServer()
            Promise<Unit> { resolve, _ -> server.listen(0, "127.0.0.1") { resolve(Unit) } }.await()
            val port = server.address().port as Int
            Promise<Unit> { resolve, _ -> server.close { resolve(Unit) } }.await()
            val record = ProverSessionRecord()
            assertNotNull(runCatching { open("127.0.0.1:$port", record) }.exceptionOrNull())
            assertTrue("ECONNREFUSED" in record.targetError.orEmpty(), "${record.targetError}")
        }
    }

    @Test
    fun a_reset_is_recorded_from_the_read_that_sees_it() = runTest {
        withContext(Dispatchers.Default) {
            val port = listen { socket -> js("setTimeout")({ socket.resetAndDestroy() }, 20) }
            val record = ProverSessionRecord()
            val channel = open("127.0.0.1:$port", record)
            assertNotNull(runCatching { channel.read().unsafeCast<Promise<Any?>>().await() }.exceptionOrNull())
            assertTrue("ECONNRESET" in record.targetError.orEmpty(), "${record.targetError}")
            runCatching { channel.close().unsafeCast<Promise<Any?>>().await() }
        }
    }

    @Test
    fun a_dial_that_does_not_connect_in_time_fails_and_its_late_channel_is_closed() = runTest {
        withContext(Dispatchers.Default) {
            var deliver: ((Any?) -> Unit)? = null
            val pending = Promise<Any?> { resolve, _ -> deliver = resolve }
            var lateClosed = false
            val record = ProverSessionRecord()
            val opening = recordedTargetChannel("steam.test:443", record, dialTimeoutMs = 20) { _, _ -> pending }
            assertNotNull(runCatching { opening.await() }.exceptionOrNull())
            assertEquals("the Steam connection timed out after 20 ms", record.targetError)
            checkNotNull(deliver)(fakeChannel(onClose = { lateClosed = true }))
            pause(10)
            assertTrue(lateClosed)
        }
    }

    @Test
    fun the_first_socket_failure_is_recorded_but_not_one_after_the_prover_closed_the_leg() = runTest {
        withContext(Dispatchers.Default) {
            var failure = "write EPIPE"
            val io = fakeChannel(write = { Promise.reject(Throwable(failure)) })
            val record = ProverSessionRecord()
            val channel: dynamic = recordedTargetChannel("steam.test:443", record) { host, port ->
                assertEquals("steam.test" to 443, host to port)
                Promise.resolve(io)
            }.await()
            assertNotNull(runCatching { channel.write(bytes()).unsafeCast<Promise<Any?>>().await() }.exceptionOrNull())
            failure = "read ECONNRESET"
            assertNotNull(runCatching { channel.write(bytes()).unsafeCast<Promise<Any?>>().await() }.exceptionOrNull())
            assertEquals("write EPIPE", record.targetError)

            val closedRecord = ProverSessionRecord()
            val closed: dynamic = recordedTargetChannel("steam.test:443", closedRecord) { _, _ -> Promise.resolve(io) }.await()
            closed.close().unsafeCast<Promise<Any?>>().await()
            assertNotNull(runCatching { closed.write(bytes()).unsafeCast<Promise<Any?>>().await() }.exceptionOrNull())
            assertNull(closedRecord.targetError)
        }
    }
}
