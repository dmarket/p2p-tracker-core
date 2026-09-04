package com.dmarket.p2p.tracker.policy

import com.dmarket.p2p.tracker.config.CadenceConfig
import com.dmarket.p2p.tracker.model.RuntimeSurface
import com.dmarket.p2p.tracker.model.TrackerMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class CadencePolicyTest {
    private val cadence = CadencePolicy.defaults()

    @Test
    fun fe_target_intervals_are_fast_for_offers_and_sparse_for_history() {
        assertEquals(3.minutes, cadence.targetInterval(PollClass.ActiveOffer))
        assertEquals(1.hours, cadence.targetInterval(PollClass.RevertWatch))
    }

    @Test
    fun next_poll_delay_uses_the_fe_target_when_above_the_floor() {
        // Web active-offer floor is 60s; the 3-min FE target wins.
        assertEquals(
            3.minutes,
            cadence.nextPollDelay(RuntimeSurface.WebChrome, TrackerMode.Foreground, PollClass.ActiveOffer),
        )
    }

    @Test
    fun next_poll_delay_clamps_up_to_the_platform_floor() {
        // iOS background floor is 15 min; even the fast-poll target is clamped up to it.
        assertEquals(
            15.minutes,
            cadence.nextPollDelay(RuntimeSurface.IosNative, TrackerMode.Background, PollClass.ActiveOffer),
        )
    }

    // ---- expedited (transient state-9) cadence -------------------------------------------------------

    @Test
    fun expedited_target_is_the_configured_interval() {
        assertEquals(15.seconds, cadence.targetInterval(PollClass.ExpeditedOffer))
    }

    @Test
    fun expedited_poll_delay_clamps_up_to_the_platform_floor() {
        // Web floor 60s dominates the 15s expedited target → 60s (still 3× faster than the 3-min baseline).
        assertEquals(
            60.seconds,
            cadence.nextPollDelay(RuntimeSurface.WebChrome, TrackerMode.Foreground, PollClass.ExpeditedOffer),
        )
        // Mobile foreground floor is 30s.
        assertEquals(
            30.seconds,
            cadence.nextPollDelay(RuntimeSurface.IosNative, TrackerMode.Foreground, PollClass.ExpeditedOffer),
        )
        assertEquals(
            30.seconds,
            cadence.nextPollDelay(RuntimeSurface.AndroidNative, TrackerMode.Foreground, PollClass.ExpeditedOffer),
        )
        // Mobile background floor 15 min is the OS cap — expedited cannot help while suspended.
        assertEquals(
            15.minutes,
            cadence.nextPollDelay(RuntimeSurface.IosNative, TrackerMode.Background, PollClass.ExpeditedOffer),
        )
    }

    @Test
    fun expedited_window_reads_from_config() {
        assertEquals(5.minutes, cadence.expeditedWindow)
    }

    // ---- pushCoalesceDelay ----------------------------------------------------------------------

    private val t0 = Instant.fromEpochMilliseconds(0)

    @Test
    fun push_with_no_prior_run_is_honoured_immediately() {
        assertEquals(
            Duration.ZERO,
            cadence.pushCoalesceDelay(t0, null, RuntimeSurface.WebChrome, TrackerMode.Foreground),
        )
    }

    @Test
    fun push_within_the_floor_waits_out_the_remainder() {
        // 20s into the 60s web active-offer floor → wait the remaining 40s.
        val delay = cadence.pushCoalesceDelay(t0 + 20.seconds, t0, RuntimeSurface.WebChrome, TrackerMode.Foreground)
        assertEquals(40.seconds, delay)
    }

    @Test
    fun push_at_or_past_the_floor_is_honoured_immediately() {
        assertEquals(
            Duration.ZERO,
            cadence.pushCoalesceDelay(t0 + 60.seconds, t0, RuntimeSurface.WebChrome, TrackerMode.Foreground),
        )
    }

    // ---- nextHeartbeatDelay: backend ttl_seconds, authoritative + clamped --------------------------

    @Test
    fun heartbeat_delay_absent_falls_back_to_heartbeat_floor() {
        assertEquals(
            cadence.heartbeatFloor(RuntimeSurface.WebChrome, TrackerMode.Background),
            cadence.nextHeartbeatDelay(0, RuntimeSurface.WebChrome, TrackerMode.Background),
        )
    }

    @Test
    fun heartbeat_delay_honours_the_ttl_less_one_poll_floor_of_margin() {
        // 10-min ttl is between the web floor and the 1h ceiling → honoured, less the 60s web poll floor.
        assertEquals(9.minutes, cadence.nextHeartbeatDelay(600, RuntimeSurface.WebChrome, TrackerMode.Background))
    }

    /**
     * When the beat actually lands. The loop cannot wake on an arbitrary instant — it re-arms to
     * `min(poll, remaining)` with the remainder coerced up to the poll floor — so a target falls through to
     * the next multiple of the wake grid. This is the model the margin is sized against.
     */
    private fun beatLandsAt(target: Duration, grid: Duration): Duration {
        val steps = (target + grid - 1.milliseconds) / grid // ceil, in whole grid steps
        return grid * steps.toInt()
    }

    @Test
    fun the_beat_lands_inside_the_advertised_cadence_on_every_servable_ttl() {
        // The regression the margin exists for, and the invariant that fixes it. Web: heartbeat floor and
        // poll floor are both 60s, and the expedited window pulls the live poll grid down to that floor
        // exactly when deals are in flight — which is when presence matters.
        val surface = RuntimeSurface.WebChrome
        val mode = TrackerMode.Background
        val grid = cadence.pollFloor(surface, mode)
        // 85 and 90 are what dev2 advertises; the rest span the floor boundary and a grid multiple.
        for (ttl in listOf(60, 61, 85, 90, 100, 120, 121, 300, 600)) {
            val delay = cadence.nextHeartbeatDelay(ttl, surface, mode)
            val landed = beatLandsAt(delay, grid)
            assertTrue(landed <= ttl.seconds, "ttl ${ttl}s: target $delay lands at $landed, past the cadence")
        }
    }

    @Test
    fun without_the_margin_the_dev_ttl_would_land_a_whole_grid_late() {
        // Pins the old behaviour as the bug it was: aiming at the ttl itself puts the beat at 120s on an
        // 85s cadence, because 85 is not a multiple of the 60s grid.
        val grid = cadence.pollFloor(RuntimeSurface.WebChrome, TrackerMode.Background)
        assertEquals(120.seconds, beatLandsAt(85.seconds, grid))
        // And what we now aim at instead lands at 60s — inside it.
        assertEquals(
            60.seconds,
            beatLandsAt(cadence.nextHeartbeatDelay(85, RuntimeSurface.WebChrome, TrackerMode.Background), grid),
        )
    }

    @Test
    fun heartbeat_margin_is_a_no_op_where_the_platform_floor_already_dominates() {
        // Mobile background: the 15-min heartbeat floor outranks both the ttl and the margin, unchanged.
        assertEquals(
            15.minutes,
            cadence.nextHeartbeatDelay(90, RuntimeSurface.IosNative, TrackerMode.Background),
        )
    }

    @Test
    fun heartbeat_delay_clamps_up_to_floor_and_down_to_max() {
        // A 5s ttl is clamped up to the 60s web floor (the margin cannot push it below zero either).
        assertEquals(60.seconds, cadence.nextHeartbeatDelay(5, RuntimeSurface.WebChrome, TrackerMode.Background))
        // A 6h ttl is clamped down to the safety ceiling so the client still checks in.
        assertEquals(
            cadence.maxActionDelay,
            cadence.nextHeartbeatDelay(6 * 3600, RuntimeSurface.WebChrome, TrackerMode.Background),
        )
    }

    // ---- nextHeartbeatDelay: fallback interval, used only when the backend sends no ttl -------------

    private fun withFallback(intervalMs: Int) = CadencePolicy(CadenceConfig(fallbackHeartbeatIntervalMs = intervalMs))

    @Test
    fun heartbeat_delay_absent_uses_the_fallback_interval_when_set() {
        assertEquals(
            5.minutes,
            withFallback(300_000).nextHeartbeatDelay(0, RuntimeSurface.WebChrome, TrackerMode.Background),
        )
    }

    @Test
    fun fallback_interval_is_clamped_like_a_ttl() {
        // Below the 60s web floor → clamped up.
        assertEquals(
            60.seconds,
            withFallback(5_000).nextHeartbeatDelay(0, RuntimeSurface.WebChrome, TrackerMode.Background),
        )
        // Above the 1h safety ceiling → clamped down so the client still checks in.
        assertEquals(
            cadence.maxActionDelay,
            withFallback(6 * 3_600_000).nextHeartbeatDelay(0, RuntimeSurface.WebChrome, TrackerMode.Background),
        )
    }

    @Test
    fun backend_ttl_always_outranks_the_fallback_interval() {
        assertEquals(
            9.minutes,
            withFallback(300_000).nextHeartbeatDelay(600, RuntimeSurface.WebChrome, TrackerMode.Background),
        )
    }

    @Test
    fun the_fallback_interval_is_not_margined() {
        // The margin exists to stay inside a cadence somebody else advertised. The fallback is our own
        // number, so subtracting from it would just be a slower heartbeat for no reason.
        assertEquals(
            5.minutes,
            withFallback(300_000).nextHeartbeatDelay(0, RuntimeSurface.WebChrome, TrackerMode.Background),
        )
    }
}
