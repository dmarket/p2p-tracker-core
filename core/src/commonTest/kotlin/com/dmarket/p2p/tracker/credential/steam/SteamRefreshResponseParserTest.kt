package com.dmarket.p2p.tracker.credential.steam

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SteamRefreshResponseParserTest {

    @Test
    fun parses_transfer_info_shape() {
        val body = """
            {
              "steamid": "76561198000000001",
              "transfer_info": [
                { "url": "https://steamcommunity.com/login/settoken", "params": { "nonce": "n1", "auth": "a1" } },
                { "url": "https://store.steampowered.com/login/settoken", "params": { "nonce": "n2", "auth": "a2" } }
              ]
            }
        """.trimIndent()

        val result = SteamRefreshResponseParser.parse(body)

        assertTrue(result is SteamRefreshResponse.Transfers)
        assertEquals("76561198000000001", result.steamId)
        assertEquals(2, result.transfers.size)
        assertEquals("https://steamcommunity.com/login/settoken", result.transfers[0].url)
        assertEquals("n1", result.transfers[0].nonce)
        assertEquals("a2", result.transfers[1].auth)
    }

    @Test
    fun parses_flat_nonce_auth_shape() {
        val body = """{ "steamid": "76561198000000001", "nonce": "flat-nonce", "auth": "flat-auth" }"""

        val result = SteamRefreshResponseParser.parse(body)

        assertTrue(result is SteamRefreshResponse.Flat)
        assertEquals("76561198000000001", result.steamId)
        assertEquals("flat-nonce", result.nonce)
        assertEquals("flat-auth", result.auth)
    }

    @Test
    fun reads_steamid_as_unquoted_number_without_precision_loss() {
        // 17-digit id as a bare JSON number would overflow a Double; the parser must keep the raw digits.
        val body = """{ "steamid": 76561198000000001, "nonce": "n", "auth": "a" }"""

        val result = SteamRefreshResponseParser.parse(body)

        assertTrue(result is SteamRefreshResponse.Flat)
        assertEquals("76561198000000001", result.steamId)
    }

    @Test
    fun accepts_steamID_capitalised_key() {
        val body = """{ "steamID": "76561198000000001", "nonce": "n", "auth": "a" }"""
        val result = SteamRefreshResponseParser.parse(body)
        assertTrue(result is SteamRefreshResponse.Flat)
        assertEquals("76561198000000001", result.steamId)
    }

    @Test
    fun unusable_when_logged_out_or_missing_fields() {
        // No nonce/auth and no transfer_info → cannot drive settoken.
        assertEquals(SteamRefreshResponse.Unusable, SteamRefreshResponseParser.parse("""{ "steamid": "76561198000000001" }"""))
        // Missing steamid.
        assertEquals(SteamRefreshResponse.Unusable, SteamRefreshResponseParser.parse("""{ "nonce": "n", "auth": "a" }"""))
        // transfer_info present but empty.
        assertEquals(
            SteamRefreshResponse.Unusable,
            SteamRefreshResponseParser.parse("""{ "steamid": "1", "transfer_info": [] }"""),
        )
    }

    @Test
    fun unusable_on_null_blank_or_garbage_body() {
        assertEquals(SteamRefreshResponse.Unusable, SteamRefreshResponseParser.parse(null))
        assertEquals(SteamRefreshResponse.Unusable, SteamRefreshResponseParser.parse(""))
        assertEquals(SteamRefreshResponse.Unusable, SteamRefreshResponseParser.parse("<html>not json</html>"))
        // A JSON array (not an object) is not a valid refresh response.
        assertEquals(SteamRefreshResponse.Unusable, SteamRefreshResponseParser.parse("[1,2,3]"))
    }
}
