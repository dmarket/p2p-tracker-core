package com.dmarket.p2p.headless.proxy

import com.dmarket.p2p.headless.TestClock
import com.dmarket.p2p.headless.distributedDir
import com.dmarket.p2p.headless.node.LoopbackProxy
import com.dmarket.p2p.headless.node.LoopbackServer
import com.dmarket.p2p.headless.node.join
import com.dmarket.p2p.headless.node.nodeFs
import com.dmarket.p2p.headless.node.nodeNet
import com.dmarket.p2p.headless.node.tempDir
import com.dmarket.p2p.headless.prover.ProverSessionRecord
import com.dmarket.p2p.headless.prover.proxiedTcpConnector
import com.dmarket.p2p.headless.prover.recordedTargetChannel
import com.dmarket.p2p.tracker.client.SteamProxyUnreachableException
import com.dmarket.p2p.tracker.client.createSteamHttpClient
import com.dmarket.p2p.tracker.net.STEAM_PROXY_UNREACHABLE_CODE
import com.dmarket.p2p.tracker.net.SteamProxy
import com.dmarket.p2p.tracker.notary.ProofFailure
import com.dmarket.p2p.tracker.notary.ProofFailures
import com.dmarket.p2p.tracker.runtime.SteamRoute
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two Steam transports through a proxy, on loopback sockets: the tunnel itself, the Steam HTTP engine (`undici`'s
 * fetch and an `Agent` per tracker) and the prover's target leg. What is asserted is where each byte went — through
 * the proxy, or nowhere — and that no failure names the proxy.
 */
class SteamProxyTransportTest {
    private val proxies = mutableListOf<LoopbackProxy>()
    private val servers = mutableListOf<LoopbackServer>()
    private val sockets = mutableListOf<dynamic>()
    private val clients = mutableListOf<HttpClient>()

    @AfterTest
    fun closeAll() {
        clients.forEach { it.close() }
        proxies.forEach { it.close() }
        servers.forEach { it.close() }
        // Servers close; sockets are destroyed.
        sockets.forEach { if (jsTypeOf(it.destroy) == "function") it.destroy() else it.close() }
    }

    private suspend fun proxy(user: String = "trader", password: String = "s3cret-pass", refuseWith: Int? = null) =
        LoopbackProxy.start(user, password, refuseWith).also { proxies += it }

    private suspend fun steamServer() = LoopbackServer.start { 200 to """{"response":{}}""" }.also { servers += it }

    /** A TCP echo server; [accepted] counts the connections it took. */
    private class Echo(val port: Int, val accepted: () -> Int)

    private suspend fun echo(): Echo {
        var count = 0
        val server: dynamic = nodeNet.createServer { socket: dynamic ->
            count++
            socket.on("error") { _: dynamic -> }
            socket.pipe(socket)
        }
        sockets.add(server)
        Promise<Unit> { resolve, _ -> server.listen(0, "127.0.0.1") { resolve(Unit) } }.await()
        return Echo((server.address().port as Number).toInt()) { count }
    }

    private fun client(route: SteamRoute) = createSteamHttpClient(steamHttpEngine(route), describeTransportCauses = true).also {
        clients +=
            it
    }

    private fun route(proxy: () -> SteamProxy?) = SteamRoute(TestClock()) { proxy() }

    private fun assertNamesNoProxy(text: String, vararg parts: String) {
        for (part in listOf("s3cret", "trader") + parts) assertFalse(part in text, "the proxy leaked: $text")
    }

    // ---- the tunnel ---------------------------------------------------------------------------------

