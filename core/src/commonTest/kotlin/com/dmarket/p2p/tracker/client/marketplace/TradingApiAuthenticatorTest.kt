package com.dmarket.p2p.tracker.client.marketplace

import com.dmarket.p2p.tracker.client.createHttpClient
import com.dmarket.p2p.tracker.config.MarketplaceRetryConfig
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.marketplace.TradingApiKey
import com.dmarket.p2p.tracker.port.host.Clock
import com.dmarket.p2p.tracker.port.marketplace.Ed25519Signer
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceUnauthorizedException
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class TradingApiAuthenticatorTest {
    private val publicKey = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
    private val seed = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"
    private val key = requireNotNull(TradingApiKey.parse(publicKey, seed + publicKey))
    private val t0 = Instant.fromEpochSeconds(1_781_611_200)
    private val clock = object : Clock {
        override fun now(): Instant = t0
    }
    private val heartbeat = MarketplaceRequest("POST", "/exchange/v1/p2p/ext/heartbeat", "{\"a\":1}")

    /** Signs nothing: returns the message bytes, so a test can read what was signed off the header. */
    private val echoSigner = Ed25519Signer { message -> message }

    private fun String.hexToText() = hexToByteArray().decodeToString()

    @Test
    fun the_signed_string_is_method_path_body_and_date_in_that_order() {
        assertEquals(
            "POST/exchange/v1/p2p/ext/heartbeat?x=1{\"a\":1}1781611200",
            TradingApiSignature.unsigned("POST", "/exchange/v1/p2p/ext/heartbeat?x=1", "{\"a\":1}", "1781611200"),
        )
    }

    @Test
    fun every_request_carries_the_public_key_the_date_and_the_dmar_signature() = runTest {
        val headers = TradingApiAuthenticator({ key }, clock) { echoSigner }.headersFor(heartbeat)

        assertEquals(setOf(TradingApiSignature.API_KEY, TradingApiSignature.SIGN_DATE, TradingApiSignature.REQUEST_SIGN), headers.keys)
        assertEquals(publicKey, headers[TradingApiSignature.API_KEY])
        assertEquals("1781611200", headers[TradingApiSignature.SIGN_DATE])
        val signature = headers.getValue(TradingApiSignature.REQUEST_SIGN)
        assertTrue(signature.startsWith("dmar ed25519 "), signature)
        assertEquals("POST/exchange/v1/p2p/ext/heartbeat{\"a\":1}1781611200", signature.removePrefix("dmar ed25519 ").hexToText())
    }

    @Test
    fun the_secret_never_goes_on_the_wire() = runTest {
        // The platform signer: a real signature, not the echo.
        val headers = TradingApiAuthenticator({ key }, clock).headersFor(heartbeat)

        assertEquals(128, headers.getValue(TradingApiSignature.REQUEST_SIGN).removePrefix("dmar ed25519 ").length)
        assertFalse(headers.values.any { seed in it }, "the seed never goes in a header")
        assertFalse(headers.keys.any { it.equals("Authorization", ignoreCase = true) }, "no session token rides a signed call")
    }

    @Test
    fun the_signer_is_built_once_per_key_and_again_for_a_new_one() = runTest {
        val other = requireNotNull(TradingApiKey.parse("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c", seed))
        var current = key
        val built = mutableListOf<TradingApiKey>()
        val authenticator = TradingApiAuthenticator({ current }, clock) {
            built += it
            echoSigner
        }

        repeat(3) { authenticator.headersFor(heartbeat) }
        current = other
        repeat(2) { authenticator.headersFor(heartbeat) }

        assertEquals(listOf(key, other), built)
    }

    @Test
    fun without_a_key_nothing_is_attached_and_a_401_is_final() = runTest {
        var built = 0
        val authenticator = TradingApiAuthenticator({ null }, clock) {
            built++
            echoSigner
        }

        assertTrue(authenticator.headersFor(MarketplaceRequest("GET", "/x", "")).isEmpty())
        assertNull(authenticator.tokenOrNull())
        assertFalse(authenticator.refreshOnUnauthorized(), "there is nothing to refresh")
        assertEquals(0, built)
    }

    @Test
    fun a_401_on_a_signed_call_is_final() = runTest {
        val sent = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            sent += request
            respondError(HttpStatusCode.Unauthorized)
        }
        val client = KtorMarketplaceClient(
            createHttpClient(engine),
            "https://gateway.test",
            TradingApiAuthenticator({ key }, clock) { echoSigner },
            MarketplaceRetryConfig(retryBaseDelayMs = 1, retryMaxDelayMs = 1),
        )

        assertFailsWith<MarketplaceUnauthorizedException> { client.acceptDeal(DealId("d-1")) }
        assertEquals(1, sent.size, "a rejected key is not retried")
        assertEquals(publicKey, sent.single().headers[TradingApiSignature.API_KEY])
    }
}
