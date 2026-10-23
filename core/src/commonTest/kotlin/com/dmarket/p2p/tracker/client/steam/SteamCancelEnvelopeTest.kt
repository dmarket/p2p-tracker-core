package com.dmarket.p2p.tracker.client.steam

import com.dmarket.p2p.tracker.port.steam.CancelOfferResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The order of the envelope rules in [SteamOfferWire.readCancel], one table per rule. */
class SteamCancelEnvelopeTest {

    @Test
    fun rule_1_a_str_error_is_a_refusal_on_any_status_even_beside_a_success() {
        for (status in listOf(200, 500)) {
            for (body in listOf("""{"strError":"There was an error cancelling (8)"}""", """{"strError":"nope","success":1}""")) {
                val result = assertIs<CancelOfferResult.Refused>(SteamOfferWire.readCancel(status, body), "$status $body")
                assertTrue("HTTP $status" in result.error, result.error)
            }
        }
    }

    @Test
    fun rule_2_a_success_decides_on_any_status() {
        for (status in listOf(200, 500, 403)) {
            assertEquals(CancelOfferResult.Cancelled, SteamOfferWire.readCancel(status, """{"success":1}"""), "$status")
            assertEquals(CancelOfferResult.Cancelled, SteamOfferWire.readCancel(status, """{"success":true}"""), "$status")
            assertEquals(CancelOfferResult.NotLive, SteamOfferWire.readCancel(status, """{"success":11}"""), "$status")
            assertIs<CancelOfferResult.Unanswered>(SteamOfferWire.readCancel(status, """{"success":16}"""), "$status")
            assertEquals(
                CancelOfferResult.Refused("Steam cancel refused with EResult 15"),
                SteamOfferWire.readCancel(status, """{"success":15}"""),
            )
            assertEquals(
                CancelOfferResult.Refused("Steam cancel refused with EResult false"),
                SteamOfferWire.readCancel(status, """{"success":false}"""),
            )
        }
        // The id beside a non-OK success does not make it a cancel.
        assertEquals(CancelOfferResult.NotLive, SteamOfferWire.readCancel(200, """{"success":11,"tradeofferid":"9"}"""))
    }

    @Test
    fun rule_3_only_a_2xx_carrying_the_offer_id_landed() {
        assertEquals(CancelOfferResult.Cancelled, SteamOfferWire.readCancel(200, """{"tradeofferid":"9"}"""))
        assertIs<CancelOfferResult.Refused>(SteamOfferWire.readCancel(500, """{"tradeofferid":"9"}"""))
        assertIs<CancelOfferResult.Refused>(SteamOfferWire.readCancel(200, """{"tradeofferid":9}"""), "a number is not Steam's id")
    }

    @Test
    fun rule_4_anything_else_is_refused_html_and_login_pages_included() {
        val bodies = listOf(
            "<html><body>Sign In</body></html>",
            "",
            "{}",
            """{"tradeoff""",
            "[]",
            null,
        )
        for (status in listOf(200, 302, 500)) {
            for (body in bodies) {
                val result = assertIs<CancelOfferResult.Refused>(SteamOfferWire.readCancel(status, body), "$status $body")
                assertEquals("Steam cancel answer is not one of Steam's (HTTP $status)", result.error)
            }
        }
    }
}
