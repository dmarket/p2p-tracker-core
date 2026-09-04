package com.dmarket.p2p.tracker.policy

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExponentialBackoffTest {

    /** Always draws the top of the `[0, until)` range, so the jitter equals the current ceiling. */
    private class MaxRandom : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextLong(until: Long): Long = until - 1
    }

    /** Always draws 0, so the jitter contributes nothing (only the floor can raise the result). */
    private class ZeroRandom : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextLong(until: Long): Long = 0
    }

    @Test
    fun doubles_each_attempt_until_the_cap() {
        val max = MaxRandom()
        // base 500, cap 8000: 500 → 1000 → 2000 → 4000 → 8000, then clamped at 8000.
        assertEquals(500, ExponentialBackoff.fullJitterMillis(1, 500, 8000, max))
        assertEquals(1000, ExponentialBackoff.fullJitterMillis(2, 500, 8000, max))
        assertEquals(2000, ExponentialBackoff.fullJitterMillis(3, 500, 8000, max))
        assertEquals(4000, ExponentialBackoff.fullJitterMillis(4, 500, 8000, max))
        assertEquals(8000, ExponentialBackoff.fullJitterMillis(5, 500, 8000, max))
        assertEquals(8000, ExponentialBackoff.fullJitterMillis(6, 500, 8000, max))
    }

    @Test
    fun full_jitter_floors_at_zero() {
        assertEquals(0, ExponentialBackoff.fullJitterMillis(3, 500, 8000, ZeroRandom()))
    }

    @Test
    fun retry_after_is_a_floor() {
        // A large Retry-After wins over the jittered delay regardless of the draw.
        assertEquals(30_000, ExponentialBackoff.fullJitterMillis(1, 500, 8000, ZeroRandom(), retryAfterMs = 30_000))
        assertEquals(30_000, ExponentialBackoff.fullJitterMillis(1, 500, 8000, MaxRandom(), retryAfterMs = 30_000))
        // A tiny Retry-After does not lower the jittered delay.
        assertEquals(500, ExponentialBackoff.fullJitterMillis(1, 500, 8000, MaxRandom(), retryAfterMs = 100))
    }

    @Test
    fun stays_within_zero_and_the_capped_ceiling_for_arbitrary_draws() {
        val random = Random(seed = 0)
        repeat(1_000) {
            val attempt = (it % 6) + 1
            val delay = ExponentialBackoff.fullJitterMillis(attempt, 500, 8000, random)
            val capped = minOf(500L shl (attempt - 1), 8000L)
            assertTrue(delay in 0..capped, "delay $delay out of [0, $capped] for attempt $attempt")
        }
    }

    // ---- equal jitter -------------------------------------------------------------------------------

    @Test
    fun equal_jitter_spans_exactly_the_upper_half_of_the_capped_window() {
        // The whole point of the strategy: the floor is half the ceiling and it comes from the arithmetic, so no
        // caller has to supply one — and both ends of the window are reachable.
        assertEquals(250, ExponentialBackoff.equalJitterMillis(1, 500, 8000, ZeroRandom()))
        assertEquals(500, ExponentialBackoff.equalJitterMillis(1, 500, 8000, MaxRandom()))
        // Rung 3 doubles twice: capped = 2000, so the window is [1000, 2000].
        assertEquals(1000, ExponentialBackoff.equalJitterMillis(3, 500, 8000, ZeroRandom()))
        assertEquals(2000, ExponentialBackoff.equalJitterMillis(3, 500, 8000, MaxRandom()))
    }

    @Test
    fun equal_jitter_respects_the_cap_once_the_exponential_saturates() {
        assertEquals(4000, ExponentialBackoff.equalJitterMillis(9, 500, 8000, ZeroRandom()))
        assertEquals(8000, ExponentialBackoff.equalJitterMillis(9, 500, 8000, MaxRandom()))
    }

    @Test
    fun equal_jitter_stays_inside_its_window_for_arbitrary_draws() {
        val random = Random(seed = 0)
        repeat(1_000) {
            val attempt = (it % 6) + 1
            val delay = ExponentialBackoff.equalJitterMillis(attempt, 500, 8000, random)
            val capped = minOf(500L shl (attempt - 1), 8000L)
            assertTrue(delay in (capped / 2)..capped, "delay $delay out of [${capped / 2}, $capped] for attempt $attempt")
        }
    }

    @Test
    fun equal_jitter_cannot_return_zero_even_at_the_bottom_of_the_draw() {
        // What removes the need for an anti-collapse floor. Full jitter can hand back 0; this cannot, for any
        // base above 1ms.
        repeat(20) { attempt ->
            assertTrue(ExponentialBackoff.equalJitterMillis(attempt + 1, 500, 8000, ZeroRandom()) > 0)
        }
    }
}
