package com.dmarket.p2p.tracker.port.steam

/**
 * What the Steam web session cookie says about the session behind it, judged from the **embedded**
 * access token's expiry rather than the cookie's presence or its browser `expirationDate` (Steam
 * persists the cookie months out while rotating the short-lived token inside it, so both of those read
 * "fine" for a session that is long dead).
 *
 * This is what lets the keep-alive run on the *session's* own clock: see
 * [SteamSessionRefresher.sessionState].
 */
enum class SteamWebSessionState {
    /** The session has comfortably more life than the refresher's headroom — nothing to do. */
    ALIVE,

    /**
     * The session is still usable but inside the refresher's headroom (or its token is unreadable), so
     * this is the window in which it must be renewed. Renewal is retried on every cycle in this window:
     * each attempt is a real chance, and the window itself bounds the retries.
     */
    NEEDS_REFRESH,

    /**
     * The session is perfectly healthy — but it belongs to a **different Steam account** than the
     * credential the caller is asking about.
     *
     * Its own state rather than a flavour of [GONE] or [NEEDS_REFRESH] because neither of their remedies
     * applies: there is nothing to renew and nothing to mint (the session is fine), and telling the user to
     * sign in would be a lie. What is wrong is the *credential*, which must be discarded and re-acquired
     * from the session now in place. Only ever reported when the caller supplies an expected id — see
     * [SteamSessionRefresher.sessionState].
     */
    OTHER_ACCOUNT,

    /**
     * There is no usable session: the cookie is absent, or its embedded token has already expired.
     * **Nothing the client can do restores this** — Steam's renew endpoint refreshes a *live* session and
     * rejects a dead one (`InvalidParam`) even when the durable "remember me" cookie is still valid; only
     * Steam's own login flow mints a new session from that. So no request is worth spending here: the host
     * prompts for a sign-in, and a host that watches the session cookie is woken when one reappears.
     */
    GONE,
}
