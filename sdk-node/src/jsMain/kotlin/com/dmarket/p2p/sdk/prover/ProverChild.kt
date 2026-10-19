package com.dmarket.p2p.sdk.prover

import com.dmarket.p2p.sdk.node.NodeModules
import com.dmarket.p2p.tracker.notary.NotaryUpgradeHeaders
import com.dmarket.p2p.tracker.notary.ProofFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** The argument that starts the SDK's script as a prover process rather than as the SDK. */
const val PROVER_CHILD_ARG: String = "--prover-child"

/**
 * One proof in a prover process: [proof] with the Steam token beside it, and [headers], which asks the SDK for the
 * signed upgrade headers (call it right before connecting, once per connect). Answers the base64 presentation or a
 * [ProofFailure].
 */
fun interface ChildProver {
    suspend fun prove(proof: ToChild.Prove, headers: suspend () -> NotaryUpgradeHeaders): ChildResult
}

sealed interface ChildResult {
    data class Proved(val presentation: String) : ChildResult

    /** [exitAfter]: the prover is still running and cannot be stopped, so the process ends after answering. */
    data class Failed(val failure: ProofFailure, val exitAfter: Boolean = false) : ChildResult
}

/**
 * The prover process's side of [ForkedProofHost]: marks itself the first process the kernel kills for memory,
 * proves each [ToChild.Prove] the SDK sends with [prover], and exits when the SDK closes the channel.
 *
 * Nothing here logs a message it receives: the Steam token and the signed headers travel in them.
 */
fun runProverChild(prover: ChildProver) {
    markFirstToKill()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val waiting = mutableMapOf<Int, CompletableDeferred<ToChild.Headers>>()
    val process = NodeModules.process
    process.on("message") { raw: dynamic ->
        if (jsTypeOf(raw) != "string") return@on
        when (val message = runCatching { ProverIpc.toChild(raw.unsafeCast<String>()) }.getOrNull()) {
            is ToChild.Headers -> waiting.remove(message.id)?.complete(message)
            is ToChild.Prove -> scope.launch {
                val headers: suspend () -> NotaryUpgradeHeaders = {
                    val reply = CompletableDeferred<ToChild.Headers>()
                    waiting[message.id] = reply
                    send(ProverIpc.encode(FromChild.HeadersWanted(message.id)))
                    val answer = reply.await()
                    NotaryUpgradeHeaders(answer.headers ?: error(answer.error ?: "the SDK sent no upgrade headers"))
                }
                val result = try {
                    prover.prove(message, headers)
                } catch (e: Throwable) {
                    ChildResult.Failed(ProofFailure.Other(e.message ?: e::class.simpleName ?: "the prover failed"))
                }
                send(
                    ProverIpc.encode(
                        when (result) {
                            is ChildResult.Proved -> FromChild.Proved(message.id, result.presentation)
                            is ChildResult.Failed -> FromChild.Failed(message.id, result.failure)
                        },
                    ),
                )
                if (result is ChildResult.Failed && result.exitAfter) {
                    // Past the IPC flush: the answer is on its way, and a wasm that never returns must not hold the process.
                    js("setTimeout")({ process.exit(0) }, EXIT_FLUSH_MS)
                }
            }
            null -> Unit
        }
    }
    // The SDK closing the channel is the end of this process's work; the prover's worker threads would keep it alive.
    process.on("disconnect") { process.exit(0) }
}

private const val EXIT_FLUSH_MS = 200

private fun send(text: String) {
    runCatching { NodeModules.process.send(text) }
}

/**
 * `oom_score_adj = 1000`: when the container runs out of memory, the kernel takes this process before the SDK.
 * Linux only; elsewhere, or without permission, nothing happens.
 */
private fun markFirstToKill() {
    if (NodeModules.process.platform != "linux") return
    runCatching { js("require('node:fs')").writeFileSync("/proc/self/oom_score_adj", "1000") }
}
