package com.dmarket.p2p.tracker.model.marketplace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TradingApiKeyTest {
    // RFC 8032 §7.1, TEST 1.
    private val publicKey = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
    private val seed = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"
    private val otherPublic = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"

    @Test
    fun a_nacl_secret_and_a_bare_seed_both_parse_to_the_seed() {
        assertEquals(seed, TradingApiKey.parse(publicKey, seed + publicKey)?.secretSeed)
        assertEquals(seed, TradingApiKey.parse(publicKey.uppercase(), " ${seed.uppercase()} ")?.secretSeed)
        assertEquals(seed, TradingApiKey.parse(publicKey, seed + publicKey.uppercase())?.secretSeed, "the NaCl tail matches in any case")
    }

    @Test
    fun the_public_key_keeps_the_case_it_was_pushed_in() {
        val mixed = publicKey.take(32).uppercase() + publicKey.drop(32)
        assertEquals(mixed, TradingApiKey.parse(" $mixed\n", seed)?.publicKey, "only the whitespace goes")
        assertEquals(publicKey.uppercase(), TradingApiKey.parse(publicKey.uppercase(), seed + publicKey)?.publicKey)
    }

    @Test
    fun a_pair_from_two_keys_or_anything_not_hex_of_the_right_length_is_refused() {
        assertNull(TradingApiKey.parse(publicKey, seed + otherPublic), "the NaCl tail must be the public key")
        assertNull(TradingApiKey.parse(publicKey, "zz" + seed.drop(2)))
        assertNull(TradingApiKey.parse(publicKey.drop(2), seed))
        assertNull(TradingApiKey.parse("zz" + publicKey.drop(2), seed))
        assertNull(TradingApiKey.parse(publicKey, seed.drop(2)))
        assertNull(TradingApiKey.parse(publicKey, seed + publicKey + "00"))
        assertNull(TradingApiKey.parse(publicKey, ""))
    }

    @Test
    fun a_key_never_prints_its_secret() {
        val printed = TradingApiKey.parse(publicKey, seed + publicKey).toString()
        assertTrue(publicKey in printed)
        assertTrue(seed !in printed, printed)
    }

    @Test
    fun two_keys_are_equal_when_both_halves_are() {
        assertEquals(TradingApiKey.parse(publicKey, seed + publicKey), TradingApiKey.parse(publicKey, seed))
        assertNotEquals(TradingApiKey.parse(publicKey, seed), TradingApiKey.parse(otherPublic, seed))
        assertNotEquals(TradingApiKey.parse(publicKey, seed), TradingApiKey.parse(publicKey, otherPublic))
    }

    @Test
    fun a_key_in_another_case_is_the_same_key() {
        val lower = requireNotNull(TradingApiKey.parse(publicKey, seed))
        val upper = requireNotNull(TradingApiKey.parse(publicKey.uppercase(), seed.uppercase()))
        assertEquals(lower, upper)
        assertEquals(lower.hashCode(), upper.hashCode())
    }
}
