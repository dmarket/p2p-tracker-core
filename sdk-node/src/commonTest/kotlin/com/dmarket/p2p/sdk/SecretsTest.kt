package com.dmarket.p2p.sdk

import com.dmarket.p2p.sdk.secrets.DmarketKey
import com.dmarket.p2p.sdk.secrets.KeyPush
import com.dmarket.p2p.sdk.secrets.PushedSteamToken
import com.dmarket.p2p.sdk.secrets.SdkSecrets
import com.dmarket.p2p.sdk.secrets.SteamTokenCheck
import com.dmarket.p2p.sdk.secrets.TokenPush
import com.dmarket.p2p.tracker.model.SteamId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class SecretsTest {

    // ---- the Trading API key -------------------------------------------------------------------

    @Test
    fun a_nacl_secret_and_a_bare_seed_both_parse_to_the_seed() {
        assertEquals(SECRET_SEED, DmarketKey.parse(PUBLIC_KEY, SECRET_SEED + PUBLIC_KEY)?.secretSeed)
        assertEquals(SECRET_SEED, DmarketKey.parse(PUBLIC_KEY.uppercase(), " $SECRET_SEED ")?.secretSeed)
    }

    @Test
    fun a_pair_from_two_keys_or_anything_not_hex_is_refused() {
        val otherPublic = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"
        assertNull(DmarketKey.parse(PUBLIC_KEY, SECRET_SEED + otherPublic), "the NaCl tail must be the public key")
        assertNull(DmarketKey.parse(PUBLIC_KEY, "zz" + SECRET_SEED.drop(2)))
        assertNull(DmarketKey.parse(PUBLIC_KEY.drop(2), SECRET_SEED))
        assertNull(DmarketKey.parse(PUBLIC_KEY, SECRET_SEED.drop(2)))
    }

    @Test
    fun a_key_never_prints_its_secret() {
        val printed = testKey().toString()
        assertTrue(PUBLIC_KEY in printed)
        assertTrue(SECRET_SEED !in printed, printed)
    }

    // ---- the Steam token -----------------------------------------------------------------------

    @Test
    fun mobile_app_and_steam_client_tokens_are_held() {
        for (aud in listOf(listOf("web", "mobile"), listOf("client", "web"), listOf("web"))) {
            val check = PushedSteamToken.check(steamToken(aud = aud), T0)
            assertIs<SteamTokenCheck.Accepted>(check, "aud=$aud")
            assertEquals(SteamId(STEAM_ID), check.credential.subjectSteamId)
            assertEquals(T0 + 24.hours, check.credential.expiresAt)
        }
    }

    @Test
    fun a_refresh_token_a_token_without_web_and_an_expired_one_are_refused() {
        val refused = mapOf(
            "refresh" to steamToken(aud = listOf("web", "renew", "derive")),
            "no web" to steamToken(aud = listOf("mobile")),
            "expired" to steamToken(exp = T0),
            "no steamid" to steamToken(sub = "1234"),
            "not a jwt" to "not-a-token",
        )
        for ((case, token) in refused) {
            val check = PushedSteamToken.check(token, T0)
            assertIs<SteamTokenCheck.Refused>(check, case)
            assertTrue("eyJ" !in check.reason, "a refusal never echoes the token: ${check.reason}")
        }
    }

    // ---- holding them --------------------------------------------------------------------------

    @Test
    fun a_key_push_says_whether_the_key_changed() = runTest {
        val secrets = SdkSecrets()
        assertEquals(KeyPush.FIRST, secrets.putDmarketKey(testKey()))
        assertEquals(KeyPush.SAME, secrets.putDmarketKey(testKey()))
        val other = assertNotNull(DmarketKey.parse("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c", SECRET_SEED))
        assertEquals(KeyPush.REPLACED, secrets.putDmarketKey(other))
    }

    @Test
    fun the_first_token_is_held_on_trust_before_any_account_is_named() = runTest {
        val secrets = SdkSecrets()
        val token = (PushedSteamToken.check(steamToken(), T0) as SteamTokenCheck.Accepted).credential
        assertIs<TokenPush.Held>(secrets.putSteamCredential(token, linkedSteamId = null))
    }

    @Test
    fun a_token_of_another_account_is_refused_and_never_evicts_the_held_one() = runTest {
        val secrets = SdkSecrets()
        val mine = (PushedSteamToken.check(steamToken(), T0) as SteamTokenCheck.Accepted).credential
        val theirs = (PushedSteamToken.check(steamToken(sub = OTHER_STEAM_ID), T0) as SteamTokenCheck.Accepted).credential
        secrets.putSteamCredential(mine, linkedSteamId = SteamId(STEAM_ID))

        // Named by DMarket…
        val named = secrets.putSteamCredential(theirs, linkedSteamId = SteamId(STEAM_ID))
        assertEquals(TokenPush.WrongAccount(SteamId(OTHER_STEAM_ID), SteamId(STEAM_ID)), named)
        // …or not yet: the held token's account stands until DMarket says otherwise.
        assertIs<TokenPush.WrongAccount>(secrets.putSteamCredential(theirs, linkedSteamId = null))
        assertEquals(mine, secrets.steamCredential)
    }

    @Test
    fun the_account_dmarket_names_wins_over_a_held_token_of_another() = runTest {
        val secrets = SdkSecrets()
        val wrong = (PushedSteamToken.check(steamToken(sub = OTHER_STEAM_ID), T0) as SteamTokenCheck.Accepted).credential
        val right = (PushedSteamToken.check(steamToken(), T0) as SteamTokenCheck.Accepted).credential
        secrets.putSteamCredential(wrong, linkedSteamId = null)

        assertIs<TokenPush.Held>(secrets.putSteamCredential(right, linkedSteamId = SteamId(STEAM_ID)))
        assertEquals(right, secrets.steamCredential)
    }
}
