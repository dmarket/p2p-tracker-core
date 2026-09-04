package com.dmarket.p2p.tracker.policy

import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * The escalating-cooldown arithmetic shared by every breaker in this package: one rung up, one jittered draw,
 * and how a standing deadline is reported to a caller.
 *
 * Extracted because [SteamWriteThrottle] and [NotaryProofThrottle] had arrived at byte-identical copies of all
 * three — including the `shl` overflow clamp and the "never below 1s" floor, each of which is a rule that was
 * reasoned about once and then had two homes with nothing forcing the second edit. The *policies* stay
 * separate: what parks whom, and on what evidence, is genuinely different per surface. Only the ladder is
 * common.
 *
 * Visible across `:domain` rather than package-private because [com.dmarket.p2p.tracker.engine.ProofMintPolicy]
 * reports the same deadline the throttles do, and the ≥1s floor has to stay one decision — the whole reason
 * this object exists. Not `@JsExport`: it is arithmetic the library applies, not a knob a host sets.
 */
object CooldownLadder {
    /**
     * Ceiling on the exponent handed to [ExponentialBackoff.fullJitterMillis].
     *
     * Unlike a retry count, a breaker's attempt counter climbs for as long as the surface keeps failing, and
     * that function doubles `baseMs` by `shl (attempt - 1)` — which overflows `Long` for a large enough
     * attempt. Clamping the *exponent* costs nothing observable: the configured maximum caps the result long
     * before this rung, so every attempt past it already produced the same capped draw.
     */
    const val MAX_ATTEMPT: Int = 20

    /** One rung up, held at [MAX_ATTEMPT] so a surface that never recovers cannot climb forever. */
    fun next(attempt: Int): Int = (attempt + 1).coerceAtMost(MAX_ATTEMPT)

    /**
     * The cooldown for a 1-based [attempt] when the surface has a **meaningful minimum wait**: full jitter in
     * `[0, capped]`, floored at [minMs].
     *
     * Jittered rather than a fixed ladder so repeated failures spread out instead of retrying in lockstep —
     * several devices hitting one rate-limited surface would otherwise re-converge on it together. The floor is
     * doing two jobs here, which is why it is explicit: it stops a near-zero draw collapsing the wait, *and* it
     * carries a requirement of its own ("do not touch this surface again for N minutes"). Where only the first
     * job applies, use [drawEqualJitter] — a floor that is purely anti-collapse takes a share of the
     * probability mass with it, and that share is exactly the lockstep the jitter was added to break.
     */
    fun draw(attempt: Int, baseMs: Int, maxMs: Int, minMs: Int, random: Random): Duration = ExponentialBackoff.fullJitterMillis(
        attempt = attempt.coerceAtMost(MAX_ATTEMPT),
        baseMs = baseMs.toLong(),
        maxMs = maxMs.toLong(),
        random = random,
        retryAfterMs = minMs.toLong(),
    ).milliseconds

    /**
     * The cooldown for a 1-based [attempt] when the only floor needed is "not zero": equal jitter in
     * `[capped/2, capped]`.
     *
     * Same guaranteed minimum as [draw] with `minMs = baseMs / 2` would give, without pinning half of every
     * first-rung draw to that one value — so the spread stays a spread, and there is one knob fewer to keep
     * consistent with the base.
     */
    fun drawEqualJitter(attempt: Int, baseMs: Int, maxMs: Int, random: Random): Duration = ExponentialBackoff.equalJitterMillis(
        attempt = attempt.coerceAtMost(MAX_ATTEMPT),
        baseMs = baseMs.toLong(),
        maxMs = maxMs.toLong(),
        random = random,
    ).milliseconds

    /**
     * How long a caller should be told to wait before retrying something parked until [until]: whole seconds,
     * never below 1, because a "retry after 0s" reads as "retry now" — which is the one thing a cooldown is
     * there to prevent.
     */
    fun retryAfterSeconds(until: Instant, now: Instant): Int = (until - now).inWholeSeconds.toInt().coerceAtLeast(1)
}
