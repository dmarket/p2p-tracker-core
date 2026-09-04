package com.dmarket.p2p.tracker.notary

import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The target leg dials on first WRITE, and these tests exist because getting that wrong is invisible.
 *
 * The bug being fixed was itself invisible for three rounds: the prover opened this socket, spent 4-23 s in
 * MPC pre-processing with the notary, and only then sent its `ClientHello` — by which time the proxy's
 * upstream had dropped an idle connection and the wasm trapped writing to it. Nothing about "connect early" vs
 * "connect late" shows up in a proof's outcome, only in whether it survives a slow notary, so the semantics
 * are pinned here rather than left to a live run.
 */
class LazyTargetChannelTest {

    /** A stand-in for the transport's `WsIoChannel`, recording what the prover did to it. */
    private class FakeChannel {
        val writes = mutableListOf<String>()
        var closes = 0
        var reads = 0
        val js: dynamic = js("({})")

        init {
            js.write = { data: dynamic ->
                writes += data as String
                Promise.resolve(Unit)
            }
            js.read = {
                reads++
                Promise.resolve("frame")
            }
            js.close = {
                closes++
                Promise.resolve(Unit)
            }
        }
    }

    @Test
    fun nothing_is_dialled_until_the_first_write() = runTest {
        var dials = 0
        val fake = FakeChannel()
        val channel = lazyTargetChannel(connect = {
            dials++
            Promise.resolve(fake.js)
        })

        // Constructing it must not touch the network: the whole point is that this socket's age starts when
        // the prover needs it, not when the proof does.
        assertEquals(0, dials)

        channel.write("hello").unsafeCast<Promise<Any?>>().await()
        assertEquals(1, dials)
        assertEquals(listOf("hello"), fake.writes)

        // And only once, however many writes follow.
        channel.write("again").unsafeCast<Promise<Any?>>().await()
        assertEquals(1, dials)
        assertEquals(listOf("hello", "again"), fake.writes)
    }

    @Test
    fun a_read_issued_before_any_write_parks_rather_than_dialling() = runTest {
        var dials = 0
        val fake = FakeChannel()
        val channel = lazyTargetChannel(connect = {
            dials++
            Promise.resolve(fake.js)
        })

        // TLS is client-first, so a read before the ClientHello cannot be missing data — it must WAIT, not
        // force the dial (which would restore the very idleness this change removes).
        val parked = channel.read().unsafeCast<Promise<Any?>>()
        assertEquals(0, dials)
        assertEquals(0, fake.reads)

        channel.write("client-hello").unsafeCast<Promise<Any?>>().await()
        assertEquals("frame", parked.await())
        assertEquals(1, dials)
        assertEquals(1, fake.reads)
    }

    @Test
    fun closing_before_any_write_never_dials_and_reports_eof() = runTest {
        var dials = 0
        val fake = FakeChannel()
        val channel = lazyTargetChannel(connect = {
            dials++
            Promise.resolve(fake.js)
        })

        val parked = channel.read().unsafeCast<Promise<Any?>>()
        channel.close().unsafeCast<Promise<Any?>>().await()

        // A proof abandoned before the target leg was ever needed must not dial on its way out — and a reader
        // parked on it must be released, or the caller hangs on a channel nobody will ever feed. `null` IS the
        // EOF signal on this interface (the Rust side maps it to Ok(0)).
        assertEquals(0, dials)
        assertEquals(0, fake.closes)
        assertEquals(null, parked.await())
        assertEquals(null, channel.read().unsafeCast<Promise<Any?>>().await())
    }

    @Test
    fun closing_after_a_write_closes_the_real_channel() = runTest {
        val fake = FakeChannel()
        val channel = lazyTargetChannel(connect = { Promise.resolve(fake.js) })

        channel.write("x").unsafeCast<Promise<Any?>>().await()
        channel.close().unsafeCast<Promise<Any?>>().await()
        assertEquals(1, fake.closes)
    }

    @Test
    fun a_write_after_close_is_refused_instead_of_dialling() = runTest {
        var dials = 0
        val channel = lazyTargetChannel(connect = {
            dials++
            Promise.resolve(FakeChannel().js)
        })

        channel.close().unsafeCast<Promise<Any?>>().await()
        assertFailsWith<IllegalStateException> { channel.write("late").unsafeCast<Promise<Any?>>().await() }
        assertEquals(0, dials)
    }

    @Test
    fun a_failed_dial_rejects_the_write_and_is_traced() = runTest {
        val traces = mutableListOf<String>()
        val channel = lazyTargetChannel(
            connect = { Promise.reject(IllegalStateException("proxy unreachable")) },
            onTrace = { traces += it },
        )

        // The rejection's next stop is inside the wasm, which reports it as whatever it makes of a failed
        // write — so the trace is the only thing that keeps "the proxy was never reachable" distinguishable
        // from "the prover died on a live channel". That distinction is the cost of dialling late, and this is
        // what pays it.
        assertFailsWith<IllegalStateException> { channel.write("x").unsafeCast<Promise<Any?>>().await() }
        assertTrue(traces.any { it.contains("target dial FAILED") && it.contains("proxy unreachable") }, "$traces")
    }

    @Test
    fun a_throwing_trace_sink_cannot_break_the_channel() = runTest {
        val fake = FakeChannel()
        val channel = lazyTargetChannel(
            connect = { Promise.resolve(fake.js) },
            onTrace = { error("the host's logger is broken") },
        )

        // Same rule as the progress sink: this runs on the proof's own path, so a logging defect must not
        // become a failed proof.
        channel.write("x").unsafeCast<Promise<Any?>>().await()
        assertEquals(listOf("x"), fake.writes)
    }
}