    @Test
    fun a_tunnel_carries_bytes_through_the_proxy_and_says_who_it_is() = runTest {
        withContext(Dispatchers.Default) {
            val proxy = proxy()
            val target = echo()
            val socket: dynamic = openSteamTunnel(SteamProxy.parse(proxy.url), "127.0.0.1", target.port).unsafeCast<Promise<Any?>>().await()
            sockets.add(socket)
            val echoed = Promise<String> { resolve, _ ->
                socket.setEncoding("utf8")
                socket.once("data") { data: String -> resolve(data) }
                socket.resume()
                socket.write("ping")
            }.await()
            assertEquals("ping", echoed)
            val authorization: String = "Basic " + js("Buffer").from("trader:s3cret-pass").toString("base64") as String
            assertEquals(listOf<Pair<String, String?>>("127.0.0.1:${target.port}" to authorization), proxy.connects)
        }
    }

    @Test
    fun any_2xx_answer_to_connect_opens_the_tunnel() = runTest {
        withContext(Dispatchers.Default) {
            val target = echo()
            for (status in listOf(200, 204)) {
                val proxy = LoopbackProxy.start(establishedWith = status).also { proxies += it }
                val socket: dynamic = openSteamTunnel(
                    SteamProxy.parse(proxy.url),
                    "127.0.0.1",
                    target.port,
                ).unsafeCast<Promise<Any?>>().await()
                sockets.add(socket)
                val echoed = Promise<String> { resolve, _ ->
                    socket.setEncoding("utf8")
                    socket.once("data") { data: String -> resolve(data) }
                    socket.resume()
                    socket.write("ping")
                }.await()
                assertEquals("ping", echoed, "$status")
            }
        }
    }

    @Test
    fun a_tunnel_that_cannot_open_fails_coded_and_in_its_own_words() = runTest {
        withContext(Dispatchers.Default) {
            val closed = LoopbackProxy.closedPort()
            val refusing = proxy(refuseWith = 407)
            val silent: dynamic = nodeNet.createServer { socket: dynamic -> sockets.add(socket) }
            sockets.add(silent)
            Promise<Unit> { resolve, _ -> silent.listen(0, "127.0.0.1") { resolve(Unit) } }.await()
            val silentPort = (silent.address().port as Number).toInt()

            val hangingUp: dynamic = nodeNet.createServer { socket: dynamic -> socket.end() }
            sockets.add(hangingUp)
            Promise<Unit> { resolve, _ -> hangingUp.listen(0, "127.0.0.1") { resolve(Unit) } }.await()
            val hangingUpPort = (hangingUp.address().port as Number).toInt()

            val cases = listOf(
                Triple("http://trader:s3cret-pass@127.0.0.1:$closed", 10_000, "could not connect to the proxy: ECONNREFUSED"),
                Triple(
                    "http://trader:s3cret-pass@127.0.0.1:$hangingUpPort",
                    10_000,
                    "the proxy closed the connection before the tunnel opened",
                ),
                Triple(refusing.url, 10_000, "the proxy answered CONNECT with HTTP 407"),
                Triple("http://trader:s3cret-pass@127.0.0.1:$silentPort", 200, "no answer from the proxy within 200 ms"),
            )
            for ((url, timeout, detail) in cases) {
                val error: dynamic = runCatching {
                    openSteamTunnel(SteamProxy.parse(url), "steamcommunity.com", 443, timeout).unsafeCast<Promise<Any?>>().await()
                }.exceptionOrNull()
                assertEquals(STEAM_PROXY_UNREACHABLE_CODE, error.code, url)
                assertEquals(detail, error.message)
                assertNamesNoProxy(error.message as String, "127.0.0.1", "$closed", "${refusing.port}", "$silentPort")
            }
        }
    }

    // ---- the Steam HTTP engine -----------------------------------------------------------------------

    @Test
    fun a_steam_call_goes_through_the_routes_proxy_and_the_route_hears_it_did() = runTest {
        withContext(Dispatchers.Default) {
            val proxy = proxy()
            val steam = steamServer()
            val route = route { SteamProxy.parse(proxy.url) }

            val body = client(route).get("${steam.base}/IEconService/GetTradeOffers/v1/").bodyAsText()

            assertEquals("""{"response":{}}""", body)
            assertEquals(listOf(steam.base.removePrefix("http://")), proxy.connects.map { it.first })
            assertEquals(1, steam.seen.size)
            assertFalse(route.isUnreachable)
        }
    }

