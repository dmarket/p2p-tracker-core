package com.dmarket.p2p.tracker.credential.steam

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Steam session-refresh types must keep the re-mint secrets out of `toString()`.
 *
 * These are the highest-value redactions in the core: `SetTokenJob.form` carries `nonce`, `auth`,
 * `sessionid` **and** `prior` — the live `steamLoginSecure` access token — and every one of these types is
 * interpolated wherever a refresh is diagnosed. See `SecretToStringTest` in `:domain` for the same
 * discipline applied to the credential types.
 */
class SteamRefreshSecretToStringTest {

    // Deliberately distinctive: a substring assertion cannot pass by accident.
    private val secret = "SECRET-NONCE-a1b2c3d4e5f6"

    @Test
    fun set_token_job_redacts_the_form_but_keeps_the_field_names() {
        val job = DefaultSteamSessionRefresher.SetTokenJob(
            url = "https://steamcommunity.com/login/settoken",
            form = mapOf("steamID" to "76561198000000001", "nonce" to secret, "auth" to secret, "prior" to secret),
        )

        val printed = job.toString()

        assertFalse(secret in printed, printed)
        assertTrue("redacted" in printed, printed)
        // Which fields Steam echoed back is the actual diagnosis for a rejected transfer, so names stay.
        assertTrue("prior" in printed, "field names must survive: $printed")
        assertTrue("nonce" in printed, printed)
        assertTrue("settoken" in printed, "the endpoint stays: $printed")
    }

    @Test
    fun transfer_redacts_nonce_and_auth() {
        val printed = SteamRefreshResponse.Transfer(
            url = "https://store.steampowered.com/login/settoken",
            nonce = secret,
            auth = secret,
        ).toString()

        assertFalse(secret in printed, printed)
        assertTrue("nonce=<redacted>" in printed, printed)
        assertTrue("auth=<redacted>" in printed, printed)
        assertTrue("store.steampowered.com" in printed, printed)
    }

    @Test
    fun flat_redacts_nonce_auth_and_the_verbatim_field_map() {
        val printed = SteamRefreshResponse.Flat(
            steamId = "76561198000000001",
            nonce = secret,
            auth = secret,
            loginUrl = "https://steamcommunity.com/login/settoken",
            fields = mapOf("nonce" to secret, "auth" to secret, "success" to "true"),
        ).toString()

        assertFalse(secret in printed, printed)
        assertTrue("redacted" in printed, printed)
        assertTrue("76561198000000001" in printed, "the public steamid stays: $printed")
    }

    @Test
    fun transfers_is_covered_transitively_through_its_transfer_list() {
        val printed = SteamRefreshResponse.Transfers(
            steamId = "76561198000000001",
            transfers = listOf(
                SteamRefreshResponse.Transfer("https://steamcommunity.com/login/settoken", secret, secret),
            ),
        ).toString()

        assertFalse(secret in printed, printed)
        assertTrue("redacted" in printed, printed)
    }
}
