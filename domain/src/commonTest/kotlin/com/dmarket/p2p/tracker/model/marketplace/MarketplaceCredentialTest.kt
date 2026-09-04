package com.dmarket.p2p.tracker.model.marketplace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class MarketplaceCredentialTest {
    private val expiry = Instant.parse("2026-06-16T12:00:00Z")
    private val credential = MarketplaceCredential("jwt", expiry)

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
    fun null_expiry_is_always_fresh() {
        // A session cookie with no explicit expiry → "can't tell" → treated as usable; the 401 path
        // is the backstop for a revoked-but-not-expired token.
        val sessionCookie = MarketplaceCredential("jwt", expiresAt = null)
        assertTrue(sessionCookie.isFresh(expiry + 10_000.seconds))
    }

    @Test
    fun custom_skew_shifts_the_boundary() {
        assertTrue(credential.isFresh(expiry - 10.seconds, skew = 5.seconds))
        assertFalse(credential.isFresh(expiry - 10.seconds, skew = 20.seconds))
    }

    @Test
    fun default_skew_is_sixty_seconds() {
        assertEquals(60.seconds, MarketplaceCredential.DEFAULT_SKEW)
    }

    @Test
    fun blank_token_is_rejected() {
        assertFailsWith<IllegalArgumentException> {
            MarketplaceCredential(" ", expiry)
        }
    }
}