    @Test
    fun an_answer_on_a_pooled_tunnel_ends_an_outage_another_leg_reported() = runTest {
        withContext(Dispatchers.Default) {
            val proxy = proxy()
            val steam = steamServer()
            val through = SteamProxy.parse(proxy.url)
            val route = route { through }
            val client = client(route)
            client.get("${steam.base}/1").bodyAsText()

            // `undici` hands the socket back to its pool a moment after the body is read; the next read comes later anyway.
            delay(100)
            // A proof through the same proxy failed to reach it; the Steam reads keep going on the pooled tunnel.
            route.unreachable(through, "could not connect to the proxy: ETIMEDOUT")
            client.get("${steam.base}/2").bodyAsText()

            assertEquals(1, proxy.connects.size, "the second call reused the first one's tunnel")
            assertFalse(route.isUnreachable)
        }
    }

    @Test
    fun an_unreachable_proxy_fails_the_call_with_its_own_cause_and_nothing_goes_direct() = runTest {
        withContext(Dispatchers.Default) {
            val steam = steamServer()
            val closed = LoopbackProxy.closedPort()
            val route = route { SteamProxy.parse("http://trader:s3cret-pass@127.0.0.1:$closed") }

            val failure = assertFailsWith<SteamProxyUnreachableException> { client(route).get("${steam.base}/tradeoffer/new/send") }

            assertEquals(0, steam.seen.size, "no request reached Steam around the proxy")
            assertEquals("could not connect to the proxy: ECONNREFUSED", failure.detail)
            assertNamesNoProxy(failure.stackTraceToString(), ":$closed")
            assertTrue(route.isUnreachable)
            assertEquals("could not connect to the proxy: ECONNREFUSED", route.detail)
        }
    }

    @Test
    fun a_proxy_that_refuses_the_connect_is_unreachable_too() = runTest {
        withContext(Dispatchers.Default) {
            val steam = steamServer()
            val refusing = proxy(refuseWith = 502)
            val route = route { SteamProxy.parse(refusing.url) }

            val failure = assertFailsWith<SteamProxyUnreachableException> { client(route).get("${steam.base}/x") }

            assertEquals("the proxy answered CONNECT with HTTP 502", failure.detail)
            assertEquals(0, steam.seen.size)
            assertEquals(1, refusing.connects.size)
        }
    }

    @Test
    fun two_trackers_each_go_through_their_own_proxy_and_one_without_goes_direct() = runTest {
        withContext(Dispatchers.Default) {
            val first = proxy(user = "first", password = "first-pass")
            val second = proxy(user = "second", password = "second-pass")
            val steam = steamServer()
            val a = client(route { SteamProxy.parse(first.url) })
            val b = client(route { SteamProxy.parse(second.url) })
            val direct = client(route { null })

            repeat(2) {
                a.get("${steam.base}/a").bodyAsText()
                b.get("${steam.base}/b").bodyAsText()
                direct.get("${steam.base}/c").bodyAsText()
            }

            val basic = { credentials: String -> "Basic " + js("Buffer").from(credentials).toString("base64") as String }
            assertTrue(first.connects.isNotEmpty() && first.connects.all { it.second == basic("first:first-pass") }, "${first.connects}")
            assertTrue(
                second.connects.isNotEmpty() &&
                    second.connects.all {
                        it.second == basic("second:second-pass")
                    },
                "${second.connects}",
            )
            assertEquals(listOf("/a", "/b", "/c", "/a", "/b", "/c"), steam.seen.map { it.url })
        }
    }

    @Test
    fun a_new_proxy_takes_over_from_the_next_call() = runTest {
        withContext(Dispatchers.Default) {
            val first = proxy(user = "first", password = "first-pass")
            val second = proxy(user = "second", password = "second-pass")
            val steam = steamServer()
            var current = SteamProxy.parse(first.url)
            val client = client(route { current })

            client.get("${steam.base}/1").bodyAsText()
            current = SteamProxy.parse(second.url)
            client.get("${steam.base}/2").bodyAsText()

            assertEquals(1, first.connects.size)
            assertEquals(1, second.connects.size)
            assertEquals(listOf("/1", "/2"), steam.seen.map { it.url })
        }
    }

