package com.dmarket.p2p.tracker.model.steam

import com.dmarket.p2p.tracker.model.SteamId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SteamId64Test {

    @Test
    fun valid_seventeen_digit_id_starting_with_7656_passes() {
        val id = requireSteamId64("76561198000000001")
        assertEquals(SteamId("76561198000000001"), id)
        assertTrue(id.isValidSteamId64())
    }

    @Test
    fun rejects_ids_that_are_not_well_formed_steamid64s() {
        val invalid = listOf(
            "7656119800000000", // 16 digits — too short
            "765611980000000012", // 18 digits — too long
            "12345678901234567", // 17 digits but wrong prefix
            "7656abc9800000001", // non-numeric
            "7656 198000000001", // whitespace
            "", // blank
            " ",
        )
        for (value in invalid) {
            assertFailsWith<InvalidSteamId64Exception>("expected '$value' to be rejected") {
                requireSteamId64(value)
            }
        }
    }

    @Test
    fun isValidSteamId64_matches_requireSteamId64_verdict() {
        assertTrue(SteamId("76561198000000001").isValidSteamId64())
        assertFalse(SteamId("123").isValidSteamId64())
    }

    @Test
    fun exception_message_names_the_bad_value() {
        val ex = assertFailsWith<InvalidSteamId64Exception> { requireSteamId64("nope") }
        assertTrue(ex.message!!.contains("nope"))
    }
}
