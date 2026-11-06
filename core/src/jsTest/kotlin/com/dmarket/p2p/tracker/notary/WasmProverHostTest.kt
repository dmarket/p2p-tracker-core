package com.dmarket.p2p.tracker.notary

import com.dmarket.p2p.tracker.config.NotaryConfig
import com.dmarket.p2p.tracker.game.Cs2GameAdapter
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusSource
import kotlinx.coroutines.test.runTest
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

private external fun encodeURIComponent(value: String): String

/**
 * The four seams of [BundledWasmProverModule]: with no host they must make exactly the calls the extension has
 * always made, and an injected host must replace each one without any of the defaults running.
 *
 * Both modules are fakes loaded by the module's real `import()`, from `data:` URLs, so the default asset path
 * goes through `runtime.getURL` exactly as in the extension. The fakes write every call they receive into
 * `globalThis.__proverSeams`.
 */
class WasmProverHostTest {
    private var savedChrome: dynamic = null
    private val extensionAssets = mutableListOf<String>()

    @BeforeTest
    fun stubExtensionRoot() {
        js("globalThis.__proverSeams = { calls: [] }")
        savedChrome = js("globalThis.chrome")
        val runtime: dynamic = js("({})")
        runtime.getURL = { path: String ->
            extensionAssets += path
            assetUrls[path] ?: error("unexpected prover asset $path")
        }
        val chrome: dynamic = js("({})")
        chrome.runtime = runtime
        js("globalThis").chrome = chrome
    }

    @AfterTest
    fun restoreExtensionRoot() {
        js("globalThis").chrome = savedChrome
        js("delete globalThis.__proverSeams")
    }

    @Test
    fun no_host_makes_the_extension_calls() = runTest {
        val module = BundledWasmProverModule()

        module.initialize(threadCount = 2)
        val presentation = module.prove(request())

        assertEquals(listOf("pkg/client_wasm.js", "transport/dist/index.js"), extensionAssets)
        assertEquals(
            listOf(
                // The façade's no-argument init: the wasm-pack glue fetches the `.wasm` itself.
                "init()",
                "initialize(2)",
                // Two arguments, no options: the transport's own default socket and the bearer subprotocol.
                "connectNotary(wss://notary.test/provenance/v1/, notary-token, args=2)",
                "connectProxy(wss://proxy.test, $serverName, 443)",
                "proxy.write",
                "proxy.close",
                "notary.close",
            ),
            calls(),
        )
        assertContentEquals(byteArrayOf(9, 9), presentation)
    }

    @Test
    fun a_host_replaces_all_four_seams() = runTest {
        val compiled = js("({ compiledWasm: true })")
        val webSocket = js("(function HostWebSocket() {})")
        val hostAssets = mutableListOf<String>()
        var compiles = 0
        var sockets = 0
        val targets = mutableListOf<String>()
        val traces = mutableListOf<String>()
        val module = BundledWasmProverModule(
            onProgress = { traces += it },
            host = WasmProverHost(
                assetUrl = { path ->
                    hostAssets += path
                    assetUrls.getValue(path)
                },
                wasmModule = {
                    compiles++
                    compiled
                },
                notaryWebSocket = {
                    sockets++
                    webSocket
                },
                connectTarget = { name, port ->
                    targets += "$name:$port"
                    Promise.resolve(hostChannel())
                },
            ),
        )

        module.initialize(threadCount = 2)
        module.prove(request())
        module.prove(request())

        assertEquals(emptyList(), extensionAssets, "runtime.getURL must not run under a host asset resolver")
        assertEquals(listOf("pkg/client_wasm.js", "transport/dist/index.js"), hostAssets)
        assertEquals(1, compiles)
        assertSame(compiled, seams().initInput.module_or_path)
        // Asked once per connect, so headers signed for one upgrade are never reused for the next.
        assertEquals(2, sockets)
        assertSame(webSocket, seams().webSocketImpl)
        assertEquals(listOf("$serverName:443", "$serverName:443"), targets)
        val calls = calls()
        assertEquals("init(module_or_path)", calls.first())
        assertEquals(2, calls.count { it == "connectNotary(wss://notary.test/provenance/v1/, notary-token, args=3)" })
        assertFalse(calls.any { it.startsWith("connectProxy") }, "$calls")
        assertEquals(2, calls.count { it == "host.write" })
        assertEquals(2, calls.count { it == "host.close" })
        assertTrue(traces.any { "target=host" in it }, "$traces")
    }