    @Test
    fun loading_undici_leaves_no_agent_of_its_own_as_nodes_global_dispatcher() = runTest {
        withContext(Dispatchers.Default) {
            val undici: dynamic = undiciModule().unsafeCast<Promise<Any?>>().await()
            for (key in listOf("undici.globalDispatcher.1", "undici.globalDispatcher.2")) {
                val installed: dynamic = js("globalThis")[js("Symbol").`for`(key)]
                assertFalse(js("installed instanceof undici.Dispatcher") as Boolean, "$key is this package's undici")
            }
        }
    }

    @Test
    fun a_failed_undici_import_is_tried_again_on_the_next_call() = runTest {
        withContext(Dispatchers.Default) {
            var imports = 0
            val load = undiciLoader {
                imports++
                if (imports == 1) Promise.reject(Error("Cannot find package 'undici'")) else undiciModule()
            }

            assertTrue(runCatching { load().unsafeCast<Promise<Any?>>().await() }.isFailure)
            val undici: dynamic = load().unsafeCast<Promise<Any?>>().await()
            load().unsafeCast<Promise<Any?>>().await()

            assertEquals("function", jsTypeOf(undici.Agent))
            assertEquals(2, imports, "a failed import is not kept, a loaded one is")
        }
    }

    @Test
    fun a_proxy_client_that_did_not_load_fails_one_call_and_the_next_goes_through_the_proxy() = runTest {
        withContext(Dispatchers.Default) {
            val proxy = proxy()
            val steam = steamServer()
            val route = route { SteamProxy.parse(proxy.url) }
            var loads = 0
            val transport = SteamHttpTransport(route) {
                loads++
                if (loads == 1) Promise.reject(Error("Cannot find package 'undici'")) else undiciModule()
            }
            val client = createSteamHttpClient(transport.engine, describeTransportCauses = true).also { clients += it }

            val failure = assertFailsWith<SteamProxyUnreachableException> { client.get("${steam.base}/1") }
            assertEquals("the proxy client could not be loaded", failure.detail)
            assertFalse(route.isUnreachable, "the proxy was never asked")
            assertEquals(0, steam.seen.size)

            assertEquals("""{"response":{}}""", client.get("${steam.base}/2").bodyAsText())
            assertEquals(2, loads)
            assertEquals(listOf("/2"), steam.seen.map { it.url })
            assertEquals(1, proxy.connects.size)
            transport.close()
        }
    }

    // ---- the prover's target leg ---------------------------------------------------------------------

    /**
     * A 1.0.1 prover package's transport: `dist/index.js` with the generic `connectTcp(host, port, { connect })` and its
     * `TcpIoChannel`, and no `dist/node.js` with `wrapTcpSocket`. The channel is built on the tunnel; `connectTcp`,
     * which would dial on its own, is never called (it throws here).
     */
    private suspend fun transportDir(): String {
        val root = tempDir()
        val dist = join(join(root, "transport"), "dist")
        nodeFs.promises.mkdir(dist, js("({recursive: true})")).unsafeCast<Promise<Any?>>().await()
        nodeFs.promises.writeFile(join(root, "package.json"), """{"type":"module"}""").unsafeCast<Promise<Any?>>().await()
        nodeFs.promises.writeFile(join(dist, "index.js"), GENERIC_CONNECT_TCP).unsafeCast<Promise<Any?>>().await()
        return root
    }

