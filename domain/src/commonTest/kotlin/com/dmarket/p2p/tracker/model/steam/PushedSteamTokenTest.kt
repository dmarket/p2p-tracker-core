package com.dmarket.p2p.tracker.model.steam

import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.support.base64UrlEncode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class PushedSteamTokenTest {
    private val now = Instant.parse("2026-06-16T12:00:00Z")
    private val steamId = "76561198000000001"

    /** Shaped like Steam's access token: a JWT whose payload carries [sub], [aud] and [exp]. Unsigned. */
    private fun steamToken(sub: String = steamId, aud: List<String> = listOf("web", "mobile"), exp: Instant? = now + 24.hours): String {
        val claims = listOfNotNull(
            """"iss":"r:test"""",
            """"sub":"$sub"""",
            """"aud":[${aud.joinToString(",") { "\"$it\"" }}]""",
            exp?.let { """"exp":${it.epochSeconds}""" },
            """"iat":${now.epochSeconds}""",
        )
        val header = base64UrlEncode("""{"alg":"EdDSA","typ":"JWT"}""")
        return "$header.${base64UrlEncode(claims.joinToString(",", "{", "}"))}.c2lnbmF0dXJl"
    }

    @Test
    fun mobile_app_steam_client_and_plain_web_tokens_are_accepted() {
        for (aud in listOf(listOf("web", "mobile"), listOf("client", "web"), listOf("web"))) {
            val token = steamToken(aud = aud)
            val check = PushedSteamToken.check(token, now)
            assertIs<SteamTokenCheck.Accepted>(check, "aud=$aud")
            assertEquals(SteamCredential(token, SteamId(steamId), now + 24.hours), check.credential)
        }
    }

    @Test
    fun surrounding_whitespace_is_not_part_of_the_held_token() {
        val token = steamToken()
        val check = PushedSteamToken.check(" $token\n", now)
        assertIs<SteamTokenCheck.Accepted>(check)
        assertEquals(token, check.credential.token)
    }

    @Test
    fun a_token_expiring_after_now_is_accepted_and_one_expiring_at_now_is_not() {
        assertIs<SteamTokenCheck.Accepted>(PushedSteamToken.check(steamToken(exp = now + 1.seconds), now))
        assertIs<SteamTokenCheck.Refused>(PushedSteamToken.check(steamToken(exp = now), now))
    }

    @Test
    fun refresh_unscoped_expired_and_malformed_tokens_are_refused_without_echoing_the_token() {
        val refused = mapOf(
            "refresh" to steamToken(aud = listOf("web", "renew", "derive")),
            "derive only" to steamToken(aud = listOf("web", "derive")),
            "no web" to steamToken(aud = listOf("mobile")),
            "expired" to steamToken(exp = now - 1.hours),
            "no expiry" to steamToken(exp = null),
            "no steamid" to steamToken(sub = "1234"),
            "not a jwt" to "not-a-token",
        )
        for ((case, token) in refused) {
            val check = PushedSteamToken.check(token, now)
            assertIs<SteamTokenCheck.Refused>(check, case)
            for (segment in token.split('.')) {
                assertTrue(segment !in check.reason, "$case: a refusal never echoes the token: ${check.reason}")
            }
        }
    }
}
