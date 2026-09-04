package com.dmarket.p2p.tracker.model.steam

import com.dmarket.p2p.tracker.model.SteamId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class SteamCredentialTest {
    private val expiry = Instant.parse("2026-06-16T12:00:00Z")
    private val credential = SteamCredential("jwt", SteamId("76561198000000001"), expiry)

    @Test
    fun fresh_with_more_than_skew_headroom() {
        assertTrue(credential.isFresh(expiry - 61.seconds))
    }

    @Test
    fun not_fresh_inside_the_skew_window() {
        assertFalse(credential.isFresh(expiry - 59.seconds))
    }

    @Test
    fun not_fresh_after_expiry() {
        assertFalse(credential.isFresh(expiry + 1.seconds))
    }

    @Test
    fun freshness_is_keyed_off_exp_not_a_fixed_window() {
        // A custom skew shifts the boundary, proving freshness tracks the JWT exp claim.
        assertTrue(credential.isFresh(expiry - 10.seconds, skew = 5.seconds))
        assertFalse(credential.isFresh(expiry - 10.seconds, skew = 20.seconds))
    }

    @Test
    fun default_skew_is_sixty_seconds() {
        assertEquals(60.seconds, SteamCredential.DEFAULT_SKEW)
    }

    @Test
    fun blank_token_is_rejected() {
        assertFailsWith<IllegalArgumentException> {
            SteamCredential(" ", SteamId("76561198000000001"), expiry)
        }
    }

    @Test
    fun toString_redacts_the_token() {
        val secret = "ey.super-secret-steam-jwt.signature"
        val rendered = SteamCredential(secret, SteamId("76561198000000001"), expiry).toString()
        assertFalse(rendered.contains(secret), "raw token must never appear in toString(): $rendered")
        // Non-secret fields stay visible for debuggability.
        assertTrue(rendered.contains("76561198000000001"))
        assertTrue(rendered.contains("2026-06-16T12:00:00Z"))
    }
}
