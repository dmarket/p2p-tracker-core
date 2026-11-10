package com.dmarket.p2p.tracker.notary

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IssuanceModeTest {
    @Test
    fun modes_round_trip_using_the_existing_wire_names() {
        for ((mode, wire) in listOf(IssuanceMode.MPC to "mpc", IssuanceMode.PROXY to "proxy")) {
            assertEquals(mode, IssuanceMode.fromWire(wire))
            assertEquals("\"$wire\"", Json.encodeToString(IssuanceMode.serializer(), mode))
            assertEquals(mode, Json.decodeFromString(IssuanceMode.serializer(), "\"$wire\""))
        }
    }

    @Test
    fun invalid_values_have_one_redacted_error_on_every_boundary() {
        for (value in listOf("", "MPC", "auto", "secret-value")) {
            val direct = assertFailsWith<IllegalArgumentException> { IssuanceMode.fromWire(value) }
            val json = assertFailsWith<IllegalArgumentException> { Json.decodeFromString(IssuanceMode.serializer(), "\"$value\"") }
            assertEquals("issuance mode must be mpc or proxy", direct.message)
            assertEquals(direct.message, json.message)
        }
    }
}
