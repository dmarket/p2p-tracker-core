package com.dmarket.p2p.tracker.policy

import kotlin.random.Random

/**
 * Full-jitter exponential backoff (the AWS "full jitter" strategy): the delay before retry [attempt]
 * is a uniform random pick in `[0, min(base·2^(attempt-1), cap)]`, floored by any server-supplied
 * `Retry-After`. Pure and clock-free — the caller supplies the `delay`; this only computes the amount.
 *
 * [attempt] is **1-based** (the first retry is `attempt = 1`). Callers keep the retry count small, so
 * the `shl` doubling stays well clear of `Long` overflow.
 */
object ExponentialBackoff {
    /** @return the backoff in milliseconds for retry [attempt] (1-based). */
    fun fullJitterMillis(attempt: Int, baseMs: Long, maxMs: Long, random: Random, retryAfterMs: Long = 0L): Long {
        val capped = cappedMillis(attempt, baseMs, maxMs)
        val jittered = random.nextLong(capped + 1)
        return maxOf(jittered, retryAfterMs)
    }

    /**
     * The AWS "equal jitter" strategy: a uniform random pick in `[capped/2, capped]` rather than in
     * `[0, capped]`.
     *
     * **Choose this over [fullJitterMillis] when the delay has no meaningful floor of its own.** Full jitter
     * needs a caller-supplied minimum to stop a draw near zero collapsing the wait entirely — and that
     * minimum then takes a *share of the probability mass with it*: with a floor at half the base, half of
     * every first-rung draw lands on exactly the floor, which is the lockstep the jitter was added to break.
     * Equal jitter gets the same guaranteed minimum from the arithmetic and spreads the rest.
     *
     * Keep [fullJitterMillis] where the floor means something on its own — a server's `Retry-After`, or a
     * deliberate "do not touch this surface again for N minutes" — because there the value is a requirement,
     * not an artifact of the distribution.
     *
     * @return the backoff in milliseconds for retry [attempt] (1-based).
     */
    fun equalJitterMillis(attempt: Int, baseMs: Long, maxMs: Long, random: Random): Long {
        val capped = cappedMillis(attempt, baseMs, maxMs)
        val half = capped / 2
        return half + random.nextLong(capped - half + 1)
    }

    /** The un-jittered ceiling for retry [attempt]: `min(base·2^(attempt-1), max)`. */
    private fun cappedMillis(attempt: Int, baseMs: Long, maxMs: Long): Long = minOf(baseMs shl (attempt - 1), maxMs)
}
