package com.dmarket.p2p.tracker.port

/**
 * The result of a session-refresh attempt.
 *
 * Steam-only since the marketplace axis moved to an API refresh whose outcome is a credential (or `null`)
 * rather than an enum — see `MarketplaceCredentialProvider`.
 */
enum class SessionRefreshOutcome {
    /** The session credential was (re)minted / written fresh. */
    REFRESHED,

    /** The session still had ample headroom; no refresh was performed. */
    NOT_NEEDED,

    /** No authenticated session was available to refresh from (durable session gone). */
    NOT_LOGGED_IN,

    /** A refresh was attempted but did not complete (transient/unexpected); swallow and retry later. */
    FAILED,
}
