package com.dmarket.p2p.tracker.engine

/**
 * The single highest-priority reason the tracker is blocked from acting, surfaced to the host as one
 * value so it can render exactly one prompt. Ordered by precedence in [BlockingState.resolve]; the
 * constants are declared in ascending precedence, and nothing reads their `ordinal`.
 */
enum class TrackerBlock {
    /** Nothing is blocking — the tracker is free to act. */
    NONE,

    /**
     * The DMarket backend is erroring: the `/heartbeat` reached the gateway but failed with a non-401
     * HTTP status (a deterministic 4xx like 404, or a persistent 5xx), so the cycle cannot complete.
     * Distinct from [DM_SESSION_MISSING] — the session token is fine; DMarket itself is unreachable — so
     * the host renders a "can't reach DMarket" prompt, not a "you were logged out" one. That distinction is
     * why the two names are `DM_CONNECTION_ERROR` / `DM_SESSION_MISSING`: this one is the connection, the
     * other one is the login. **Lowest
     * priority**: it is the only state here the user cannot act on, so every actionable sign-in prompt
     * outranks it. Note the shipped prod `/heartbeat` route answers 404 by design, which keeps this
     * state permanently set — anything ranked below it would never be displayed at all.
     */
    DM_CONNECTION_ERROR,

    /**
     * A wrong-account session: the DMarket-linked Steam id disagrees with the Steam id of the token the
     * client holds. See [AccountBinding]. Outranks [DM_CONNECTION_ERROR] — the verdict has a clear site that
     * does not need a working heartbeat (a credential for a different account releases it), so a failing
     * DMarket backend cannot pin it stale — and is outranked by the two sign-in states, either of which
     * stops the cycle before the binding can be re-derived at all.
     */
    STEAM_ACCOUNT_MISMATCH,

    /**
     * No authenticated Steam web session: the Steam session cookie is gone, so no Steam credential can
     * be acquired and the cycle stops at the credential gate. Outranks [STEAM_ACCOUNT_MISMATCH] (the
     * binding is derived from a credential this state means we do not have) and is outranked only by
     * [DM_SESSION_MISSING], which the loop establishes first — see [BlockingState.resolve].
     */
    STEAM_SESSION_MISSING,

    /**
     * No usable DMarket session: the marketplace bearer token is absent, or it is invalid and could
     * not be refreshed. **Highest priority**: it is the most upstream sign-in problem — nothing this
     * client does works without a DMarket session — and it is the first thing every cycle establishes
     * (`TradeTrackerLoop.runOnce` checks the marketplace credential before the Steam credential gate), so
     * ranking it top surfaces a fact that was just re-derived rather than a frozen one.
     */
    DM_SESSION_MISSING,
}

/**
 * Pure, zero-IO precedence over the tracker's blocking states:
 * `DM_SESSION_MISSING` > `STEAM_SESSION_MISSING` > `STEAM_ACCOUNT_MISMATCH` > `DM_CONNECTION_ERROR`.
 *
 * The chain is ordered by **how actionable the state is, most upstream first**: sign into DMarket, then
 * sign into Steam, then switch to the linked Steam account, and only then the one state the user cannot
 * do anything about (DMarket itself erroring). That is also the order in which a cycle establishes them
 * — `TradeTrackerLoop.runOnce` checks the marketplace credential, then the Steam credential, then the
 * account binding — which is what keeps the precedence honest: a higher-ranked state is always one that
 * was re-derived *this* cycle, never one frozen behind a block that short-circuited the cycle earlier.
 * The two exceptions are deliberate and safe: `STEAM_ACCOUNT_MISMATCH` is only ever *raised* after a
 * successful heartbeat, but it is *released* before one (a credential naming a different account clears
 * it), so a permanently-erroring backend cannot pin it; and `DM_CONNECTION_ERROR` is ranked last precisely
 * because the shipped prod route answers 404 by design, which would otherwise mask every prompt above.
 *
 * Kept here, next to [AccountBinding], so the whole decision surface stays in the audited, unit-tested
 * domain module.
 *
 * [steamSessionMissing] is defaulted so adding it did not break existing callers.
 */
object BlockingState {
    fun resolve(
        missingConnection: Boolean,
        serverError: Boolean,
        steamAccountMismatch: Boolean,
        steamSessionMissing: Boolean = false,
    ): TrackerBlock = when {
        // The parameter names describe the LOOP's flags (`marketplaceConnectionMissing`,
        // `marketplaceServerError`), which predate the state names and are deliberately left alone: they
        // are read by name in a dozen KDocs and tests. Reading order: a missing DMarket *session* is
        // `missingConnection`, an erroring DMarket *backend* is `serverError`.
        missingConnection -> TrackerBlock.DM_SESSION_MISSING
        steamSessionMissing -> TrackerBlock.STEAM_SESSION_MISSING
        steamAccountMismatch -> TrackerBlock.STEAM_ACCOUNT_MISMATCH
        serverError -> TrackerBlock.DM_CONNECTION_ERROR
        else -> TrackerBlock.NONE
    }
}
