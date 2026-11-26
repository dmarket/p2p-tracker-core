package com.dmarket.p2p.headless.proxy

import com.dmarket.p2p.headless.STEAM_ID
import com.dmarket.p2p.headless.TestClock
import com.dmarket.p2p.headless.distributedDir
import com.dmarket.p2p.headless.node.LoopbackProxy
import com.dmarket.p2p.headless.node.LoopbackServer
import com.dmarket.p2p.headless.node.join
import com.dmarket.p2p.headless.node.nodeFs
import com.dmarket.p2p.headless.node.nodeNet
import com.dmarket.p2p.headless.node.tempDir
import com.dmarket.p2p.headless.prover.ChildResult
import com.dmarket.p2p.headless.prover.NO_TUNNEL_HANDOFF
import com.dmarket.p2p.headless.prover.NodeWasmProver
import com.dmarket.p2p.headless.prover.ProverSessionRecord
import com.dmarket.p2p.headless.prover.ProverSettings
import com.dmarket.p2p.headless.prover.ToChild
import com.dmarket.p2p.headless.prover.proxiedTcpConnector
import com.dmarket.p2p.headless.prover.recordedTargetChannel
import com.dmarket.p2p.headless.steamToken
import com.dmarket.p2p.tracker.client.SteamProxyUnreachableException
import com.dmarket.p2p.tracker.client.createSteamHttpClient
import com.dmarket.p2p.tracker.net.STEAM_PROXY_MISSING_DETAIL
import com.dmarket.p2p.tracker.net.SteamProxy
import com.dmarket.p2p.tracker.notary.NotaryProofRequest
import com.dmarket.p2p.tracker.notary.NotaryUpgradeHeaders
import com.dmarket.p2p.tracker.notary.ProofFailure
import com.dmarket.p2p.tracker.notary.ProofFailures
import com.dmarket.p2p.tracker.runtime.SteamRoute
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A route that requires the Steam proxy (the host's `requireSteamProxy`, or a proxy held once), at its two Steam legs:
 * the HTTP engine and the proof's target leg. Node's global `fetch` (the engine's direct path) and the transport's
 * direct connector are both spied on, and neither may be reached; nor may Steam, around the proxy. A prover package
 * that cannot take the tunnel is refused before it dials.
 */
class StickySteamProxyTest {
    private val proxies = mutableListOf<LoopbackProxy>()
    private val servers = mutableListOf<LoopbackServer>()
    private val sockets = mutableListOf<dynamic>()
    private val clients = mutableListOf<HttpClient>()

    /** Every URL that reached Node's global `fetch`: the engine's direct path. */
    private val directFetches = mutableListOf<String>()
    private var realFetch: dynamic = null

    @BeforeTest
    fun spyOnGlobalFetch() {
        val global: dynamic = js("globalThis")
        realFetch = global.fetch
        val real = realFetch
        global.fetch = { input: dynamic, init: dynamic ->
            directFetches += input.toString()
            real(input, init)
        }
    }

    @AfterTest
    fun closeAll() {
        js("globalThis").fetch = realFetch
        clients.forEach { it.close() }
        proxies.forEach { it.close() }
        servers.forEach { it.close() }
        sockets.forEach { if (jsTypeOf(it.destroy) == "function") it.destroy() else it.close() }
        val global: dynamic = js("globalThis")
        global.__directDials = undefined
        global.__genericDials = undefined
        global.__channels = undefined
    }

    private fun client(route: SteamRoute) = createSteamHttpClient(steamHttpEngine(route), describeTransportCauses = true).also {
        clients += it
    }

    private suspend fun steamServer() = LoopbackServer.start { 200 to """{"response":{}}""" }.also { servers += it }

    // ---- the HTTP engine ------------------------------------------------------------------------------

    @Test
    fun with_no_proxy_held_a_steam_call_is_not_sent_at_all_and_never_reaches_the_global_fetch() = runTest {
        withContext(Dispatchers.Default) {
            val steam = steamServer()
            val route = SteamRoute(TestClock(), required = { true }) { null }

            val failure = assertFailsWith<SteamProxyUnreachableException> { client(route).get("${steam.base}/tradeoffer/new/send") }

            assertEquals(STEAM_PROXY_MISSING_DETAIL, failure.detail, "coded as the proxy's, so a write is deferred")
            assertEquals(emptyList(), directFetches, "the global fetch was never called")
            assertEquals(0, steam.seen.size, "nothing reached Steam")
            assertTrue(route.isProxyMissing)
            assertFalse(route.isUnreachable, "no proxy was asked, so none is unreachable")
        }
    }

    @Test
    fun the_spy_sees_the_direct_path_where_no_proxy_is_required() = runTest {
        withContext(Dispatchers.Default) {
            val steam = steamServer()
            client(SteamRoute(TestClock()) { null }).get("${steam.base}/x").bodyAsText()

            assertEquals(listOf("${steam.base}/x"), directFetches)
            assertEquals(1, steam.seen.size)
        }
    }

    @Test
    fun once_a_proxy_is_held_the_same_route_goes_through_it_and_still_never_direct() = runTest {
        withContext(Dispatchers.Default) {
            val proxy = LoopbackProxy.start("trader", "s3cret-pass").also { proxies += it }
            val steam = steamServer()
            var held: SteamProxy? = null
            val route = SteamRoute(TestClock(), required = { true }) { held }
            val client = client(route)
            assertFailsWith<SteamProxyUnreachableException> { client.get("${steam.base}/1") }

            held = SteamProxy.parse(proxy.url)
            client.get("${steam.base}/2").bodyAsText()

            assertEquals(listOf(steam.base.removePrefix("http://")), proxy.connects.map { it.first })
            assertEquals(emptyList(), directFetches)
            assertFalse(route.isProxyMissing)
        }
    }

    @Test
    fun a_direct_request_in_flight_when_the_first_proxy_is_pushed_is_aborted() = runTest {
        withContext(Dispatchers.Default) {
            // A Steam that takes the request and never answers: the call is in flight until something ends it.
            val held: dynamic = nodeNet.createServer { socket: dynamic ->
                sockets.add(socket)
                socket.on("error") { _: dynamic -> }
            }
            sockets.add(held)
            Promise<Unit> { resolve, _ -> held.listen(0, "127.0.0.1") { resolve(Unit) } }.await()
            val steam = "http://127.0.0.1:${(held.address().port as Number).toInt()}"
            val proxy = LoopbackProxy.start("trader", "s3cret-pass").also { proxies += it }
            var current: SteamProxy? = null
            val route = SteamRoute(TestClock()) { current }
            val client = client(route)

            val call = async { runCatching { client.get("$steam/tradeoffer/new/send").bodyAsText() } }
            withTimeout(5_000) { while (directFetches.isEmpty()) delay(10) }
            current = SteamProxy.parse(proxy.url)
            route.proxyMoved()

            val outcome = withTimeout(5_000) { call.await() }
            assertTrue(outcome.isFailure, "the direct call did not go on past the first proxy")
            assertFalse(outcome.exceptionOrNull() is SteamProxyUnreachableException, "it may have reached Steam: not coded as never sent")
        }
    }

    // ---- the proof's target leg -------------------------------------------------------------------------

    /**
     * A prover package whose transport has no `wrapTcpSocket`: `dist/node.js` with only the direct `connectTcp`, and
     * `dist/index.js` with [generic] as the generic one. Each counts its calls on `globalThis`, so a dial through either
     * shows.
     */
    private suspend fun noHandOffDir(generic: String = GENERIC_CONNECT_ONLY): String {
        val root = tempDir()
        val dist = join(join(root, "transport"), "dist")
        nodeFs.promises.mkdir(dist, js("({recursive: true})")).unsafeCast<Promise<Any?>>().await()
        nodeFs.promises.writeFile(join(root, "package.json"), """{"type":"module"}""").unsafeCast<Promise<Any?>>().await()
        nodeFs.promises.writeFile(join(dist, "node.js"), DIRECT_CONNECT_TCP).unsafeCast<Promise<Any?>>().await()
        nodeFs.promises.writeFile(join(dist, "index.js"), generic).unsafeCast<Promise<Any?>>().await()
        return root
    }

    private fun dials(counter: String): Int = (js("globalThis")[counter] as? Number)?.toInt() ?: 0

    private suspend fun echoPort(accepted: MutableList<Unit>): Int {
        val server: dynamic = nodeNet.createServer { socket: dynamic ->
            accepted += Unit
            socket.on("error") { _: dynamic -> }
            socket.pipe(socket)
        }
        sockets.add(server)
        Promise<Unit> { resolve, _ -> server.listen(0, "127.0.0.1") { resolve(Unit) } }.await()
        return (server.address().port as Number).toInt()
    }

    private suspend fun echoed(channel: dynamic): List<Int> {
        channel.write(js("new Uint8Array([22, 3, 1])")).unsafeCast<Promise<Any?>>().await()
        val echoed: dynamic = channel.read().unsafeCast<Promise<dynamic>>().await()
        channel.close().unsafeCast<Promise<Any?>>().await()
        return js("Array.from")(echoed).unsafeCast<Array<Int>>().toList()
    }

    @Test
    fun a_proof_with_no_proxy_is_not_started_and_the_direct_connector_is_never_loaded() = runTest {
        withContext(Dispatchers.Default) {
            val settings = ProverSettings(
                notaryUrl = "wss://notary.test/provenance/v1/",
                threadCount = 1,
                assetsDir = noHandOffDir(),
                requireSteamProxy = true,
            )
            val request = NotaryProofRequest(dealId = "deal-1", subjectSteamId = STEAM_ID, steamOfferId = "555", kind = "TRADE_OFFER")
            var signed = false

            val result = NodeWasmProver(TestClock()).prove(ToChild.Prove(1, request, steamToken(), "sid", settings)) {
                signed = true
                NotaryUpgradeHeaders(emptyMap())
            }

            assertEquals(ProofFailure.NotStarted(STEAM_PROXY_MISSING_DETAIL), assertIs<ChildResult.Failed>(result).failure)
            assertFalse(signed, "the notary was never asked for")
            assertEquals(0, dials("__directDials"), "the transport's direct connector was never called")
        }
    }

    @Test
    fun a_generic_entry_exporting_its_channel_gets_it_built_on_the_tunnel_and_never_dials() = runTest {
        withContext(Dispatchers.Default) {
            val proxy = LoopbackProxy.start("trader", "s3cret-pass").also { proxies += it }
            val accepted = mutableListOf<Unit>()
            val target = echoPort(accepted)
            val errors = mutableListOf<String>()
            val connect = proxiedTcpConnector(noHandOffDir(GENERIC_WITH_CHANNEL), SteamProxy.parse(proxy.url)) { errors += it }

            val channel: dynamic = connect("127.0.0.1", target).await()!!

            assertEquals(listOf(22, 3, 1), echoed(channel))
            assertEquals(1, dials("__channels"), "the transport's own channel, built here")
            assertEquals(0, dials("__genericDials"), "its connectTcp was never trusted with the dial")
            assertEquals(0, dials("__directDials"))
            assertEquals(listOf("127.0.0.1:$target"), proxy.connects.map { it.first })
            assertEquals(emptyList(), errors)
        }
    }

    @Test
    fun a_package_with_neither_wrap_tcp_socket_nor_tcp_io_channel_is_refused_before_anything_is_dialled() = runTest {
        withContext(Dispatchers.Default) {
            val proxy = LoopbackProxy.start("trader", "s3cret-pass").also { proxies += it }
            val record = ProverSessionRecord()
            val connect = proxiedTcpConnector(noHandOffDir(), SteamProxy.parse(proxy.url)) {
                record.proxyError = record.proxyError ?: it
            }

            val failed = runCatching { recordedTargetChannel("steamcommunity.com:443", record, connect = connect).await() }.isFailure

            assertTrue(failed)
            assertEquals(0, dials("__genericDials"), "its connectTcp, which would dial on its own, was never called")
            assertEquals(0, dials("__directDials"))
            assertTrue(proxy.connects.isEmpty(), "no tunnel was opened either")
            assertEquals(null, record.proxyError, "the proxy is not blamed")
            assertEquals(NO_TUNNEL_HANDOFF, record.targetError)
            val failure = ProofFailures.classify(targetError = record.targetError, proxyError = record.proxyError, message = "io error")
            assertEquals(ProofFailure.SteamFailed(detail = NO_TUNNEL_HANDOFF), failure)
        }
    }

    @Test
    fun the_shipped_transports_wrap_tcp_socket_carries_the_tunnel() = runTest {
        withContext(Dispatchers.Default) {
            val proxy = LoopbackProxy.start("trader", "s3cret-pass").also { proxies += it }
            val accepted = mutableListOf<Unit>()
            val target = echoPort(accepted)
            val errors = mutableListOf<String>()
            val connect = proxiedTcpConnector(distributedDir(), SteamProxy.parse(proxy.url)) { errors += it }

            val channel: dynamic = connect("127.0.0.1", target).await()!!

            assertEquals(listOf(22, 3, 1), echoed(channel))
            assertEquals(listOf("127.0.0.1:$target"), proxy.connects.map { it.first })
            assertEquals(emptyList(), errors)
        }
    }

    private companion object {
        const val DIRECT_CONNECT_TCP = """
            export async function connectTcp(host, port) {
              globalThis.__directDials = (globalThis.__directDials || 0) + 1;
              throw new Error('dialled ' + host + ':' + port + ' directly');
            }
        """

        /** A generic entry with only `connectTcp`, no `TcpIoChannel`: it must never be called. */
        const val GENERIC_CONNECT_ONLY = """
            export async function connectTcp(host, port, opts) {
              globalThis.__genericDials = (globalThis.__genericDials || 0) + 1;
              const socket = await opts.connect(host, port);
              const reader = socket.readable.getReader();
              const writer = socket.writable.getWriter();
              return {
                async read() { const { value, done } = await reader.read(); return done ? null : value; },
                async write(data) { await writer.write(new Uint8Array(data)); },
                async close() { reader.releaseLock(); writer.releaseLock(); await socket.close(); },
              };
            }
        """

        /** A generic entry that exports its channel, as the shipped one does; its `connectTcp` must never be called. */
        const val GENERIC_WITH_CHANNEL = """
            export class TcpIoChannel {
              #reader;
              #writer;
              #socket;
              constructor(socket) {
                globalThis.__channels = (globalThis.__channels || 0) + 1;
                this.#socket = socket;
                this.#reader = socket.readable.getReader();
                this.#writer = socket.writable.getWriter();
              }
              async read() { const { value, done } = await this.#reader.read(); return done ? null : value; }
              async write(data) { await this.#writer.write(new Uint8Array(data)); }
              async close() { this.#reader.releaseLock(); this.#writer.releaseLock(); await this.#socket.close(); }
            }
            export async function connectTcp(host, port, opts) {
              globalThis.__genericDials = (globalThis.__genericDials || 0) + 1;
              throw new Error('the generic connectTcp was called for ' + host + ':' + port);
            }
        """
    }
}
