package com.dmarket.p2p.tracker.client.steam

/**
 * Thrown by [KtorSteamProfileReader] when Steam keeps returning HTTP 429 after the configured retry
 * budget is exhausted. The Steam-side twin of the marketplace `RateLimitedException`, kept in the
 * steam package so the Steam and marketplace surfaces never share a type (same reasoning as the two
 * separate HTTP client factories).
 *
 * @property retryAfterSeconds the last `Retry-After` header value Steam sent, if any.
 */
class SteamRateLimitedException(val retryAfterSeconds: Long?) :
    RuntimeException("Steam rate-limited the profile request (429)${retryAfterSeconds?.let { "; retry after ${it}s" } ?: ""}")
