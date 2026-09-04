package com.dmarket.p2p.tracker.policy

import com.dmarket.p2p.tracker.config.SteamWriteConfig
import com.dmarket.p2p.tracker.model.SteamId
import kotlin.random.Random
import kotlin.time.Instant

/** How a failed `create_offer` should be read by the throttle — *what to park*, not why it failed. */
enum class SteamWriteFailureKind {
    /**
     * Steam refused because *this one counterparty* is over their outstanding-offer cap. The only kind that
     * parks a single partner: it is a statement about *that* partner's quota, and every further create for
     * them is refused until one of their offers clears.
     */
    RATE_LIMITED,

    /**
     * Steam refused for a quota reason that is **not** about one counterparty — the account-wide
     * outgoing-offer cap, or an outright `429` on the request rate.
     *
     * Parking a single partner would be the wrong answer: the next partner's create is refused on exactly
     * the same grounds, so this arms the surface-wide block **immediately** instead of spending
     * [SteamWriteConfig.globalBreakerThreshold] more doomed creates proving what this one already said.
     */
    RATE_LIMITED_SURFACE,

    /**
     * The request never reached Steam, or its answer never came back. Says nothing about a partner's
     * quota, so it parks no one on its own — but it counts towards the global breaker, because refusing
     * connections outright is how Steam's edge answers a client that pushed too hard.
     */
    TRANSPORT,

    /**
     * Anything else — a malformed body, a rejected session, a Steam-side error for this one offer. It must
     * not park the write surface: the next deal's create may be perfectly valid. (It still stops its own
     * partner chain for the cycle; that is the caller's rule, not the throttle's.)
     */
    OTHER,
}

/**
 * **Why** a `create_offer` failed, in the client's own closed vocabulary — the diagnosis a host renders,
 * as against [SteamWriteFailureKind], which is only the throttle's decision about what to park. Each cause
 * carries the [kind] it implies, so the two can never disagree.
 *
 * Steam states every one of these as one English sentence inside the refusal body, so they are recovered by
 * matching the host-suppliable markers in [SteamWriteConfig]. That reading happens **once**, here, and
 * travels as an enum: a host left to re-parse the text would be re-deriving this from a free-form string
 * that may name urls, ids or the counterparty's persona — and every host would do it differently.
 */
enum class SteamCreateFailureCause(val kind: SteamWriteFailureKind) {
    /**
     * Steam's per-counterparty outstanding-offer cap (5 at the time of writing). The user's remedy is real
     * and specific: cancel one of the offers already open with *that* partner, or wait for one to clear.
     */
    COUNTERPARTY_OFFER_LIMIT(SteamWriteFailureKind.RATE_LIMITED),

    /**
     * The account-wide outgoing-offer cap: the same refusal wording with no counterparty named. Cancelling
     * offers still helps, but any of them will do — and no other partner's create will go through either.
     */
    OUTGOING_OFFER_LIMIT(SteamWriteFailureKind.RATE_LIMITED_SURFACE),

    /**
     * Steam throttled the *request rate* (`429`). Deliberately not one of the two offer limits: nothing
     * here says any quota of open offers was reached, so telling the user to cancel offers would be wrong
     * advice. The only honest remedy is to wait, which is what the surface-wide cooldown does.
     */
    REQUEST_RATE_LIMITED(SteamWriteFailureKind.RATE_LIMITED_SURFACE),

    /** The request never reached Steam, or its answer never came back. */
    TRANSPORT(SteamWriteFailureKind.TRANSPORT),

    /**
     * Unrecognised. Reported as unrecognised rather than as the nearest-looking limit: a wrong cause on
     * screen ("cancel some offers") is worse for the user than an honest "it failed", and it would park the
     * write surface over something that is not a quota at all.
     */
    OTHER(SteamWriteFailureKind.OTHER),
}

/** Which writes a [WriteGate.Blocked] verdict covers. */
enum class ThrottleScope {
    /** Only this partner's creates are parked; every other partner is free. */
    PARTNER,

    /** The whole create surface is parked, whatever the partner. */
    GLOBAL,
}

/** A single partner's standing cooldown, and how many refusals deep the escalation is. */
data class PartnerCooldown(val until: Instant, val attempt: Int)

/**
 * The create-surface throttle, as one immutable value: who is parked, until when, and how deep the
 * escalation has gone. Held by the caller (persisted across worker respawns in `:core`) and advanced
 * only through [SteamWriteThrottle], so there is no hidden state anywhere in the decision.
 */
data class SteamWriteThrottleState(
    val partners: Map<SteamId, PartnerCooldown> = emptyMap(),
    val globalUntil: Instant? = null,
    val globalAttempt: Int = 0,
    val consecutiveFailures: Int = 0,
) {
    companion object {
        val EMPTY: SteamWriteThrottleState = SteamWriteThrottleState()
    }
}

