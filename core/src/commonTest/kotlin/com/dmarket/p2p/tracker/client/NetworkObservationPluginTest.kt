package com.dmarket.p2p.tracker.client

import com.dmarket.p2p.tracker.model.ExchangeOrigin
import com.dmarket.p2p.tracker.model.NetworkExchange
import com.dmarket.p2p.tracker.port.host.NetworkObserver
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkObservationPluginTest {
    private class RecordingObserver : NetworkObserver {
        val exchanges = mutableListOf<NetworkExchange>()
        override suspend fun onExchange(exchange: NetworkExchange) {
            exchanges += exchange
        }
    }

    private fun mockEngine() = MockEngine {
        respond(content = "{}", headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
    }

    @Test
    fun steam_read_access_token_is_redacted_in_url_and_tagged_steam() = runTest {
        val observer = RecordingObserver()
        val client = createHttpClient(engine = mockEngine(), observer = observer, origin = ExchangeOrigin.STEAM)
        client.get("https://api.steampowered.com/IEconService/GetTradeOffers/v1/?access_token=SECRETJWT&get_sent_offers=1")
            .bodyAsText()
        val exchange = observer.exchanges.single()
        assertEquals(ExchangeOrigin.STEAM, exchange.origin)
        assertEquals("GET", exchange.method)
        assertFalse("SECRETJWT" in exchange.url)
        assertTrue("access_token=<redacted>" in exchange.url)
        assertTrue("get_sent_offers=1" in exchange.url) // non-secret param preserved
        assertEquals(200, exchange.responseStatus)
    }

    @Test
    fun marketplace_bearer_header_is_redacted_and_tagged_marketplace() = runTest {
        val observer = RecordingObserver()
        val client = createHttpClient(engine = mockEngine(), observer = observer, origin = ExchangeOrigin.MARKETPLACE)
        client.post("https://api.dmarket.com/exchange/v1/p2p/ext/heartbeat") {
            bearerAuth("SECRET_DM_JWT")
            setBody("""{"device_id":"dev-1"}""")
        }.bodyAsText()
        val exchange = observer.exchanges.single()
        assertEquals(ExchangeOrigin.MARKETPLACE, exchange.origin)
        assertFalse(exchange.headers.values.any { "SECRET_DM_JWT" in it })
        assertEquals("<redacted>", exchange.headers[HttpHeaders.Authorization])
        assertTrue(exchange.requestBody?.contains("dev-1") == true) // non-secret body preserved
    }

    @Test
    fun response_body_is_captured_and_downstream_read_still_works() = runTest {
        val observer = RecordingObserver()
        val body = """{"strError":"There was an error sending your trade offer. (26)"}"""
        val engine = MockEngine {
            respond(content = body, headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        val client = createHttpClient(engine = engine, observer = observer, origin = ExchangeOrigin.MARKETPLACE)
        // The caller (as KtorMarketplaceClient.send does) reads the body after the plugin already did;
        // Ktor's cached body must still deliver it in full — no double-read regression.
        val downstream = client.post("https://api.dmarket.com/exchange/v1/p2p/ext/trade-actions") {
            setBody("{}")
        }.bodyAsText()
        assertEquals(body, downstream)
        val exchange = observer.exchanges.single()
        assertTrue(exchange.responseBody?.contains("strError") == true)
    }

    @Test
    fun secret_token_in_response_body_is_redacted() = runTest {
        val observer = RecordingObserver()
        val engine = MockEngine {
            respond(
                content = """{"access_token":"LEAKEDJWT","ok":true}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = createHttpClient(engine = engine, observer = observer, origin = ExchangeOrigin.STEAM)
        client.get("https://api.steampowered.com/x").bodyAsText()
        val exchange = observer.exchanges.single()
        assertFalse("LEAKEDJWT" in (exchange.responseBody ?: ""))
        assertTrue(exchange.responseBody?.contains("<redacted>") == true)
        assertTrue(exchange.responseBody?.contains("\"ok\":true") == true) // non-secret field preserved
    }

    @Test
    fun exchange_carries_a_real_wall_clock_start_time() = runTest {
        // Regression: startedAtEpochMs was hardcoded 0L, making the start instant meaningless to
        // consumers. It must now carry a real epoch-ms timestamp captured at request time.
        val observer = RecordingObserver()
        val client = createHttpClient(engine = mockEngine(), observer = observer, origin = ExchangeOrigin.STEAM)
        client.get("https://api.steampowered.com/x").bodyAsText()
        assertTrue(observer.exchanges.single().startedAtEpochMs > 0L)
    }

    @Test
    fun no_op_observer_installs_no_plugin_and_records_nothing() = runTest {
        // The default factory uses NoOpNetworkObserver; a plain client must not blow up or capture.
        val client = createHttpClient(engine = mockEngine())
        val status = client.get("https://api.steampowered.com/x?access_token=abc").bodyAsText()
        assertEquals("{}", status)
    }
}
