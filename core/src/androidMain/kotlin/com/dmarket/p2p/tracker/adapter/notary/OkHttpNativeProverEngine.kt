package com.dmarket.p2p.tracker.adapter.notary

import com.dmarket.p2p.tracker.notary.IssuanceMode
import com.dmarket.p2p.tracker.notary.NativeIssuance
import com.dmarket.p2p.tracker.notary.NativeIssuanceListener
import com.dmarket.p2p.tracker.notary.NativeIssuanceOutcome
import com.dmarket.p2p.tracker.notary.NativeProverEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import kotlin.concurrent.thread

/**
 * The Android [NativeProverEngine]: [NativeProverBridge] (the steam-provenance C ABI through JNI), the notary over
 * [client]'s WebSocket ([OkHttpNotaryTransport]) and, for MPC, Steam over TCP ([LazyTcpTransport]) through [client]'s
 * socket factory. [client] is the host's `OkHttpClient`, so the notary call takes the app's network setup.
 *
 * Each proof gets its own context, cancel handle and worker thread: a context runs one issuance at a time, and an
 * internal panic poisons it. A cancelled call closes the connections and cancels the issuance through the bridge, then
 * waits for the worker to free every handle before the cancellation goes on.
 */
class OkHttpNativeProverEngine(private val client: OkHttpClient, private val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS) :
    NativeProverEngine {
    private val loadFailure: String? by lazy {
        try {
            NativeProverBridge.abiVersion().takeIf { it != ABI_VERSION }?.let { "the native prover implements ABI $it, not $ABI_VERSION" }
        } catch (e: LinkageError) {
            // No .so for this device's ABI: ExceptionInInitializerError first, NoClassDefFoundError afterwards.
            "the native prover did not load: ${e.message ?: e::class.simpleName}"
        }
    }

    override fun unavailableReason(): String? = loadFailure

    override suspend fun issue(issuance: NativeIssuance, listener: NativeIssuanceListener): NativeIssuanceOutcome {
        val run = Run(issuance, listener)
        val done = CompletableDeferred<NativeIssuanceOutcome>()
        thread(name = "prover-issue", isDaemon = true) { done.complete(run.execute()) }
        return try {
            done.await()
        } catch (e: CancellationException) {
            run.cancel()
            withContext(NonCancellable) { done.await() }
            throw e
        }
    }

    /** One issuance on its worker thread; [cancel] may come from any thread. */
    private inner class Run(private val issuance: NativeIssuance, private val listener: NativeIssuanceListener) {
        private val cancelHandle = NativeProverBridge.cancelNew()

        @Volatile private var cancelled = false

        private var freed = false

        @Volatile private var notary: OkHttpNotaryTransport? = null

        @Volatile private var target: LazyTcpTransport? = null

        fun cancel(): Unit = synchronized(this) {
            if (cancelled || freed) return
            cancelled = true
            NativeProverBridge.cancel(cancelHandle)
            notary?.close()
            target?.close()
        }

        fun execute(): NativeIssuanceOutcome = try {
            connectAndIssue()
        } catch (e: Throwable) {
            NativeIssuanceOutcome(diagnostic = e.message ?: e::class.simpleName)
        } finally {
            synchronized(this) {
                freed = true
                NativeProverBridge.cancelFree(cancelHandle)
            }
            notary?.close()
            target?.close()
        }

        private fun connectAndIssue(): NativeIssuanceOutcome {
            val notary = try {
                OkHttpNotaryTransport.connect(
                    client,
                    issuance.notaryUrl,
                    issuance.subprotocol,
                    issuance.notaryToken,
                    connectTimeoutMs,
                    listener::onBytes,
                )
            } catch (e: NotaryConnectException) {
                return NativeIssuanceOutcome(upgradeStatus = e.upgradeStatus, notaryConnected = false, diagnostic = e.message)
            }
            val target = if (issuance.mode == IssuanceMode.MPC) {
                LazyTcpTransport(
                    client.socketFactory,
                    issuance.targetHost,
                    issuance.targetPort,
                    connectTimeoutMs.toInt(),
                    listener::onBytes,
                )
            } else {
                null
            }
            synchronized(this) {
                this.notary = notary
                this.target = target
                if (cancelled) return NativeIssuanceOutcome(status = NativeIssuanceOutcome.STATUS_CANCELLED)
            }
            val context = NativeProverBridge.contextNew()
            try {
                return issue(context, notary, target)
            } finally {
                NativeProverBridge.contextFree(context)
            }
        }

        private fun issue(context: Long, notary: OkHttpNotaryTransport, target: LazyTcpTransport?): NativeIssuanceOutcome {
            val notaryStream = NativeProverStream(notary)
            val targetStream = target?.let {
                runCatching { NativeProverStream(it) }.getOrElse { e ->
                    notaryStream.close()
                    throw e
                }
            }
            var material = 0L
            try {
                val config = issuance.config.encodeToByteArray()
                val deadlineMs = issuance.deadline.inWholeMilliseconds
                val progress = NativeProverProgress { _, index, stage, fraction -> listener.onStage(index, stage, fraction) }
                material = if (targetStream != null) {
                    NativeProverBridge.issueMpc(
                        context,
                        config,
                        issuance.body,
                        notaryStream.handle,
                        targetStream.handle,
                        deadlineMs,
                        cancelHandle,
                        progress,
                    )
                } else {
                    NativeProverBridge.issueProxy(context, config, issuance.body, notaryStream.handle, deadlineMs, cancelHandle, progress)
                }
                val status = NativeProverBridge.response(material).status
                val presentation = NativeProverBridge.present(material, issuance.policy.encodeToByteArray())
                return NativeIssuanceOutcome(presentation = presentation, targetStatus = status)
            } catch (e: NativeProverException) {
                return NativeIssuanceOutcome(
                    status = e.code,
                    diagnostic = e.message,
                    closeCode = notary.closeCode,
                    closeReason = notary.closeReason,
                    targetError = target?.targetError,
                )
            } finally {
                if (material != 0L) NativeProverBridge.materialFree(material)
                notaryStream.close()
                targetStream?.close()
                notaryStream.awaitStopped(STREAM_STOP_MS)
                targetStream?.awaitStopped(STREAM_STOP_MS)
            }
        }
    }

    private companion object {
        const val ABI_VERSION = 1
        const val CONNECT_TIMEOUT_MS = 30_000L
        const val STREAM_STOP_MS = 5_000L
    }
}
