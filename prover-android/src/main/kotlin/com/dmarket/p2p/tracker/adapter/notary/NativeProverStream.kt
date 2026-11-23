package com.dmarket.p2p.tracker.adapter.notary

import java.util.concurrent.atomic.AtomicBoolean

/**
 * One connection a prover stream runs over: the notary's authenticated WSS (its binary payload) or the target's
 * raw TCP. Blocking calls, made only from the stream's two worker threads.
 */
interface NativeProverTransport {
    /** Blocks until bytes arrive; the count read into [buffer], or -1 at the end of the stream. */
    fun read(buffer: ByteArray): Int

    /** Blocks until [length] bytes of [buffer] are handed to the connection. */
    fun write(buffer: ByteArray, length: Int)

    /** Gracefully closes the write side; reading continues. */
    fun shutdownOutput()

    /** Aborts the connection, so that a blocked [read] returns or throws. Idempotent. */
    fun close()
}

/**
 * A `PcStream` over a [transport]. The C side keeps a bounded read queue and write queue (each [queueCapacity]
 * bytes) and answers the prover's callbacks from them alone; two daemon threads here move bytes between those
 * queues and the [transport]: a reader (connection -> prover) and a writer (prover -> connection, then the write
 * side's shutdown once the prover closes). Reads progress independently from pending writes.
 *
 * Pass [handle] to one issuance, then [close] it, also after a failure. The prover's release (at the end of the
 * issuance, on cancellation, timeout or failure, or when an unused handle is closed) stops both threads and closes
 * the [transport]; [awaitStopped] waits for that.
 */
class NativeProverStream(private val transport: NativeProverTransport, queueCapacity: Int = DEFAULT_QUEUE_CAPACITY) : AutoCloseable {
    private val queue = NativeProverBridge.queueNew(queueCapacity, queueCapacity)

    /** The `PcStreamHandle` for [NativeProverBridge.issueMpc] / [NativeProverBridge.issueProxy]. */
    val handle: Long =
        try {
            NativeProverBridge.streamNew(queue)
        } catch (e: Throwable) {
            NativeProverBridge.queueFree(queue)
            throw e
        }

    private val closed = AtomicBoolean()
    private val reader = Thread(::pumpReads, "prover-stream-read").apply { isDaemon = true }
    private val writer = Thread(::pumpWrites, "prover-stream-write").apply { isDaemon = true }

    init {
        reader.start()
        writer.start()
    }

    /** Frees [handle]: after the issuance returns, or to release a stream that was never issued. */
    override fun close() {
        if (closed.compareAndSet(false, true)) NativeProverBridge.streamFree(handle)
    }

    /** Whether the workers stopped within [timeoutMs]: they stop on the prover's release. */
    fun awaitStopped(timeoutMs: Long): Boolean {
        writer.join(timeoutMs)
        return !writer.isAlive
    }

    private fun pumpReads() {
        val buffer = ByteArray(READ_CHUNK)
        try {
            while (true) {
                val n = transport.read(buffer)
                if (n < 0) {
                    NativeProverBridge.queueEndRead(queue)
                    return
                }
                if (!NativeProverBridge.queueOfferRead(queue, buffer, 0, n)) return
            }
        } catch (_: Exception) {
            // A read the release aborted (close() below) is not a transport failure.
            if (!NativeProverBridge.queueReleased(queue)) NativeProverBridge.queueFail(queue)
        }
    }

    private fun pumpWrites() {
        val buffer = ByteArray(WRITE_CHUNK)
        try {
            while (true) {
                when (val n = NativeProverBridge.queueTakeWrite(queue, buffer)) {
                    -2 -> break
                    -1 -> {
                        transport.shutdownOutput()
                        NativeProverBridge.queueCloseDone(queue)
                    }
                    else -> {
                        transport.write(buffer, n)
                        NativeProverBridge.queueAckWrite(queue, n)
                    }
                }
            }
        } catch (_: Exception) {
            NativeProverBridge.queueFail(queue)
        } finally {
            runCatching { transport.close() }
            reader.join()
            NativeProverBridge.queueFree(queue)
        }
    }

    private companion object {
        const val DEFAULT_QUEUE_CAPACITY = 64 * 1024
        const val READ_CHUNK = 16 * 1024
        const val WRITE_CHUNK = 4096
    }
}
