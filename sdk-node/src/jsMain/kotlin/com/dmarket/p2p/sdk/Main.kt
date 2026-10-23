package com.dmarket.p2p.sdk

import com.dmarket.p2p.headless.node.AccountDirectories
import com.dmarket.p2p.sdk.api.ManagementApi
import com.dmarket.p2p.sdk.node.NodeManagementServer
import com.dmarket.p2p.sdk.node.NodeModules
import com.dmarket.p2p.sdk.prover.ForkedProofHost
import com.dmarket.p2p.sdk.prover.NodeWasmProver
import com.dmarket.p2p.sdk.prover.PROVER_CHILD_ARG
import com.dmarket.p2p.sdk.prover.ProverLimits
import com.dmarket.p2p.sdk.prover.ProverMemory
import com.dmarket.p2p.sdk.prover.ProverSettings
import com.dmarket.p2p.sdk.prover.SdkProofHostFactory
import com.dmarket.p2p.sdk.prover.cgroupMemoryLimit
import com.dmarket.p2p.sdk.prover.proverAssetsDir
import com.dmarket.p2p.sdk.prover.residentBytes
import com.dmarket.p2p.sdk.prover.runProverChild
import com.dmarket.p2p.tracker.adapter.host.SystemClock
import com.dmarket.p2p.tracker.port.host.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.await
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.js.Promise

/**
 * The process entry point: `node sdk-node.js <config.json>` (or the path in `DMARKET_SDK_CONFIG`).
 *
 * Reads the config, opens the data directory, starts the management API and then the loop, which waits for the
 * bot's pushes before it heartbeats. Exits `2` on a config it cannot use, and `0` on SIGTERM / SIGINT.
 *
 * The same script, started with [PROVER_CHILD_ARG], is a prover process: the SDK forks one per proof.
 */
fun main() {
    val clock = SystemClock()
    if (NodeModules.process.argv[2] == PROVER_CHILD_ARG) {
        runProverChild(NodeWasmProver(clock))
        return
    }
    val log = SdkLog(clock) { line -> NodeModules.process.stdout.write(line + "\n") }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val configPath = (NodeModules.process.argv[2] as? String) ?: (NodeModules.process.env.DMARKET_SDK_CONFIG as? String)
    if (configPath == null) {
        log.error("no config: pass its path as the first argument or in DMARKET_SDK_CONFIG")
        NodeModules.process.exit(2)
        return
    }
    scope.launch {
        val config = readConfig(configPath, log) ?: run {
            NodeModules.process.exit(2)
            return@launch
        }
        val accounts = AccountDirectories(config.dataDir)
        val runtime = SdkRuntime(
            config = config,
            platform = SdkPlatform({ steamId ->
                accounts.storeFor(steamId.value)
            }, clock, log, proofHost = proofHostFactory(config, log, clock)),
            scope = scope,
        )
        if (config.prover.enabled) checkProverMemory(runtime, config)
        val server = NodeManagementServer(ManagementApi(config.managementSecret, runtime), scope, log)
        server.listen(config.listenHost, config.listenPort)
        runtime.start()
        log.info("sdk started", "version" to SdkRuntime.VERSION, "listen" to "${config.listenHost}:${config.listenPort}")

        val shutdown: () -> Unit = {
            log.info("sdk stopping")
            server.close()
            runtime.stop()
            scope.cancel()
            NodeModules.process.exit(0)
        }
        NodeModules.process.once("SIGTERM", shutdown)
        NodeModules.process.once("SIGINT", shutdown)
    }
}

/**
 * The host that forks this same script as a prover process per proof, or `null` (and a logged reason) when the
 * prover's files cannot be found.
 */
private fun proofHostFactory(config: SdkConfig, log: SdkLog, clock: Clock): SdkProofHostFactory? {
    val assets = proverAssetsDir(config.prover.assetsDir) ?: run {
        log.error("prover files not found: no proofs will be made", "assetsDir" to config.prover.assetsDir)
        return null
    }
    val settings = ProverSettings(notaryUrl = config.prover.notaryUrl, threadCount = config.prover.threadCount, assetsDir = assets)
    val script = NodeModules.process.argv[1] as String
    val limits = ProverLimits(proofsPerChild = config.prover.proofsPerChild)
    return SdkProofHostFactory { onOutcome ->
        ForkedProofHost(script, listOf(PROVER_CHILD_ARG), settings, log, clock, limits, onOutcome = onOutcome)
    }
}

/** Reports `PROVER_MEMORY_LOW` when this container's memory limit leaves less than one proof's peak. */
private suspend fun checkProverMemory(runtime: SdkRuntime, config: SdkConfig) {
    val limit = cgroupMemoryLimit() ?: return
    val used = residentBytes()
    if (ProverMemory.isLow(limit, used, config.prover.peakMemoryBytes)) runtime.proverMemoryLow(limit, used)
}

/** The parsed config, or `null` after logging why not. A malformed file's own error is not printed: it can quote the secret. */
private suspend fun readConfig(path: String, log: SdkLog): SdkConfig? {
    val text = try {
        NodeModules.fs.readFile(path, "utf8").unsafeCast<Promise<String>>().await()
    } catch (_: Throwable) {
        log.error("config file cannot be read", "path" to path)
        return null
    }
    return try {
        SdkConfig.parse(text)
    } catch (e: IllegalArgumentException) {
        // `require` failures name the field and never its value.
        log.error("config is invalid", "path" to path, "reason" to e.message.takeIf { e !is kotlinx.serialization.SerializationException })
        null
    } catch (_: Throwable) {
        log.error("config is invalid", "path" to path)
        null
    }
}
