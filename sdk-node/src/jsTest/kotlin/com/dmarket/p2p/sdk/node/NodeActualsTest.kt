package com.dmarket.p2p.sdk.node

import com.dmarket.p2p.sdk.SdkLog
import com.dmarket.p2p.sdk.TestClock
import com.dmarket.p2p.sdk.api.ManagementActions
import com.dmarket.p2p.sdk.api.ManagementApi
import com.dmarket.p2p.sdk.api.PushOutcome
import com.dmarket.p2p.sdk.health.Health
import com.dmarket.p2p.tracker.client.createSteamHttpClient
import com.dmarket.p2p.tracker.client.steam.HeaderSessionSteamOfferCreator
import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.model.steam.TradeDraft
import com.dmarket.p2p.tracker.port.steam.CreateOfferResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.await
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.resume
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class NodeActualsTest {

    // ---- file storage --------------------------------------------------------------------------

    private suspend fun tempDir(): String {
        val os: dynamic = js("require('node:os')")
        val prefix = NodeModules.path.join(os.tmpdir(), "sdk-node-test-") as String
        return NodeModules.fs.mkdtemp(prefix).unsafeCast<Promise<String>>().await()
    }

    private suspend fun exists(path: String): Boolean =
        runCatching { NodeModules.fs.stat(path).unsafeCast<Promise<dynamic>>().await() }.isSuccess

    @Test
    fun the_file_store_round_trips_and_writes_owner_only_files() = runTest {
        val dir = tempDir()
        val store = FileKeyValueStore(NodeModules.path.join(dir, "data") as String)
        assertNull(store.get("device_id"))
        assertFalse(exists(NodeModules.path.join(dir, "data") as String), "a read creates nothing")
        store.set("device_id", "abc")
        val dirStat: dynamic = NodeModules.fs.stat(NodeModules.path.join(dir, "data")).unsafeCast<Promise<dynamic>>().await()
        assertEquals(448, (dirStat.mode as Int) and 511, "0700")
        store.set("device_id", "def")
        assertEquals("def", store.get("device_id"))

        val stat: dynamic = NodeModules.fs.stat(NodeModules.path.join(dir, "data", "device_id")).unsafeCast<Promise<dynamic>>().await()
        assertEquals(384, (stat.mode as Int) and 511, "0600")

        store.remove("device_id")
        store.remove("device_id")
        assertNull(store.get("device_id"))
    }

    @Test
    fun each_account_keeps_its_state_in_its_own_directory() = runTest {
        val dir = tempDir()
        val accounts = AccountDirectories(dir)
        accounts.storeFor("76561198000000001").set("device_id", "first")
        accounts.storeFor("76561198000000099").set("device_id", "second")
        assertEquals("first", accounts.storeFor("76561198000000001").get("device_id"))
        val onDisk = NodeModules.fs.readFile(NodeModules.path.join(dir, "76561198000000099", "device_id"), "utf8")
            .unsafeCast<Promise<String>>().await()
        assertEquals("second", onDisk)
        for (id in listOf("../etc", "", "7656119800000000a", "1/2")) {
            assertFailsWith<IllegalArgumentException>(id) { accounts.storeFor(id) }
        }
    }

    @Test
    fun the_file_store_refuses_the_steam_credential() = runTest {
        val store = FileKeyValueStore(tempDir())
        assertFailsWith<IllegalArgumentException> { store.set("steam_credential", "token") }
    }

    // ---- the Node transport --------------------------------------------------------------------

    /** A local HTTP server that records each request's headers and answers [body]. */
    private suspend fun recordingServer(body: String, seen: MutableList<dynamic>): dynamic {
        val server = NodeModules.http.createServer { request: dynamic, response: dynamic ->
            seen.add(request.headers)
            request.resume()
            request.on("end") {
                response.writeHead(200, js("({'content-type': 'application/json'})"))
                response.end(body)
            }
        }
        suspendCancellableCoroutine { continuation -> server.listen(0, "127.0.0.1") { continuation.resume(Unit) } }
        return server
    }

    @Test
    fun a_create_on_node_sends_the_cookie_referer_and_origin_it_sets() = runTest {
        val seen = mutableListOf<dynamic>()
        val server = recordingServer("""{"tradeofferid":"555","needs_mobile_confirmation":true}""", seen)
        try {
            val base = "http://127.0.0.1:${server.address().port}"
            val creator = HeaderSessionSteamOfferCreator(createSteamHttpClient(requestTimeoutMs = 5_000), "sid", base)
            val credential = SteamCredential("eyJ.node.token", SteamId("76561198000000001"), Instant.fromEpochSeconds(1_900_000_000))
            val result = creator.createOffer(credential, TradeDraft(SteamId("76561198000000002"), listOf(AssetId("a1")), "tok"))

            assertEquals(CreateOfferResult.NeedsConfirmation(OfferId("555")), result)
            val headers = seen.single()
            assertEquals("steamLoginSecure=76561198000000001%7C%7CeyJ.node.token; sessionid=sid", headers.cookie as String?)
            assertEquals("$base/tradeoffer/new/?partner=39734274&token=tok", headers.referer as String?)
            assertEquals(base, headers.origin as String?)
        } finally {
            server.close()
        }
    }

    @Test
    fun the_management_listener_serves_the_api_on_loopback() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pushed = mutableListOf<String>()
        val actions = object : ManagementActions {
            override suspend fun putDmarketKey(publicKey: String, secretKey: String) = PushOutcome.Held

            override suspend fun putSteamToken(accessToken: String): PushOutcome {
                pushed += accessToken
                return PushOutcome.Held
            }

            override fun health() = Health(status = "ok", problems = emptyList(), version = "1.2.3")
        }
        val lines = mutableListOf<String>()
        val secret = "x".repeat(32)
        val server = NodeManagementServer(ManagementApi(secret, actions), scope, SdkLog(TestClock()) { lines += it })
        server.listen("127.0.0.1", 0)
        try {
            val base = "http://127.0.0.1:${server.boundPort()}"
            val fetch: dynamic = js("fetch")
            val health: dynamic = fetch(
                "$base/v1/health",
                js("({headers: {authorization: 'Bearer ' + 'x'.repeat(32)}})"),
            ).unsafeCast<Promise<dynamic>>().await()
            assertEquals(200, health.status as Int)
            assertTrue("\"ok\"" in health.text().unsafeCast<Promise<String>>().await())

            val init: dynamic = js("({method: 'PUT', headers: {authorization: 'Bearer ' + 'x'.repeat(32)}})")
            init.body = """{"accessToken":"eyJ.secret.token"}"""
            val push: dynamic = fetch("$base/v1/steam/session", init).unsafeCast<Promise<dynamic>>().await()
            assertEquals(204, push.status as Int)
            assertEquals(listOf("eyJ.secret.token"), pushed)

            val anonymous: dynamic = fetch("$base/v1/health").unsafeCast<Promise<dynamic>>().await()
            assertEquals(401, anonymous.status as Int)
            assertTrue(lines.none { "eyJ.secret.token" in it || secret in it }, "a request log never carries a header or a body")
        } finally {
            server.close()
            scope.cancel()
        }
    }
}