/** Whether a prospective `create_offer` may reach Steam right now. */
sealed interface WriteGate {
    /** Nothing is parking this write — send it. */
    data object Allow : WriteGate

    /** The write is parked until [until]; [scope] says whether that covers one partner or the whole surface. */
    data class Blocked(val scope: ThrottleScope, val until: Instant) : WriteGate
}

/**
 * The pure back-pressure behind Steam's `create_offer` surface: how a refusal is classified, how long it
 * parks whom, and whether a prospective create may go out.
 *
 * No clock, no IO, no store — [Instant]s and the [Random] are supplied by the caller, so every rule here
 * is table-testable. The IO half (persistence across an MV3 worker respawn) lives in the `:core` throttle
 * store; this object owns *only* the decision.
 *
 * Escalation is built on [ExponentialBackoff.fullJitterMillis] rather than a fixed ladder, so repeated
 * refusals for the same partner spread out instead of retrying in lockstep — several devices trading with
 * the same partner would otherwise re-hit Steam's cap together. The configured minimum is passed as that
 * function's `retryAfterMs` **floor**, so a full-jitter draw can never collapse a cooldown to ~0 ms.
 *
 * **Create only.** The cancel surface is never throttled: a cancel *frees* the partner's outstanding-offer
 * quota, so it is the way out of a rate-limit block, not a contributor to it.
 */
object SteamWriteThrottle {
    /**
     * What the throttle should do about a failed create — [classifyCause] read down to the action it
     * implies. Kept as its own entry point because that is all the throttle itself ever needs.
     */
    fun classify(error: String?, limits: SteamWriteConfig): SteamWriteFailureKind = classifyCause(error, limits).kind

    /**
     * Reads a failed create's error text ([error], as carried by
     * `com.dmarket.p2p.tracker.port.steam.CreateOfferResult.Failed`) against the host-suppliable markers in
     * [limits]. A `null`/blank error is [SteamCreateFailureCause.OTHER] — unknown is not a reason to park
     * the surface, nor to put a cause on the user's screen.
     *
     * Order is load-bearing:
     * 1. [SteamWriteConfig.counterpartyLimitMarker] first, because Steam's per-counterparty refusal *also*
     *    contains the generic "too many trade offers" wording — matching that first would read every
     *    per-partner cap as the account-wide one and park the whole surface.
     * 2. Rate-limit markers before transport ones, so a refusal that happens to mention a network word is
     *    still read as the quota statement it is.
     */
    fun classifyCause(error: String?, limits: SteamWriteConfig): SteamCreateFailureCause {
        val text = error?.lowercase()?.takeIf { it.isNotBlank() } ?: return SteamCreateFailureCause.OTHER
        return when {
            limits.counterpartyLimitMarker.matches(text) -> SteamCreateFailureCause.COUNTERPARTY_OFFER_LIMIT
            limits.rateLimitMarkers.matchesAny(text) ->
                // Which *kind* of rate limit, among the markers that all park the surface the same way.
                if (limits.requestRateLimitMarkers.matchesAny(text)) {
                    SteamCreateFailureCause.REQUEST_RATE_LIMITED
                } else {
                    SteamCreateFailureCause.OUTGOING_OFFER_LIMIT
                }
            limits.transportFailureMarkers.matchesAny(text) -> SteamCreateFailureCause.TRANSPORT
            else -> SteamCreateFailureCause.OTHER
        }
    }

    /**
     * Marker matching, in the one form both sides of this file agree on: case-insensitive substring, with
     * blank markers skipped. A blank one would otherwise match *every* text (the empty string is contained
     * in all of them), so a host that cleared a marker to disable it would instead pin every failure to
     * that marker's cause.
     */
    private fun String.matches(lowercasedText: String): Boolean = isNotBlank() && lowercase() in lowercasedText

    private fun List<String>.matchesAny(lowercasedText: String): Boolean = any { it.matches(lowercasedText) }

    /**
     * Whether a create for [partner] may reach Steam at [now]. The global block is checked first so its
     * (longer, surface-wide) deadline is what a caller reports. A deadline exactly at [now] has expired —
     * the same boundary rule the write-claim TTL uses.
     */
    fun gate(state: SteamWriteThrottleState, partner: SteamId, now: Instant): WriteGate {
        state.globalUntil?.let { if (now < it) return WriteGate.Blocked(ThrottleScope.GLOBAL, it) }
        state.partners[partner]?.let { if (now < it.until) return WriteGate.Blocked(ThrottleScope.PARTNER, it.until) }
        return WriteGate.Allow
    }

