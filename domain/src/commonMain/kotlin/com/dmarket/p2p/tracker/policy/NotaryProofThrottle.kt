package com.dmarket.p2p.tracker.policy

import com.dmarket.p2p.tracker.config.NotaryBreakerConfig
import kotlin.random.Random
import kotlin.time.Instant

/**
 * The prover's standing cooldown, as one immutable value: until when proving is parked, how deep the
 * escalation has gone, and how many consecutive failures have accumulated since the last arming.
 *
 * Held by the caller (persisted across worker respawns by the `:core` throttle store) and advanced only
 * through [NotaryProofThrottle], so no part of the decision hides in mutable state.
 */
data class NotaryThrottleState(val parkedUntil: Instant? = null, val attempt: Int = 0, val consecutiveFailures: Int = 0) {
    companion object {
        val EMPTY: NotaryThrottleState = NotaryThrottleState()
    }
}

/**
 * The back-pressure behind proof *generation*: how many consecutive failures park the prover, for how long,
 * and whether a due proof may be minted at all.
 *
 * **Why it exists.** A failed proof is not cheap. Each attempt garbles circuits for `maxSentData` plaintext
 * bytes and uploads them, measured at ~30 MB per attempt, and the loop plans a fresh intent for the same
 * transition on every wake for as long as the report stays withheld. So a prover that fails *reliably* — the
 * dev incident on 2026-08-26 wedged on 12 consecutive attempts — turns into a standing ~30 MB-per-wake drain
 * that also holds the cycle for the length of the host's proof timeout, with no proof to show for any of it.
 * Measured on that incident: ~230 MB to the notary in ten minutes, zero proofs. The per-cycle proving budget
 * in `TradeTrackerLoop` bounds the chain *within* one cycle; this bounds the retry *across* cycles.
 *
 * **Prover-wide, not per-transition.** The failure this exists for is a property of the prover — a wedged
 * wasm instance, an unreachable notary — so every deal's proof fails on identical grounds, and parking each
 * transition separately would spend one full attempt per tracked deal to establish what the first already
 * said. (A transition that fails *on its own*, e.g. a multi-item trade whose response exceeds
 * [com.dmarket.p2p.tracker.config.NotaryConfig.maxRecvData], is therefore NOT bounded here: it re-mints once
 * per cooldown window. That case wants its own per-intent ladder and does not have one yet.)
 *
 * No clock, no IO, no store — [Instant]s and the [Random] are the caller's, so every rule is table-testable.
 * The escalation arithmetic is [CooldownLadder], shared with [SteamWriteThrottle] — but drawn with **equal**
 * jitter rather than full jitter plus a floor: the notary has no minimum wait of its own to honour, so the
 * floor would have been pure anti-collapse, and a pure anti-collapse floor pins half the first-rung draws to
 * one value. See [CooldownLadder.drawEqualJitter].
 */
object NotaryProofThrottle {
    /**
     * Until when proving is parked at [now], or `null` if a proof may be minted.
     *
     * A deadline exactly at [now] has expired — the same boundary rule the write claims and the create
     * throttle use. Returning the deadline rather than a verdict type is deliberate: unlike
     * [WriteGate.Blocked], which has to say *whose* cooldown it is, there is only one thing parked here, so
     * the answer is an `Instant?` and a sealed hierarchy would encode nothing extra.
     */
    fun parkedUntil(state: NotaryThrottleState, now: Instant): Instant? = state.parkedUntil?.takeIf { now < it }

    /**
     * Folds one failed proof into the state. The streak advances, and crossing
     * [NotaryBreakerConfig.breakerThreshold] arms the cooldown and resets the streak — so the next
     * threshold's worth of failures is what escalates it a rung further.
     *
     * The threshold is why a single failure does not park anything: one lost socket or one transient notary
     * refusal is not evidence that the prover is broken, and parking on it would delay a transition that the
     * very next cycle could have proved.
     */
    fun onFailure(state: NotaryThrottleState, now: Instant, limits: NotaryBreakerConfig, random: Random): NotaryThrottleState {
        val streak = state.consecutiveFailures + 1
        if (streak < limits.breakerThreshold) return state.copy(consecutiveFailures = streak)
        val attempt = CooldownLadder.next(state.attempt)
        val cooldown = CooldownLadder.drawEqualJitter(attempt, limits.cooldownBaseMs, limits.cooldownMaxMs, random)
        return NotaryThrottleState(parkedUntil = now + cooldown, attempt = attempt, consecutiveFailures = 0)
    }

    /**
     * Folds a *generated* proof into the state — the prover is demonstrably working, so the streak and the
     * escalation both clear.
     *
     * Keyed on generation and delivery, deliberately **not** on the backend's `verified` verdict: a proof the
     * notary refuses still proves the prover produced one, and parking the surface over a verdict would stop
     * the client proving anything at all the moment the backend started disagreeing.
     *
     * [NotaryThrottleState.parkedUntil] is left standing for the same reason the create throttle leaves its
     * global block: a success while parked is only reachable through a caller that bypassed the gate, and
     * clearing it there would let one lucky proof cancel the backstop.
     */
    fun onSuccess(state: NotaryThrottleState): NotaryThrottleState = state.copy(attempt = 0, consecutiveFailures = 0)

    /**
     * How long to tell a caller to wait before the next attempt — the ≥1s floor is [CooldownLadder]'s, so it
     * is the same decision every surface reporting a deadline makes.
     */
    fun retryAfterSeconds(until: Instant, now: Instant): Int = CooldownLadder.retryAfterSeconds(until, now)
}
