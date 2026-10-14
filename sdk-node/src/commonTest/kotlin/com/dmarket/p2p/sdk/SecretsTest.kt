package com.dmarket.p2p.sdk

import com.dmarket.p2p.sdk.secrets.KeyPush
import com.dmarket.p2p.sdk.secrets.SdkSecrets
import com.dmarket.p2p.sdk.secrets.TokenPush
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.marketplace.TradingApiKey
import com.dmarket.p2p.tracker.model.steam.PushedSteamToken
import com.dmarket.p2p.tracker.model.steam.SteamTokenCheck
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class SecretsTest {

    // ---- holding them --------------------------------------------------------------------------

    @Test
    fun a_key_push_says_whether_the_key_changed() = runTest {
        val secrets = SdkSecrets()
        assertEquals(KeyPush.FIRST, secrets.putDmarketKey(testKey()))
        assertEquals(KeyPush.SAME, secrets.putDmarketKey(testKey()))
        val other = assertNotNull(TradingApiKey.parse("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c", SECRET_SEED))
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