    @Test
    fun the_proof_target_leg_tunnels_through_the_proxy() = runTest {
        withContext(Dispatchers.Default) {
            val proxy = proxy()
            val target = echo()
            val errors = mutableListOf<String>()
            val connect = proxiedTcpConnector(transportDir(), SteamProxy.parse(proxy.url)) { errors += it }

            val channel: dynamic = connect("127.0.0.1", target.port).await()!!
            channel.write(js("new Uint8Array([22, 3, 3])")).unsafeCast<Promise<Any?>>().await()
            val echoed: dynamic = channel.read().unsafeCast<Promise<dynamic>>().await()
            channel.close().unsafeCast<Promise<Any?>>().await()

            assertEquals(listOf(22, 3, 3), js("Array.from")(echoed).unsafeCast<Array<Int>>().toList())
            assertEquals(listOf("127.0.0.1:${target.port}"), proxy.connects.map { it.first })
            assertEquals(emptyList(), errors)
        }
    }

    @Test
    fun the_proof_target_leg_hands_the_tunnel_to_the_shipped_transports_wrap_tcp_socket() = runTest {
        withContext(Dispatchers.Default) {
            val proxy = proxy()
            val target = echo()
            val errors = mutableListOf<String>()
            // The package as it ships (1.2.0): its `node.js` has `wrapTcpSocket`, so the generic connector is not used.
            val connect = proxiedTcpConnector(distributedDir(), SteamProxy.parse(proxy.url)) { errors += it }

            val channel: dynamic = connect("127.0.0.1", target.port).await()!!
            channel.write(js("new Uint8Array([22, 3, 1])")).unsafeCast<Promise<Any?>>().await()
            val echoed: dynamic = channel.read().unsafeCast<Promise<dynamic>>().await()

            assertEquals("function", jsTypeOf(channel.abort), "the transport's own Node channel, which the generic one is not")
            channel.close().unsafeCast<Promise<Any?>>().await()
            assertEquals(listOf(22, 3, 1), js("Array.from")(echoed).unsafeCast<Array<Int>>().toList())
            assertEquals(listOf("127.0.0.1:${target.port}"), proxy.connects.map { it.first })
            assertEquals(emptyList(), errors)
        }
    }

    @Test
    fun an_unreachable_proxy_fails_the_proof_leg_as_the_proxys_and_never_dials_steam() = runTest {
        withContext(Dispatchers.Default) {
            val target = echo()
            val closed = LoopbackProxy.closedPort()
            // Both handoffs: the shipped package's `wrapTcpSocket`, and 1.0.1's `TcpIoChannel`.
            for (assetsDir in listOf(distributedDir(), transportDir())) {
                val record = ProverSessionRecord()
                val connect = proxiedTcpConnector(assetsDir, SteamProxy.parse("http://trader:s3cret-pass@127.0.0.1:$closed")) {
                    record.proxyError = record.proxyError ?: it
                }

                val failed = runCatching { recordedTargetChannel("127.0.0.1:${target.port}", record, connect = connect).await() }.isFailure

                assertTrue(failed)
                assertEquals(0, target.accepted(), "nothing dialled the target around the proxy")
                assertEquals("could not connect to the proxy: ECONNREFUSED", record.proxyError)
                val failure = ProofFailures.classify(targetError = record.targetError, proxyError = record.proxyError, message = "io error")
                assertEquals(ProofFailure.ProxyUnreachable("could not connect to the proxy: ECONNREFUSED"), failure)
                assertNamesNoProxy(failure.toString() + record.targetError, ":$closed")
            }
        }
    }

    private companion object {
        const val GENERIC_CONNECT_TCP = """
            export class TcpIoChannel {
              #socket;
              #reader;
              #writer;
              constructor(socket) {
                this.#socket = socket;
                this.#reader = socket.readable.getReader();
                this.#writer = socket.writable.getWriter();
              }
              async read() { const { value, done } = await this.#reader.read(); return done ? null : value; }
              write(data) { return this.#writer.write(new Uint8Array(data)); }
              async close() { try { await this.#writer.close(); } finally { await this.#socket.close(); } }
            }
            export async function connectTcp(host, port, opts) {
              throw new Error('the generic connectTcp was called for ' + host + ':' + port);
            }
        """
    }
}
