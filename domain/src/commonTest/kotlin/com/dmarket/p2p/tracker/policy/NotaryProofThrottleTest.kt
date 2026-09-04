package com.dmarket.p2p.tracker.policy

import com.dmarket.p2p.tracker.config.NotaryBreakerConfig
import com.dmarket.p2p.tracker.support.T0
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class NotaryProofThrottleTest {
    /**
     * Threshold pinned at 2 rather than inherited: every test below is about the ladder mechanics, and the
     * shipped default is a tuning decision that must not break them. The default itself is asserted in
     * [the_threshold_decides_how_many_failures_park_the_prover].
     */
    private val limits = NotaryBreakerConfig(breakerThreshold = 2)

    /** Seeded so the jittered draws are reproducible; the shipped limits are otherwise untouched. */
    private fun fail(state: NotaryThrottleState, at: Instant = T0, limitsFor: NotaryBreakerConfig = this.limits, seed: Int = 7) =
        NotaryProofThrottle.onFailure(state, at, limitsFor, Random(seed))

    @Test
    fun a_fresh_prover_is_allowed() {
        assertNull(NotaryProofThrottle.parkedUntil(NotaryThrottleState.EMPTY, T0))
    }

    @Test
    fun the_threshold_decides_how_many_failures_park_the_prover() {
        // One property, four thresholds, including the shipped default so a change to it lands here. `1` is the
        // no-forgiveness knob a host reaches for when every attempt is costing ~30MB; `3` (the default) sits
        // above the run length of the transient failure measured on dev, where an immediate retry succeeded; a
        // threshold above the tracked-deal count effectively disables the breaker.
        val shippedDefault = NotaryBreakerConfig().breakerThreshold
        val cases = listOf(1 to 1, 2 to 2, shippedDefault to shippedDefault, 100 to 100)
        for ((threshold, failuresToPark) in cases) {
            val config = NotaryBreakerConfig(breakerThreshold = threshold)
            var state = NotaryThrottleState.EMPTY
            repeat(failuresToPark - 1) {
                state = fail(state, limitsFor = config)
                assertNull(
                    NotaryProofThrottle.parkedUntil(state, T0),
                    "threshold $threshold must not park before $failuresToPark failures",
                )
            }
            state = fail(state, limitsFor = config)
            assertNotNull(NotaryProofThrottle.parkedUntil(state, T0), "threshold $threshold must park on failure $failuresToPark")
            assertEquals(1, state.attempt, "the first arming is rung one")
            assertEquals(0, state.consecutiveFailures, "the streak resets, so the next threshold escalates")
        }
    }

    @Test
    fun the_cooldown_stays_between_half_the_ceiling_and_the_ceiling_once_capped() {
        // Equal jitter draws from [capped/2, capped], so the floor is derived rather than configured: no draw
        // can collapse the park to ~0ms, and none can exceed the ceiling. 40 armings is far past where the
        // exponential saturates, so `capped` here is exactly cooldownMaxMs. Swept over seeds rather than
        // trusting the one this suite fixes.
        val ceiling = limits.cooldownMaxMs.milliseconds
        repeat(50) { seed ->
            var state = NotaryThrottleState.EMPTY
            repeat(40) { state = NotaryProofThrottle.onFailure(state, T0, limits, Random(seed)) }
            val waited = assertNotNull(state.parkedUntil) - T0
            assertTrue(waited >= ceiling / 2, "seed $seed parked for $waited, under half the ceiling")
            assertTrue(waited <= ceiling, "seed $seed parked for $waited, past the ceiling")
        }
    }

    @Test
    fun the_first_rung_spreads_across_the_upper_half_of_the_base() {
        // The point of moving off full-jitter-plus-floor. With a floor at base/2, half of every first-rung draw
        // landed on exactly that value — the lockstep the jitter exists to break. Equal jitter must produce a
        // real spread inside [base/2, base] instead, so several devices proving against one notary do not
        // re-converge on it.
        val base = limits.cooldownBaseMs.milliseconds
        val draws = (0 until 200).map { seed ->
            assertNotNull(fail(fail(NotaryThrottleState.EMPTY, limitsFor = limits), limitsFor = limits, seed = seed).parkedUntil) - T0
        }
        assertTrue(draws.all { it >= base / 2 && it <= base }, "every first-rung draw must sit inside [base/2, base]")
        assertTrue(draws.distinct().size > 100, "expected a spread, got ${draws.distinct().size} distinct values in 200 draws")
        val onTheFloor = draws.count { it == base / 2 }
        assertTrue(onTheFloor <= 5, "$onTheFloor of 200 draws landed exactly on the floor — that is the old lockstep")
    }

    @Test
    fun repeated_armings_climb_the_ladder() {
        var state = fail(fail(NotaryThrottleState.EMPTY))
        assertEquals(1, state.attempt)
        state = fail(fail(state))
        assertEquals(2, state.attempt, "each threshold crossing is one rung further")
    }

    @Test
    fun a_generated_proof_clears_the_streak_and_the_ladder() {
        val state = NotaryProofThrottle.onSuccess(fail(fail(fail(fail(NotaryThrottleState.EMPTY)))))
        assertEquals(0, state.attempt)
        assertEquals(0, state.consecutiveFailures)
    }

    @Test
    fun a_success_does_not_lift_a_standing_park() {
        // Only reachable through a caller that bypassed the gate, and one lucky proof must not cancel the
        // backstop — the same rule the create throttle's global block follows.
        val parked = fail(fail(NotaryThrottleState.EMPTY))
        val after = NotaryProofThrottle.onSuccess(parked)
        assertEquals(parked.parkedUntil, after.parkedUntil)
        assertNotNull(NotaryProofThrottle.parkedUntil(after, T0))
    }

    @Test
    fun a_deadline_exactly_now_has_expired() {
        val parked = fail(fail(NotaryThrottleState.EMPTY))
        val until = assertNotNull(parked.parkedUntil)
        assertNull(NotaryProofThrottle.parkedUntil(parked, until))
        assertNull(NotaryProofThrottle.parkedUntil(parked, until + 1.seconds))
    }

    @Test
    fun an_expired_park_keeps_its_rung() {
        // The rung must survive its own cooldown — that is exactly when the next failure is most likely, and
        // restarting from the bottom there is how a ladder never climbs.
        val parked = fail(fail(NotaryThrottleState.EMPTY))
        val next = fail(fail(parked, at = assertNotNull(parked.parkedUntil) + 1.seconds))
        assertEquals(2, next.attempt)
    }

    @Test
    fun retry_after_is_whole_seconds_and_never_zero() {
        // "retry after 0s" reads as "retry now", which is the one thing a cooldown exists to prevent.
        assertEquals(1, NotaryProofThrottle.retryAfterSeconds(T0 + 200.milliseconds, T0))
        assertEquals(1, NotaryProofThrottle.retryAfterSeconds(T0, T0))
        assertEquals(90, NotaryProofThrottle.retryAfterSeconds(T0 + 90.seconds, T0))
    }

    @Test
    fun the_config_rejects_a_ladder_with_nowhere_to_climb() {
        // A ceiling below the base means rung one is already capped, so escalation does nothing. Under equal
        // jitter there is no separate floor left to contradict — it is derived as half the capped draw.
        assertFailsWith<IllegalArgumentException> { NotaryBreakerConfig(cooldownBaseMs = 60_000, cooldownMaxMs = 30_000) }
        assertFailsWith<IllegalArgumentException> { NotaryBreakerConfig(breakerThreshold = 0) }
        assertFailsWith<IllegalArgumentException> { NotaryBreakerConfig(cooldownBaseMs = 0) }
    }
}
