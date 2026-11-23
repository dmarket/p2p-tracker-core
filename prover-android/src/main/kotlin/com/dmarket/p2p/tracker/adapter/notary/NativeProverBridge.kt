package com.dmarket.p2p.tracker.adapter.notary

import android.os.Looper

/**
 * The steam-provenance C ABI (`provenance_client.h`, ABI v1) through `libprovenance_jni.so`. Handles are the C
 * pointers as `Long`; `0` is NULL. Ownership follows the C contract: free every handle with its own function,
 * free a stream handle after the issuance that used it (also after a failure), and never free a handle that is
 * in use. Owned buffers are copied into Java arrays and freed before a call returns.
 *
 * A failing call throws [NativeProverException] with the ABI status; nothing here ends the process. Issuance
 * blocks for up to its deadline and refuses the main thread.
 */
object NativeProverBridge {
    init {
        System.loadLibrary("provenance_client_cffi")
        System.loadLibrary("provenance_jni")
    }

    /** The ABI version the loaded library implements: 1. */
    @JvmStatic external fun abiVersion(): Int

    @JvmStatic external fun contextNew(): Long

    @JvmStatic external fun contextFree(context: Long)

    /** A one-shot cancel handle; keep it alive until the issuance that uses it returns. */
    @JvmStatic external fun cancelNew(): Long

    /** Cancels the issuance using [cancel]; callable from any thread. */
    @JvmStatic external fun cancel(cancel: Long)

    @JvmStatic external fun cancelFree(cancel: Long)

    /** pc_wake: harmless for an unknown, late or repeated token. */
    @JvmStatic external fun wake(token: Long)

    /**
     * Issues an MPC attestation over a [notary] and a [target] stream ([NativeProverStream.handle]).
     * [config] is the UTF-8 JSON of the C ABI's config, [deadlineMs] in `1..86_400_000`, [cancel] `0` for none.
     * Returns the material handle; free it with [materialFree].
     */
    fun issueMpc(
        context: Long,
        config: ByteArray,
        body: ByteArray?,
        notary: Long,
        target: Long,
        deadlineMs: Long,
        cancel: Long = 0,
        progress: NativeProverProgress? = null,
    ): Long {
        requireWorkerThread()
        return nativeIssueMpc(context, config, body, notary, target, deadlineMs, cancel, progress)
    }

    /** Issues a proxy attestation over one [notary] stream; otherwise as [issueMpc]. */
    fun issueProxy(
        context: Long,
        config: ByteArray,
        body: ByteArray?,
        notary: Long,
        deadlineMs: Long,
        cancel: Long = 0,
        progress: NativeProverProgress? = null,
    ): Long {
        requireWorkerThread()
        return nativeIssueProxy(context, config, body, notary, deadlineMs, cancel, progress)
    }

    @JvmStatic external fun streamFree(stream: Long)

    @JvmStatic external fun materialFree(material: Long)

    /** The proven HTTP response of freshly issued material (not restored material). */
    @JvmStatic external fun response(material: Long): NativeResponse

    /** `[name, value]` of the response header at [index], in order. */
    @JvmStatic external fun responseHeader(material: Long, index: Long): Array<ByteArray>

    /** `[attestation, secrets]`; save both. The secrets hold the private transcript. */
    @JvmStatic external fun materialExport(material: Long): Array<ByteArray>

    /** Restores exported material. Decoding is not authentication: the validator checks trust. */
    @JvmStatic external fun materialImport(attestation: ByteArray, secrets: ByteArray): Long

    /** The presentation for [policy] (UTF-8 JSON, `null` for the core defaults); offline. */
    @JvmStatic external fun present(material: Long, policy: ByteArray?): ByteArray

    // The stream queue, driven by NativeProverStream (provenance_jni.c documents each call).
    @JvmStatic external fun queueNew(readCapacity: Int, writeCapacity: Int): Long

    @JvmStatic external fun streamNew(queue: Long): Long

    @JvmStatic external fun queueFree(queue: Long)

    @JvmStatic external fun queueOfferRead(queue: Long, bytes: ByteArray, offset: Int, length: Int): Boolean

    @JvmStatic external fun queueEndRead(queue: Long)

    @JvmStatic external fun queueFail(queue: Long)

    @JvmStatic external fun queueTakeWrite(queue: Long, dst: ByteArray): Int

    @JvmStatic external fun queueAckWrite(queue: Long, count: Int)

    @JvmStatic external fun queueCloseDone(queue: Long)

    @JvmStatic external fun queueReleased(queue: Long): Boolean

    @JvmStatic private external fun nativeIssueMpc(
        context: Long,
        config: ByteArray,
        body: ByteArray?,
        notary: Long,
        target: Long,
        deadlineMs: Long,
        cancel: Long,
        progress: NativeProverProgress?,
    ): Long

    @JvmStatic private external fun nativeIssueProxy(
        context: Long,
        config: ByteArray,
        body: ByteArray?,
        notary: Long,
        deadlineMs: Long,
        cancel: Long,
        progress: NativeProverProgress?,
    ): Long

    private fun requireWorkerThread() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "prover issuance blocks; call it off the main thread" }
    }
}

/** Issuance progress: [mode] 0 MPC / 1 proxy, stage [index] 0–6, an advisory [fraction]. Any thread. */
fun interface NativeProverProgress {
    fun onProgress(mode: Int, index: Int, stage: String, fraction: Float)
}

/** The proven HTTP response: [body] is a copy; headers through [NativeProverBridge.responseHeader]. */
class NativeResponse(val status: Int, val headerCount: Long, val body: ByteArray)

/** The C ABI's status codes (`PC_*`); [UNKNOWN] for a code this build does not know. */
enum class NativeProverStatus(val code: Int) {
    INVALID_ARGUMENT(1),
    BUSY(2),
    TRANSPORT(3),
    PROTOCOL(4),
    CODEC(5),
    PRESENTATION(6),
    TIMEOUT(7),
    CANCELLED(8),
    PANIC(9),
    INTERNAL(10),
    UNKNOWN(-1),
    ;

    companion object {
        fun of(code: Int): NativeProverStatus = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/** A failed C ABI call: [status], and the library's diagnostic (wording is not stable; do not parse it). */
class NativeProverException(val code: Int, diagnostic: ByteArray?) :
    Exception("${NativeProverStatus.of(code)} ($code)" + (diagnostic?.let { ": " + it.decodeToString() } ?: "")) {
    val status: NativeProverStatus get() = NativeProverStatus.of(code)
}
