package com.dmarket.p2p.tracker.client.marketplace

import com.dmarket.p2p.tracker.credential.steam.HeaderSteamProofCookieSource
import com.dmarket.p2p.tracker.credential.steam.PushedSteamSession
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.marketplace.TradingApiKey
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.port.marketplace.Ed25519Signer
import com.dmarket.p2p.tracker.support.FakeClock
import com.dmarket.p2p.tracker.support.steamAccessToken
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class NotaryUpgradeSigningTest {
    private val clock = FakeClock()

    /** Signs nothing: returns the message bytes, so the header shows what was signed. */
    private val echoSigner = Ed25519Signer { message -> message }
    private var key: TradingApiKey? = TradingApiKey.parse(PUBLIC_KEY, SECRET_SEED)
    private val signer = TradingApiNotaryUpgradeSigner(
        TradingApiAuthenticator({ key }, clock) { echoSigner },
        notaryUrl = "wss://api-dev2.devss.xyz/provenance/v1/",
    )

    private fun signed(header: String): String = header.removePrefix("dmar ed25519 ").hexToByteArray().decodeToString()

    @Test
    fun each_upgrade_signs_get_of_the_notary_path_with_an_empty_body_afresh() = runTest {
        val first = signer.sign().values
        clock.advance(3.seconds)
        val second = signer.sign().values

        assertEquals(PUBLIC_KEY, first[TradingApiSignature.API_KEY])
        val date = first.getValue(TradingApiSignature.SIGN_DATE)
        assertEquals("GET/provenance/v1/$date", signed(first.getValue(TradingApiSignature.REQUEST_SIGN)))
        assertNotEquals(first[TradingApiSignature.SIGN_DATE], second[TradingApiSignature.SIGN_DATE], "never a reused signature")
        for (headers in listOf(first, second)) {
            assertFalse(headers.values.any { SECRET_SEED in it }, "the secret is in no header")
        }
    }

    @Test
    fun with_no_key_held_nothing_is_signed() = runTest {
        key = null
        assertFailsWith<IllegalStateException> { signer.sign() }
    }

    @Test
    fun the_signed_path_is_the_one_the_client_sends() {
        assertEquals("/provenance/v1/", notaryUpgradePath("wss://api-dev2.devss.xyz/provenance/v1/"))
        assertEquals("/provenance/v1", notaryUpgradePath("wss://api.dmarket.com/provenance/v1"))
        assertEquals("/", notaryUpgradePath("ws://127.0.0.1:7047"))
        assertEquals("/?x=1", notaryUpgradePath("ws://127.0.0.1:7047?x=1"))
        assertEquals("/n/?a=b", notaryUpgradePath("wss://host/n/?a=b#frag"))
    }

    @Test
    fun a_log_line_keeps_no_signed_header_value() {
        val sign = "dmar ed25519 " + "ab".repeat(64)
        val lines = listOf(
            "X-Api-Key: $PUBLIC_KEY\r\nX-Sign-Date: 1781611200\r\nX-Request-Sign: $sign",
            """{"X-Api-Key":"$PUBLIC_KEY","x-sign-date":"1781611200","X-Request-Sign":"$sign"}""",
            "{X-Api-Key=$PUBLIC_KEY, X-Sign-Date=1781611200, X-Request-Sign=$sign}",
            "upgrade signed with $sign",
        )
        for (line in lines) {
            val redacted = TradingApiSignature.redact(line)
            assertFalse(PUBLIC_KEY in redacted || "1781611200" in redacted || "ab".repeat(64) in redacted, redacted)
            assertTrue("<redacted>" in redacted, redacted)
        }
        assertEquals("nothing to hide here", TradingApiSignature.redact("nothing to hide here"))
    }

    @Test
    fun a_proof_cookie_is_built_from_the_token_and_the_session_id() = runTest {
        val credential = SteamCredential("tok.en.sig", SteamId(STEAM_ID), clock.now() + 1.hours)
        val session = HeaderSteamProofCookieSource({ credential }, "abc123").currentSession()
        assertEquals("steamLoginSecure=$STEAM_ID%7C%7Ctok.en.sig; sessionid=abc123", session?.cookieHeader)
        assertEquals("abc123", session?.sessionId)
        assertNull(HeaderSteamProofCookieSource({ null }, "abc123").currentSession())
    }

    @Test
    fun the_pushed_session_lends_its_token_to_a_proof_while_it_lives() = runTest {
        val steam = PushedSteamSession(clock) { null }
        val cookies = steam.proofCookies("abc123")
        assertNull(cookies.currentSession(), "nothing held yet")
        steam.push(steamAccessToken(STEAM_ID, clock.now() + 1.hours))
        assertTrue(cookies.currentSession()?.cookieHeader.orEmpty().startsWith("steamLoginSecure=$STEAM_ID%7C%7C"))
        clock.advance(2.hours)
        assertNull(cookies.currentSession(), "an expired token lends nothing")
    }

    private companion object {
        const val STEAM_ID = "76561198000000001"
        const val PUBLIC_KEY = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
        const val SECRET_SEED = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"
    }
}