    @Test
    fun a_host_socket_that_fails_fails_the_handshake_before_any_connect() = runTest {
        val module = BundledWasmProverModule(
            host = WasmProverHost(
                assetUrl = { assetUrls.getValue(it) },
                notaryWebSocket = { error("upgrade headers unavailable") },
            ),
        )
        module.initialize(threadCount = 2)

        val failure = assertFailsWith<IllegalStateException> { module.prove(request()) }

        assertContains(failure.message.orEmpty(), "notary handshake failed")
        assertFalse("notary-token" in failure.message.orEmpty() || "steam-jwt" in failure.message.orEmpty())
        assertFalse(calls().any { it.startsWith("connectNotary") || it.startsWith("connectProxy") }, "${calls()}")
    }

    @Test
    fun the_issuance_line_names_the_host_target_instead_of_an_unused_proxy() {
        val hosted = issuanceLine(request(), hostTarget = true)

        assertContains(hosted, "target=host")
        assertFalse("proxy" in hosted, hosted)
        assertFalse("notary-token" in hosted || "steam-jwt" in hosted, hosted)
        assertContains(issuanceLine(request()), "proxy=wss://proxy.test")
    }

    @Test
    fun proxy_uses_one_channel_and_the_same_presentation_and_response_hook() = runTest {
        var status: Int? = null
        val traces = mutableListOf<String>()
        val module = BundledWasmProverModule(
            onProgress = { traces += it },
            host = WasmProverHost(
                connectTarget = { _, _ -> error("proxy must never dial a client target") },
                onTargetResponse = { status = it },
            ),
        )
        module.initialize(2, "proxy")
        val proof = module.prove(request().copy(mode = "proxy", rootStorePem = "fixture roots"))
        assertContentEquals(byteArrayOf(9, 9), proof)
        assertEquals(200, status)
        assertEquals(
            listOf(
                "createProxyProver",
                "init()",
                "initialize(2)",
                "connectNotary(wss://notary.test/provenance/v1/, notary-token, args=2)",
                "proveProxy",
                "notary.close",
            ),
            calls(),
        )
        assertEquals(request().maxSentData, seams().config.limits.maxSentData as Int)
        assertEquals(request().maxRecvData, seams().config.limits.maxRecvData as Int)
        assertEquals("fixture roots", seams().config.rootStore.pem as String)
        assertEquals(serverName, seams().config.serverName as String)
        assertEquals(undefined, seams().config.maxRecvDataOnline)
        assertEquals(undefined, seams().config.limits.maxRecvRecordsOnline)
        assertEquals(undefined, seams().config.proxyBaseUrl)
        assertEquals(httpRequest(request()).uri as String, seams().request.uri as String)
        assertTrue(traces.any { "mode=proxy target=notary" in it })
        assertFalse(traces.any { "maxRecvOnline" in it || "proxy.test" in it })
        assertTrue(traces.any { "proxy_setup" in it })
    }

    @Test
    fun proxy_failure_closes_the_notary_without_target_or_fallback() = runTest {
        val module = BundledWasmProverModule()
        module.initialize(2, "proxy")
        seams().failProof = true
        val error = assertFailsWith<Throwable> { module.prove(request().copy(mode = "proxy")) }
        assertContains(error.message.orEmpty(), "proxy rejected")
        assertEquals("notary.close", calls().last())
        assertFalse(calls().any { it.startsWith("connectProxy") })
    }

    @Test
    fun proxy_status_rejection_also_closes_the_notary() = runTest {
        val module = BundledWasmProverModule(host = WasmProverHost(onTargetResponse = { error("decline response") }))
        module.initialize(2, "proxy")
        assertFailsWith<IllegalStateException> { module.prove(request().copy(mode = "proxy")) }
        assertEquals("notary.close", calls().last())
    }

    @Test
    fun old_assets_fail_proxy_before_compilation_workers_or_network() = runTest {
        for (missing in listOf("pkg/client_wasm.js", "transport/dist/index.js")) {
            val module = BundledWasmProverModule(
                host = WasmProverHost(
                    assetUrl = { path ->
                        if (path != missing) {
                            assetUrls.getValue(path)
                        } else {
                            dataUrl(
                                if (path.startsWith("pkg")) {
                                    FAKE_PKG.replace("export function proveProxy", "function oldProveProxy")
                                } else {
                                    FAKE_TRANSPORT.replace("export function createProxyProver", "function oldCreateProxyProver")
                                },
                            )
                        }
                    },
                    wasmModule = { error("must check capabilities before compiling") },
                ),
            )
            val failure = assertFailsWith<IllegalStateException> { module.initialize(2, "proxy") }
            assertContains(failure.message.orEmpty(), "proxy-capable")
            assertEquals(emptyList(), calls())
        }
    }

