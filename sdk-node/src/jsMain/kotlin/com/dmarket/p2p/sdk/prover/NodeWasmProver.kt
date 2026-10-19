package com.dmarket.p2p.sdk.prover

import com.dmarket.p2p.sdk.node.NodeModules
import com.dmarket.p2p.tracker.config.NotaryConfig
import com.dmarket.p2p.tracker.config.TrackerConfig
import com.dmarket.p2p.tracker.notary.HostedProofRequest
import com.dmarket.p2p.tracker.notary.NotaryUpgradeHeaders
import com.dmarket.p2p.tracker.notary.ProofFailure
import com.dmarket.p2p.tracker.notary.ProofFailures
import com.dmarket.p2p.tracker.notary.WasmProverHost
import com.dmarket.p2p.tracker.notary.runHostedProof
import com.dmarket.p2p.tracker.port.host.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.await
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlin.js.Promise

/**
 * The extension's wasm prover in a Node prover process: [runHostedProof] over Node's four seams.
 *
 *  - assets: `pkg/` and `transport/` under [ProverSettings.assetsDir], as `file:` URLs;
 *  - the module: `client_wasm_bg.wasm` compiled from disk, handed to the init as `module_or_path`;
 *  - the notary: a `ws` socket with the upgrade headers the SDK signs when asked, and no `bearer.` entry;
 *  - the target: a TCP socket to the target's port 443 ([ProverSettings.targetAddress] on a test stand).
 *
 * The prover's threads run as worker threads ([installBrowserWorker]). Its trace goes to stdout, which the SDK
 * logs redacted. A failure is classified from what the sockets saw and the prover's words ([ProofFailures]).
 */
class NodeWasmProver(private val clock: Clock) : ChildProver {
    override suspend fun prove(proof: ToChild.Prove, headers: suspend () -> NotaryUpgradeHeaders): ChildResult {
        installBrowserWorker()
        val settings = proof.settings
        val record = ProverSessionRecord()
        val host = WasmProverHost(
            assetUrl = { path -> fileUrl(NodeModules.path.join(settings.assetsDir, path) as String) },
            wasmModule = { compileWasm(NodeModules.path.join(settings.assetsDir, "pkg", "client_wasm_bg.wasm") as String) },
            notaryWebSocket = {
                record.notaryAttempted = true
                val signed = try {
                    headers()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    record.signingError = e.message ?: "the upgrade could not be signed"
                    throw e
                }
                headerWebSocket(signed.values, record)
            },
            connectTarget = { serverName, port -> tcpChannel(settings.targetAddress ?: "$serverName:$port", record) },
            onTargetResponse = { status ->
                record.targetStatus = status
                check(status in SUCCESS) { "Steam answered HTTP $status" }
            },
        )
        val config = TrackerConfig(
            notary = NotaryConfig(notaryUrl = settings.notaryUrl, threadCount = settings.threadCount, rootStorePem = settings.rootStorePem),
        )
        val started = NodeModules.process.cpuUsage()
        // The wasm does not return when the notary ends the session on purpose (a close 1013 after the 101 left it
        // waiting until the deadline): the close itself is the answer.
        val refused = CompletableDeferred<ProofFailure>()
        record.onDeliberateClose = { code, reason -> refused.complete(ProofFailure.SessionClosed(code, reason)) }
        return try {
            coroutineScope {
                val proving = async {
                    runHostedProof(
                        HostedProofRequest(proof.request, proof.steamAccessToken, proof.steamSessionId),
                        config,
                        host,
                        clock.now(),
                        onProgress = { line -> println("prover: $line") },
                    )
                }
                select {
                    proving.onAwait { presentation -> ChildResult.Proved(presentation) }
                    refused.onAwait { failure ->
                        proving.cancel()
                        ChildResult.Failed(failure, exitAfter = true)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ChildResult.Failed(classify(e, record))
        } finally {
            printStats(started)
        }
    }

    /** What the proof cost this process: its peak resident memory and the CPU its threads spent. */
    private fun printStats(started: dynamic) {
        val cpu: dynamic = NodeModules.process.cpuUsage(started)
        val peakKb = (NodeModules.process.resourceUsage().maxRSS as Number).toLong()
        val cpuMs = ((cpu.user as Number).toLong() + (cpu.system as Number).toLong()) / MICROS_PER_MILLI
        println("prover: stats peakRssMb=${peakKb / KB_PER_MB} cpuMs=$cpuMs")
    }

    private fun classify(error: Throwable, record: ProverSessionRecord): ProofFailure {
        val message = generateSequence(error) { it.cause }.mapNotNull { it.message }.distinct().joinToString(": ")
        record.signingError?.let { return ProofFailure.Other("the upgrade could not be signed: $it") }
        return ProofFailures.classify(
            upgradeStatus = record.upgradeStatus,
            closeCode = record.closeCode,
            closeReason = record.closeReason,
            targetStatus = record.targetStatus,
            targetError = record.targetError,
            // A failure before any connect is not the notary's.
            notaryConnected = !record.notaryAttempted || record.notaryOpened || record.upgradeStatus != null,
            message = message,
        ) ?: ProofFailure.Other(message)
    }

    private companion object {
        val SUCCESS = 200..299
        const val MICROS_PER_MILLI = 1_000L
        const val KB_PER_MB = 1_024L
    }
}

private fun fileUrl(path: String): String = NodeModules.url.pathToFileURL(path).href as String

private suspend fun compileWasm(path: String): Any {
    val bytes: dynamic = NodeModules.fs.readFile(path).unsafeCast<Promise<dynamic>>().await()
    val webAssembly: dynamic = js("WebAssembly")
    return webAssembly.compile(bytes).unsafeCast<Promise<Any>>().await()
}
