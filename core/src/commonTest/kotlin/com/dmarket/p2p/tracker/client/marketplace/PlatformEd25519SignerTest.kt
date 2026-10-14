package com.dmarket.p2p.tracker.client.marketplace

import com.dmarket.p2p.tracker.model.marketplace.TradingApiKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** The platform signer itself: the same vectors on the JVM (JDK provider) and on Node (WebCrypto). */
class PlatformEd25519SignerTest {
    // RFC 8032 §7.1, TEST 1.
    private val publicKey = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
    private val seed = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"

    private fun key(public: String = publicKey, secret: String = seed + publicKey) = requireNotNull(TradingApiKey.parse(public, secret))

    @Test
    fun the_signer_reproduces_the_rfc_8032_test_vector() = runTest {
        assertEquals(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
            platformEd25519Signer(key()).sign(ByteArray(0)).toHexString(),
        )
    }

    @Test
    fun a_request_string_signs_as_an_independent_implementation_signs_it() = runTest {
        // The same string signed with node:crypto, a different Ed25519 implementation than either actual.
        val unsigned = TradingApiSignature.unsigned("POST", "/exchange/v1/p2p/ext/heartbeat", "{}", "1781611200")
        assertEquals(
            "aa342ac554d527b6437e21ade436dab17e16f612556b4e92023e1ac68faf0f03794dbdecfdeb6f3defc23b7c6744e08c6f6c280dbc9e9ee89e704d6b91e55c07",
            platformEd25519Signer(key(secret = seed)).sign(unsigned.encodeToByteArray()).toHexString(),
        )
    }

    @Test
    fun a_seed_that_does_not_belong_to_the_public_key_cannot_sign() = runTest {
        val otherSeed = "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb"
        val refused = assertFailsWith<IllegalArgumentException> { platformEd25519Signer(key(secret = otherSeed)) }
        assertFalse(otherSeed in refused.message.orEmpty(), "the refusal names no key")
    }

    @Test
    fun a_public_key_that_is_not_a_curve_point_is_a_key_error() = runTest {
        // y = 2 has no x on edwards25519.
        val notAPoint = "02" + "00".repeat(31)
        assertFailsWith<IllegalArgumentException> { platformEd25519Signer(key(public = notAPoint, secret = seed)) }
    }
}
