package com.dmarket.p2p.tracker.model.steam

import com.dmarket.p2p.tracker.model.SteamId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A Steam Web API credential scraped from a logged-in steamcommunity.com session
 * (`data-loyalty_webapi_token`).
 *
 * Hard audit boundary: this value is stored **on the device only** and is never transmitted to the
 * DMarket backend. No port in this codebase accepts a [SteamCredential] as a marketplace request
 * argument — see `MarketplaceClient`.
 *
 * Unlike the reference, freshness is keyed off the JWT's own `exp` claim ([expiresAt]) rather than a
 * fixed 30-minute sliding window, so we refresh just before actual expiry instead of guessing.
 */
data class SteamCredential(val token: String, val subjectSteamId: SteamId, val expiresAt: Instant) {
    init {
        require(token.isNotBlank()) { "token must not be blank" }
    }

    /**
     * True if the credential is still safely usable at [now], leaving [skew] of headroom before the
     * JWT expires (default 60s, matching the refresh-just-before-expiry policy).
     */
    fun isFresh(now: Instant, skew: Duration = DEFAULT_SKEW): Boolean = now < expiresAt - skew

    /**
     * Redacts [token] so the raw Steam JWT never leaks into logs, crash reporters, or `toString`
     * dumps of containing types. The data-class default would print the full secret — one of the
     * few ways the device-only credential escapes the lib unintentionally.
     */
    override fun toString(): String = "SteamCredential(token=<redacted>, subjectSteamId=$subjectSteamId, expiresAt=$expiresAt)"

    companion object {
        val DEFAULT_SKEW: Duration = 60.seconds
    }
}
