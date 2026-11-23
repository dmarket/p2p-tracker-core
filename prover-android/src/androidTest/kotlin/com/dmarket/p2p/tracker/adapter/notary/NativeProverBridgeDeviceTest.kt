package com.dmarket.p2p.tracker.adapter.notary

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The bridge on a device: both libraries load, and a stream over an in-memory loopback connection carries the
 * prover's bytes both ways. No notary is reachable here, so an issuance ends in a timeout, a cancellation or a
 * protocol error; what is checked is that it ends that way, promptly, and that the stream's release stops its
 * workers and closes the connection.
 */
@RunWith(AndroidJUnit4::class)
class NativeProverBridgeDeviceTest {
    private val config = """{"abi_version":1,"server_name":"example.com"}""".toByteArray()

    /** The far end in memory: records what the prover writes, serves what [serve] queues. */
    private class Loopback : NativeProverTransport {
        val written = AtomicInteger()
        val firstWrite = CountDownLatch(1)
        val closed = AtomicBoolean()
        private val incoming = LinkedBlockingQueue<ByteArray>()

        fun serve(bytes: ByteArray) = incoming.put(bytes)

        fun end() = incoming.put(ByteArray(0))

        override fun read(buffer: ByteArray): Int {
            val chunk = incoming.take()
            if (chunk.isEmpty()) return -1
            chunk.copyInto(buffer)
            return chunk.size
        }

        override fun write(buffer: ByteArray, length: Int) {
            written.addAndGet(length)
            firstWrite.countDown()
        }

        override fun shutdownOutput() = Unit

        override fun close() {
            if (closed.compareAndSet(false, true)) end()
        }
    }

    private fun issueProxyExpectingFailure(
        stream: NativeProverStream,
        deadlineMs: Long,
        cancel: Long = 0,
        progress: NativeProverProgress? = null,
    ): NativeProverException {
        val context = NativeProverBridge.contextNew()
        try {
            NativeProverBridge.issueProxy(context, config, null, stream.handle, deadlineMs, cancel, progress)
            fail("issuance over a loopback without a notary succeeded")
            error("unreachable")
        } catch (e: NativeProverException) {
            return e
        } finally {
            stream.close()
            NativeProverBridge.contextFree(context)
        }
    }

    @Test
    fun bothLibrariesLoadAndReportAbiV1() {
        assertEquals(1, NativeProverBridge.abiVersion())
    }

    @Test
    fun contextAndCancelHandlesAreCreatedAndFreed() {
        val context = NativeProverBridge.contextNew()
        val cancel = NativeProverBridge.cancelNew()
        assertNotEquals(0L, context)
        assertNotEquals(0L, cancel)
        NativeProverBridge.cancelFree(cancel)
        NativeProverBridge.contextFree(context)
        NativeProverBridge.wake(Long.MAX_VALUE) // an unknown token is harmless
    }

    @Test
    fun closingAnUnusedStreamReleasesItAndStopsItsWorkers() {
        val transport = Loopback()
        val stream = NativeProverStream(transport)
        stream.close()
        assertTrue(stream.awaitStopped(5_000))
        assertTrue(transport.closed.get())
    }

    @Test
    fun aSilentNotaryLeavesTheReadPendingUntilTheDeadline() {
        val transport = Loopback()
        val stream = NativeProverStream(transport)
        val started = System.nanoTime()
        val e = issueProxyExpectingFailure(stream, deadlineMs = 1_500)
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        android.util.Log.i("ProverDeviceTest", "silent: ${e.status} ${e.message} written=${transport.written.get()} in ${elapsedMs}ms")
        assertEquals(NativeProverStatus.TIMEOUT, e.status)
        assertTrue("timed out after $elapsedMs ms", elapsedMs in 1_400..10_000)
        assertTrue(stream.awaitStopped(5_000))
        assertTrue(transport.closed.get())
    }

    @Test
    fun bytesServedAfterAPendingReadWakeTheProver() {
        val transport = Loopback()
        val stream = NativeProverStream(transport)
        // Served only once the prover is parked waiting for the notary: reaching it at all needs pc_wake. It then
        // fails on the garbage (PROTOCOL) after the 300 ms delay, well before its deadline.
        Thread {
            transport.firstWrite.await(10, TimeUnit.SECONDS)
            Thread.sleep(300)
            transport.serve(ByteArray(64) { 0xFF.toByte() })
            transport.end()
        }.start()
        val started = System.nanoTime()
        val e = issueProxyExpectingFailure(stream, deadlineMs = 60_000)
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        android.util.Log.i("ProverDeviceTest", "wake: ${e.status} ${e.message} written=${transport.written.get()} in ${elapsedMs}ms")
        assertTrue("prover wrote nothing", transport.written.get() > 0)
        assertTrue("ended as ${e.status} after $elapsedMs ms", e.status != NativeProverStatus.TIMEOUT && elapsedMs in 250..30_000)
        assertTrue(stream.awaitStopped(5_000))
    }

    @Test
    fun cancellingEndsTheIssuanceBeforeItsDeadline() {
        val transport = Loopback()
        val stream = NativeProverStream(transport)
        val cancel = NativeProverBridge.cancelNew()
        try {
            Thread {
                Thread.sleep(300)
                NativeProverBridge.cancel(cancel)
            }.start()
            val started = System.nanoTime()
            val e = issueProxyExpectingFailure(stream, deadlineMs = 60_000, cancel = cancel)
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertEquals(NativeProverStatus.CANCELLED, e.status)
            assertTrue("cancelled after $elapsedMs ms", elapsedMs < 10_000)
            assertTrue(stream.awaitStopped(5_000))
        } finally {
            NativeProverBridge.cancelFree(cancel)
        }
    }

    @Test
    fun issuanceRefusesTheMainThread() {
        var refused = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            refused = runCatching { NativeProverBridge.issueProxy(0, config, null, 0, 1_000) }
                .exceptionOrNull() is IllegalStateException
        }
        assertTrue(refused)
    }

    @Test
    fun malformedStoredMaterialIsACodecErrorNotACrash() {
        val e = runCatching { NativeProverBridge.materialImport(ByteArray(16) { 7 }, ByteArray(16) { 9 }) }
            .exceptionOrNull() as NativeProverException
        assertEquals(NativeProverStatus.CODEC, e.status)
    }

    @Test
    fun presentingWithoutMaterialIsAnInvalidArgument() {
        val e = runCatching { NativeProverBridge.present(0, null) }.exceptionOrNull() as NativeProverException
        assertEquals(NativeProverStatus.INVALID_ARGUMENT, e.status)
    }
}