    @Test
    fun an_initialized_module_cannot_silently_switch_protocols() = runTest {
        val module = BundledWasmProverModule()
        module.initialize(2)
        assertFailsWith<IllegalStateException> { module.prove(request().copy(mode = "proxy")) }
        assertFailsWith<IllegalStateException> { module.initialize(2, "proxy") }
        assertFalse(calls().any { it.startsWith("connect") })
    }

    private fun seams(): dynamic = js("globalThis.__proverSeams")

    private fun calls(): List<String> = (seams().calls as Array<String>).toList()

    /** A host `IoChannel` that records into the same call log as the fake transport's channels. */
    private fun hostChannel(): dynamic {
        val channel: dynamic = js("({})")
        channel.read = { Promise.resolve(null) }
        channel.write = { _: dynamic ->
            seams().calls.push("host.write")
            Promise.resolve(Unit)
        }
        channel.close = {
            seams().calls.push("host.close")
            Promise.resolve(Unit)
        }
        return channel
    }

    private fun request() = WasmProveRequest(
        notaryUrl = "wss://notary.test/provenance/v1/",
        notaryToken = "notary-token",
        proxyBaseUrl = "wss://proxy.test",
        spec = SteamProofReadMapper(NotaryConfig()).readSpec(
            source = TradeStatusSource.OFFER,
            binding = ProvenReadBinding(dealId = DealId("d1"), steamOfferId = OfferId("off-1")),
            subjectSteamId = SteamId("76561198000000001"),
            adapter = Cs2GameAdapter(),
        ),
        steamAccessToken = "steam-jwt",
        maxSentData = 1024,
        maxRecvData = 16384,
        maxRecvDataOnline = 2048,
    )

    private val serverName get() = request().spec.serverName

    private companion object {
        /** `pkg/client_wasm.js`: only the default-export init, which records what it was handed. */
        const val FAKE_PKG = """
            export function proveProxy() {}
            export default function init(input) {
              const seams = globalThis.__proverSeams;
              seams.calls.push(input === undefined ? "init()" : "init(module_or_path)");
              seams.initInput = input;
              return Promise.resolve();
            }
        """

        /** `transport/dist/index.js`: the façade's shape, with the prover writing once to the target leg. */
        const val FAKE_TRANSPORT = """
            const seams = () => globalThis.__proverSeams;
            const channel = (name) => ({
              read: async () => null,
              write: async () => { seams().calls.push(name + ".write"); },
              close: async () => { seams().calls.push(name + ".close"); },
            });
            export function createProver(wasm) {
              return {
                async initialize(config, threads) {
                  await wasm.default?.();
                  seams().calls.push("initialize(" + threads + ")");
                },
                async prove(notaryIo, serverIo) {
                  await serverIo.write(new Uint8Array([22]));
                  return { attestation: new Uint8Array([1]), secrets: new Uint8Array([2]) };
                },
                present() { return new Uint8Array([9, 9]); },
              };
            }
            export function createProxyProver(wasm) {
              seams().calls.push("createProxyProver");
              return {
                ...createProver(wasm),
                async proveProxy(notaryIo, config, request, progress) {
                  seams().calls.push("proveProxy");
                  seams().config = config;
                  seams().request = request;
                  progress?.({step: "proxy_setup", progress: 0, message: "setup", source: "prover"});
                  if (seams().failProof) throw new Error("proxy rejected");
                  return {attestation: new Uint8Array([1]), secrets: new Uint8Array([2]), response: {status: 200}};
                },
              };
            }
            export async function connectNotary(...args) {
              seams().calls.push("connectNotary(" + args[0] + ", " + args[1] + ", args=" + args.length + ")");
              seams().webSocketImpl = args[2]?.WebSocketImpl;
              return channel("notary");
            }
            export async function connectProxy(base, host, port) {
              seams().calls.push("connectProxy(" + base + ", " + host + ", " + port + ")");
              return channel("proxy");
            }
        """

        val assetUrls = mapOf(
            "pkg/client_wasm.js" to dataUrl(FAKE_PKG),
            "transport/dist/index.js" to dataUrl(FAKE_TRANSPORT),
        )

        fun dataUrl(source: String) = "data:text/javascript," + encodeURIComponent(source)
    }
}
