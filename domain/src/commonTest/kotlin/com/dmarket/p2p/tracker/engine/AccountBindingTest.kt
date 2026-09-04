package com.dmarket.p2p.tracker.engine

import com.dmarket.p2p.tracker.model.SteamId
import kotlin.test.Test
import kotlin.test.assertEquals

class AccountBindingTest {
    private val token = SteamId("76561198000000001")

    @Test
    fun null_expected_is_unknown() {
        assertEquals(AccountBindingStatus.UNKNOWN, AccountBinding.evaluate(null, token))
    }

    @Test
    fun equal_ids_is_match() {
        assertEquals(AccountBindingStatus.MATCH, AccountBinding.evaluate(SteamId("76561198000000001"), token))
    }

    @Test
    fun different_ids_is_mismatch() {
        assertEquals(AccountBindingStatus.MISMATCH, AccountBinding.evaluate(SteamId("76561198000000099"), token))
    }
}
