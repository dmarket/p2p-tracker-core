package com.dmarket.p2p.tracker.runtime

import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * How long to stay quiet after a 429: the `Retry-After` or a backoff that doubles with each 429 in a row from [BASE] to
 * [MAX], whichever is longer, plus up to a quarter more at random, so clients behind one address do not all come back
 * at once. One instance per run of 429s to count; [answered] ends the run.
 */
internal class RateLimitBackoff(private val random: Random) {
    private var streak = 0

    /** A 429 came back; returns the wait it asks for. */
    fun refused(retryAfterSeconds: Long?): Duration {
        streak += 1
        val ladder = BASE * (1 shl (streak - 1).coerceAtMost(MAX_DOUBLINGS))
        val wait = maxOf(retryAfterSeconds?.coerceAtLeast(0)?.seconds ?: Duration.ZERO, ladder.coerceAtMost(MAX))
        return wait + wait * (random.nextDouble() * JITTER)
    }

    /** An answer that was not a 429: the next 429 starts from [BASE] again. */
    fun answered() {
        streak = 0
    }

    companion object {
        /** The first wait after a 429 with no longer `Retry-After`; it doubles with each 429 in a row. */
        val BASE: Duration = 30.seconds

        /** The longest wait a run of 429s grows to: as long as the gateway's own ban on an address. */
        val MAX: Duration = 10.minutes

        private const val MAX_DOUBLINGS = 5
        private const val JITTER = 0.25
    }
}
