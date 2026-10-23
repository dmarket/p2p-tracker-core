package com.dmarket.p2p.headless.prover

import com.dmarket.p2p.headless.node.nodeFs
import com.dmarket.p2p.headless.node.nodeModule
import com.dmarket.p2p.headless.node.nodePath
import com.dmarket.p2p.headless.node.nodeProcess

/**
 * Where the prover's `pkg/` and `transport/` are: [configured] when set; else next to the running script, where a
 * release puts them; else the installed `@dmarket/steam-provenance` package. `null` when none has `pkg/`.
 *
 * Public only for the Node SDK in this repository, which picks the directory for its prover processes; not a
 * supported entry point of the library.
 */
fun proverAssetsDir(configured: String?): String? {
    fun hasPkg(dir: String) = nodeFs.existsSync(nodePath.join(dir, "pkg", "client_wasm.js")) as Boolean
    if (configured != null) return configured.takeIf(::hasPkg)
    val script = nodeProcess.argv[1] as? String
    val scriptDir = script?.let { nodePath.dirname(it) as String }
    if (scriptDir != null && hasPkg(scriptDir)) return scriptDir
    val installed = resolveInstalled("@dmarket/steam-provenance/package.json", script) ?: return null
    return (nodePath.dirname(installed) as String).takeIf(::hasPkg)
}

/**
 * Where Node would load [specifier] from: by the bundle's own `require` in a CommonJS build, as before; an ES module
 * has none, so there from [script] (the process's entry), the way a CommonJS script there would. `null` when it is
 * not installed.
 */
internal fun resolveInstalled(specifier: String, script: String?): String? = runCatching {
    val resolve: dynamic = if (js("typeof require") == "function") {
        js("require").resolve
    } else {
        nodeModule.createRequire(script ?: nodePath.join(nodeProcess.cwd(), "index.js")).resolve
    }
    resolve(specifier) as String
}.getOrNull()
