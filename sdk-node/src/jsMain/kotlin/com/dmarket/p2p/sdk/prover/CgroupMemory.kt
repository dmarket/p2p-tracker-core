package com.dmarket.p2p.sdk.prover

import com.dmarket.p2p.sdk.node.NodeModules
import kotlinx.coroutines.await
import kotlin.js.Promise

/**
 * Reads this process's cgroup memory limit: v2's `memory.max`, else v1's `memory.limit_in_bytes`, under [root]
 * (`/sys/fs/cgroup` on Linux). `null` with no limit, or where there is no cgroup filesystem (macOS, Windows).
 */
internal suspend fun cgroupMemoryLimit(root: String = "/sys/fs/cgroup"): Long? {
    val v2 = readText(NodeModules.path.join(root, "memory.max") as String)
    val v1 = if (v2 == null) readText(NodeModules.path.join(root, "memory", "memory.limit_in_bytes") as String) else null
    return ProverMemory.limitBytes(v2, v1)
}

/** This process's resident memory, in bytes. */
internal fun residentBytes(): Long = (NodeModules.process.memoryUsage().rss as Number).toLong()

private suspend fun readText(path: String): String? = try {
    NodeModules.fs.readFile(path, "utf8").unsafeCast<Promise<String>>().await()
} catch (_: Throwable) {
    null
}

/**
 * Where the prover's `pkg/` and `transport/` are: [configured] when set; else next to the SDK's script, where a
 * release puts them; else the installed `@dmarket/steam-provenance` package. `null` when none has `pkg/`.
 */
internal fun proverAssetsDir(configured: String?): String? {
    val fs: dynamic = js("require('node:fs')")
    fun hasPkg(dir: String) = fs.existsSync(NodeModules.path.join(dir, "pkg", "client_wasm.js")) as Boolean
    if (configured != null) return configured.takeIf(::hasPkg)
    val script = NodeModules.process.argv[1] as? String
    val scriptDir = script?.let { NodeModules.path.dirname(it) as String }
    if (scriptDir != null && hasPkg(scriptDir)) return scriptDir
    val installed = runCatching { js("require").resolve("@dmarket/steam-provenance/package.json") as String }.getOrNull() ?: return null
    return (NodeModules.path.dirname(installed) as String).takeIf(::hasPkg)
}
