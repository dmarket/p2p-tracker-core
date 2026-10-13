package com.dmarket.p2p.sdk

import com.dmarket.p2p.sdk.api.ManagementApi
import com.dmarket.p2p.sdk.node.FileKeyValueStore
import com.dmarket.p2p.sdk.node.NodeManagementServer
import com.dmarket.p2p.sdk.node.NodeModules
import com.dmarket.p2p.sdk.node.nodeEd25519Signer
import com.dmarket.p2p.sdk.node.randomHex
import com.dmarket.p2p.tracker.adapter.host.SystemClock
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
 */
fun main() {
    val clock = SystemClock()
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
        val storage = FileKeyValueStore(config.dataDir).also { it.init() }
        val runtime = SdkRuntime(
            config = config,
            platform = SdkPlatform(storage, ::nodeEd25519Signer, ::randomHex, clock, log),
            scope = scope,
        )
        val server = NodeManagementServer(ManagementApi(config.managementSecret, runtime), scope, log)
        server.listen(config.listenHost, config.listenPort)
        runtime.start()
        log.info("sdk started", "version" to SdkRuntime.VERSION, "listen" to "${config.listenHost}:${config.listenPort}")

        val shutdown: () -> Unit = {
            log.info("sdk stopping")
            server.close()
            scope.cancel()
            NodeModules.process.exit(0)
        }
        NodeModules.process.once("SIGTERM", shutdown)
        NodeModules.process.once("SIGINT", shutdown)
    }
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
