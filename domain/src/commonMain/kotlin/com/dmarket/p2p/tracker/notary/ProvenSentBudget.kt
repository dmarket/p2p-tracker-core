package com.dmarket.p2p.tracker.notary

import com.dmarket.p2p.tracker.config.NotaryConfig

/**
 * Size `NotaryConfig.maxSentData` to the request actually being proven, instead of paying the configured
 * ceiling on every proof.
 *
 * **Why this is worth a module.** `maxSentData` is the one notary knob that costs real bandwidth — the prover
 * garbles circuits for that many plaintext bytes whether the request uses them or not — and its value is a
 * *static over-estimate*: 1024, chosen so that a token 1.6× the observed one still fits, because "nothing here
 * is notified when it grows" (see the field's own KDoc for the 4096 → 2048 → 1024 measurement history). But the
 * token length is not actually unobservable at proof time: the IO edge is holding the token when it builds the
 * request. Every byte of that over-estimate is pre-processing uploaded to the notary and then discarded — a
 * measured ~10 MB per KB of budget, against a 41 MB session.
 *
 * **Only the framing is a measured constant; the rest is read off the spec.** The `maxSentData` KDoc records
 * the proven request as `196 + len(token)` on the history axis, whose substituted path is 93 bytes — so 103 of
 * that 196 is the request line's syntax plus the four headers `client_core::issue` injects, and the other 93
 * is *that one read's path*, which every spec already carries exactly. [HEADER_FRAMING_BYTES] keeps only the
 * part that cannot be derived. Folding the path into the constant instead was the first shape of this and was
 * wrong in a way worth recording: it made the number history-specific while the predicate below admits every
 * token-authed read, and `GetTradeOffers` (119 B of path) and `GetSteamNotifications` (121 B) are *longer* than
 * the axis it was measured on — an under-count that only the margin absorbed, on the one read where a
 * miscount fails every proof.
 *
 * Re-deriving the framing byte by byte was the other alternative and is still rejected: counting the request
 * line, `host`, `accept-encoding: identity` and `connection: close` lands at 91, i.e. it silently misses one of
 * the four injected headers, and being 12 bytes short fails every proof for that read. 103 is measurement
 * minus measurement; 91 is a guess that looks like arithmetic.
 *
 * **Narrow by construction.** [sentBudget] returns the configured value untouched for anything that is not a
 * token-authed read whose only credential is the JWT in its path — a request with headers, a body, or a
 * per-read `maxSentDataOverride` keeps today's behaviour byte for byte. That is every cookie-authed community
 * read and both writes.
 *
 * **It can only ever spend less.** The result is clamped at the configured value, so the operator's knob keeps
 * the exact meaning (and the exact safety) it has today and no config becomes more dangerous by upgrading. The
 * consequence worth stating: a token that grows past what the configured ceiling admits still fails every
 * proof, exactly as it does now, and the remedy is still the runbook on `NotaryConfig.maxSentData` — publish a
 * larger value. Sizing down cannot fix a ceiling that is too low; letting this raise the ceiling instead would
 * turn an absurd token into an unbounded upload, which is a worse failure than the one it would prevent.
 */
object ProvenSentBudget {

    /**
     * The proven request minus its path and its token: the request line's own syntax and the four headers
     * `client_core::issue` injects.
     *
     * Derived from measurement, not from counting bytes — `196` for the history axis (see the class doc) less
     * that axis's 93-byte substituted path. Measured against `api.steampowered.com`; a host with a longer name
     * costs the difference in its `Host` header, which the margin absorbs at the scale the catalog's hosts
     * differ by (`steamcommunity.com` is *shorter*, and its reads are excluded anyway).
     */
    private const val HEADER_FRAMING_BYTES: Int = 103

    /**
     * The Steam `access_token` length traced on a live dev proof, 2026-08-28.
     *
     * Public because it is the only figure that makes a budget quotable — `NotaryConfig.maxSentData` and
     * `sentBudgetMarginPercent` both reason in terms of it, and the debug console needs something to size
     * against when no credential is loaded. One home for it, so those cannot drift (they already had: one KDoc
     * said 521 against this 522).
     */
    const val OBSERVED_TOKEN_LENGTH: Int = 522

    /**
     * The send budget to hand the prover for [spec].
     *
     * Takes the whole [config] rather than the two numbers it reads, because resolving
     * `spec.maxSentDataOverride ?: config.maxSentData` is part of this decision, not part of the caller's: it
     * was written out at both call sites in the first shape of this, and one of those callers is the debug
     * console whose entire job is to report what the prover will really do. Every future platform prover gets
     * one call with no policy in it.
     *
     * @param steamAccessTokenLength length of the token the IO edge is about to substitute into the path — the
     *   only part of a token-authed request that the spec does not already state.
     */
    fun sentBudget(spec: ProvenReadSpec, config: NotaryConfig, steamAccessTokenLength: Int): Int {
        // The read's own override wins outright: it exists precisely to say the global sizing does not apply
        // (`ProvenReadCatalog` sets 8 KiB for the create POST), so it is both the answer and the ceiling.
        val configured = spec.maxSentDataOverride ?: config.maxSentData
        if (!spec.hasDerivableRequestSize()) return configured
        val required = HEADER_FRAMING_BYTES + spec.path.length - TOKEN_PLACEHOLDER.length + steamAccessTokenLength
        // `Long` throughout, like `OnlineBudgetLesson.learn`, and note the `100L`: `sentBudgetMarginPercent` is
        // host-supplied and only validated `>= 0`, so `100 + margin` in `Int` wraps NEGATIVE before the
        // widening can help — which `minOf` below would then happily pick as the budget, i.e. an unprovable
        // request from a config that merely looked over-generous. Rounded UP; the rounding direction is the
        // safety direction.
        val withMargin = (required.toLong() * (100L + config.sentBudgetMarginPercent) + 99) / 100
        return minOf(configured.toLong(), withMargin).toInt()
    }

    /**
     * Whether every byte of [spec]'s request is known from the spec plus the token length.
     *
     * A per-read `maxSentDataOverride` counts as "not derivable" even when the rest matches, for the reason
     * [sentBudget] gives. The other three clauses are what [HEADER_FRAMING_BYTES] assumes: no headers and no
     * body means the request is the line and the injected four, and a token slot means the one substitution
     * the caller can measure. `ProvenSentBudgetTest` enumerates the catalog kinds this admits, so widening the
     * set is a test change rather than a silent one.
     */
    private fun ProvenReadSpec.hasDerivableRequestSize(): Boolean =
        maxSentDataOverride == null && sendHeaders.isEmpty() && body == null && needsAccessToken
}
