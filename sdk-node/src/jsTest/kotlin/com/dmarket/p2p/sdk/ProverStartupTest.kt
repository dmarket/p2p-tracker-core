package com.dmarket.p2p.sdk

import com.dmarket.p2p.sdk.prover.ProverConfig
import com.dmarket.p2p.tracker.notary.IssuanceMode
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProverStartupTest {
    @Test
    fun startup_disables_unsupported_assets_and_accepts_capable_assets_without_initializing() {
        val fs = js("require('node:fs')")
        val root = fs.mkdtempSync(js("require('node:path').join(require('node:os').tmpdir(), 'sdk-prover-startup-')")) as String
        val savedScript = js("process.argv[1]")
        js("process.argv")[1] = "$root/sdk.js"
        val clock = TestClock()
        val lines = mutableListOf<String>()
        val log = SdkLog(clock) { lines += it }
        val config = SdkConfig("s".repeat(32), root, prover = ProverConfig(assetsDir = root, mode = IssuanceMode.PROXY))
        try {
            fs.mkdirSync("$root/pkg")
            fs.mkdirSync("$root/transport/dist", js("({recursive: true})"))
            fs.writeFileSync("$root/package.json", """{"type":"module"}""")
            fs.writeFileSync("$root/pkg/client_wasm.js", "export function prove() {}")
            fs.writeFileSync("$root/transport/dist/index.js", "export function createProver() {}")
            assertNull(proofHostFactory(config, log, clock))
            assertTrue(lines.single().contains("proxy-capable"))

            fs.writeFileSync("$root/pkg/client_wasm.js", "export function proveProxy() { throw new Error('must not prove'); }")
            fs.writeFileSync(
                "$root/transport/dist/index.js",
                "export function createProxyProver() { throw new Error('must not initialize'); }",
            )
            lines.clear()
            assertNotNull(proofHostFactory(config, log, clock))
            assertTrue(lines.isEmpty())
            assertNull(proofHostFactory(config.copy(prover = config.prover.copy(transport = "egress")), log, clock))
            assertTrue(lines.single().contains("egress-capable"))

            fs.writeFileSync("$root/pkg/client_wasm.js", "invalid javascript")
            lines.clear()
            assertNull(proofHostFactory(config, log, clock))
            assertTrue(lines.single().contains("could not be inspected"))
            lines.clear()
            assertNull(proofHostFactory(config.copy(prover = config.prover.copy(enabled = false)), log, clock))
            assertTrue(lines.isEmpty())
        } finally {
            js("process.argv")[1] = savedScript
            fs.rmSync(root, js("({recursive: true, force: true})"))
        }
    }
}
