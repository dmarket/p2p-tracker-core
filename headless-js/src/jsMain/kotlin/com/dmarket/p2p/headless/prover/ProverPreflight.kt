package com.dmarket.p2p.headless.prover

import com.dmarket.p2p.headless.node.nodeChildProcess
import com.dmarket.p2p.headless.node.nodeProcess
import com.dmarket.p2p.tracker.notary.IssuanceMode
import com.dmarket.p2p.tracker.notary.ProverCapabilities

/**
 * Check once when a host is built, before a deal can start a prover. MPC retains its existing lazy loading.
 * The synchronous headless factory cannot await ESM imports. A bounded Node probe imports only the JS glue,
 * without compiling WASM, creating workers, or connecting. This also works on Node 22 before require(ESM).
 * Public for the SDK in this repository; not exported to the JS facade.
 */
fun checkProverAssets(assetsDir: String, mode: IssuanceMode, transport: String) {
    if (mode == IssuanceMode.MPC && transport == "direct") return
    val options: dynamic = js("({encoding: 'utf8', timeout: 10000, killSignal: 'SIGKILL', maxBuffer: 16384})")
    val env: dynamic = js("({})")
    minimalProverEnv().forEach { (key, value) -> env[key] = value }
    options.env = env
    val capabilities = runCatching {
        val result = nodeChildProcess.spawnSync(nodeProcess.execPath, arrayOf("--input-type=module", "-e", PROBE, assetsDir), options)
        check(result.status == 0)
        val exports: dynamic = js("JSON.parse")(result.stdout)
        ProverCapabilities(proxy = exports.proxy == true, egress = exports.egress == true)
    }.getOrElse { error("prover assets could not be inspected: check the installed JS modules") }
    capabilities.requireSupport(mode, transport)
}

private const val PROBE = """
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
const root = process.argv[1];
try {
    const wasm = await import(pathToFileURL(resolve(root, 'pkg/client_wasm.js')).href);
    const transport = await import(pathToFileURL(resolve(root, 'transport/dist/index.js')).href);
    process.stdout.write(JSON.stringify({
        proxy: typeof wasm.proveProxy === 'function' && typeof transport.createProxyProver === 'function',
        egress: typeof transport.connectEgressNotary === 'function',
    }));
} catch {
    process.exitCode = 1;
}
"""