    /**
     * Folds one failed create into the state.
     *
     * A [SteamWriteFailureKind.RATE_LIMITED] failure escalates *that partner's* cooldown — it is the only
     * kind that names a partner. Every non-[SteamWriteFailureKind.OTHER] kind advances the
     * consecutive-failure streak, and crossing [SteamWriteConfig.globalBreakerThreshold] arms the
     * surface-wide block (and resets the streak, so the next threshold's worth of failures is what
     * escalates it again). [SteamWriteFailureKind.RATE_LIMITED_SURFACE] arms it on the spot, without
     * waiting for the streak. [SteamWriteFailureKind.OTHER] returns the state untouched.
     */
    fun onFailure(
        state: SteamWriteThrottleState,
        partner: SteamId,
        kind: SteamWriteFailureKind,
        now: Instant,
        limits: SteamWriteConfig,
        random: Random,
    ): SteamWriteThrottleState {
        if (kind == SteamWriteFailureKind.OTHER) return state
        val partners = if (kind == SteamWriteFailureKind.RATE_LIMITED) {
            val attempt = nextAttempt(state.partners[partner]?.attempt ?: 0)
            state.partners + (partner to PartnerCooldown(now + cooldown(attempt, limits, random), attempt))
        } else {
            state.partners
        }
        val streak = state.consecutiveFailures + 1
        // A refusal that was never about one partner is its own evidence that the surface is closed: holding
        // it to the streak would spend `globalBreakerThreshold - 1` more creates — every one of them refused
        // on identical grounds — to establish what this one already stated. That is precisely the hammering
        // the breaker exists to stop.
        if (kind != SteamWriteFailureKind.RATE_LIMITED_SURFACE && streak < limits.globalBreakerThreshold) {
            return state.copy(partners = partners, consecutiveFailures = streak)
        }
        val globalAttempt = nextAttempt(state.globalAttempt)
        return state.copy(
            partners = partners,
            globalUntil = now + cooldown(globalAttempt, limits, random),
            globalAttempt = globalAttempt,
            consecutiveFailures = 0,
        )
    }

    /**
     * Folds a successful create into the state: [partner] is demonstrably not over quota, and the surface
     * is demonstrably reachable, so their cooldown, the streak and the global escalation all clear.
     * [SteamWriteThrottleState.globalUntil] is left standing — a success while the surface is parked is
     * only reachable through a caller that bypassed the gate, and clearing it there would let one lucky
     * write cancel the backstop.
     */
    fun onSuccess(state: SteamWriteThrottleState, partner: SteamId): SteamWriteThrottleState {
        val partners = if (partner in state.partners) state.partners - partner else state.partners
        val cleared = state.copy(partners = partners, globalAttempt = 0, consecutiveFailures = 0)
        return if (cleared == state) state else cleared
    }

    /**
     * Drops entries whose deadline has passed, so the partner map stays bounded to partners actually being
     * held back rather than growing once per counterparty the device ever traded with.
     *
     * The escalation counters deliberately survive: [SteamWriteThrottleState.globalAttempt] and a partner's
     * `attempt` are only reset by a *success* ([onSuccess]). Pruning them would restart every escalation
     * from the first rung the moment its own cooldown elapsed, which is exactly when the next refusal is
     * most likely.
     */
    fun prune(state: SteamWriteThrottleState, now: Instant): SteamWriteThrottleState {
        val expiredGlobal = state.globalUntil?.let { now >= it } == true
        val partners = state.partners.filterValues { now < it.until }
        if (!expiredGlobal && partners.size == state.partners.size) return state
        return state.copy(
            partners = partners,
            globalUntil = state.globalUntil?.takeIf { !expiredGlobal },
        )
    }

    /**
     * How long a caller should be told to wait before retrying a write parked until [until] — the ≥1s floor
     * lives in [CooldownLadder.retryAfterSeconds], so it is one decision applied by every surface that reports
     * a deadline (the planner's deferred creates and the loop's host fast path alike).
     */
    fun retryAfterSeconds(until: Instant, now: Instant): Int = CooldownLadder.retryAfterSeconds(until, now)

    /** One rung up, held at the ladder's ceiling so a partner that never recovers cannot climb forever. */
    private fun nextAttempt(current: Int): Int = CooldownLadder.next(current)

    /** The cooldown for a 1-based [attempt]: jittered exponential, capped, floored by the configured minimum. */
    private fun cooldown(attempt: Int, limits: SteamWriteConfig, random: Random) = CooldownLadder.draw(
        attempt = attempt,
        baseMs = limits.cooldownBaseMs,
        maxMs = limits.cooldownMaxMs,
        minMs = limits.cooldownMinMs,
        random = random,
    )
}
