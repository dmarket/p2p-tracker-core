package com.dmarket.p2p.sdk

import com.dmarket.p2p.sdk.signing.Ed25519Signer
import com.dmarket.p2p.sdk.signing.TradingApiAuthenticator
import com.dmarket.p2p.sdk.signing.TradingApiSignature
import com.dmarket.p2p.tracker.client.marketplace.MarketplaceRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SigningTest {

    private fun hexToText(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray().decodeToString()

    @Test
    fun the_signed_string_is_method_path_body_and_date_in_that_order() {
        assertEquals(
            "POST/exchange/v1/p2p/ext/heartbeat{\"a\":1}1781611200",
            TradingApiSignature.unsigned("POST", "/exchange/v1/p2p/ext/heartbeat", "{\"a\":1}", "1781611200"),
        )
    }

    @Test
    fun every_request_carries_the_public_key_the_date_and_the_dmar_signature() = runTest {
        val authenticator = TradingApiAuthenticator({ testKey() }, { echoSigner }, TestClock())
        val headers = authenticator.headersFor(MarketplaceRequest("POST", "/exchange/v1/p2p/ext/heartbeat", "{}"))

        assertEquals(PUBLIC_KEY, headers[TradingApiSignature.API_KEY])
        assertEquals(T0.epochSeconds.toString(), headers[TradingApiSignature.SIGN_DATE])
        val signature = headers.getValue(TradingApiSignature.REQUEST_SIGN)
        assertTrue(signature.startsWith("dmar ed25519 "), signature)
        assertEquals("POST/exchange/v1/p2p/ext/heartbeat{}${T0.epochSeconds}", hexToText(signature.removePrefix("dmar ed25519 ")))
        assertFalse(headers.values.any { SECRET_SEED in it }, "the secret never goes on the wire")
    }

    @Test
    fun without_a_key_nothing_is_attached_and_a_401_is_final() = runTest {
        val authenticator = TradingApiAuthenticator({ null }, { echoSigner }, TestClock())
        assertTrue(authenticator.headersFor(MarketplaceRequest("GET", "/x", "")).isEmpty())
        assertNull(authenticator.tokenOrNull())
        assertFalse(authenticator.refreshOnUnauthorized(), "there is nothing to refresh")
    }

    @Test
    fun the_signer_is_built_once_per_key() = runTest {
        var built = 0
        val authenticator = TradingApiAuthenticator({ testKey() }, {
            built++
            Ed25519Signer { it }
        }, TestClock())
        repeat(3) { authenticator.headersFor(MarketplaceRequest("GET", "/x", "")) }
        assertEquals(1, built)
    }
}
