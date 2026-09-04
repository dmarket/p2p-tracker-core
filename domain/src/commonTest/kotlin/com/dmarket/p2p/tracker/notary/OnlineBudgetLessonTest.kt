package com.dmarket.p2p.tracker.notary

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OnlineBudgetLessonTest {
    /** The line as it actually reached a session log on 2026-08-26, truncation included. */
    private val realRefusal =
        "tlsn protocol: internal error caused by: backend error: record layer error: attempted to decrypt " +
            "more data in the online phase than was configured, increase `max_recv_online` in the config: " +
            "current=16, additional=786, max="

    @Test
    fun the_requirement_is_read_out_of_the_real_refusal() {
        // 16 already decrypted + 786 this operation wanted = 802, which is the boundary every budget sweep
        // that day agreed on: 32 refused, 1024 and 2048 did not.
        assertEquals(802, OnlineBudgetLesson.requiredFrom(realRefusal))
    }

    @Test
    fun a_truncated_or_reordered_message_still_parses() {
        // The message is upstream's, wrapped by two error layers and truncated by the host log — the observed
        // line ends mid-way through `max=`. Anything still carrying the two numbers has to keep working.
        assertEquals(802, OnlineBudgetLesson.requiredFrom("... online phase ... additional=786, current=16, max=32"))
        assertEquals(802, OnlineBudgetLesson.requiredFrom("online phase current = 16 additional = 786"))
    }

    @Test
    fun every_other_failure_teaches_nothing() {
        // The common case, and it must stay silent: every proof failure reaches this parser.
        assertNull(OnlineBudgetLesson.requiredFrom(null))
        assertNull(OnlineBudgetLesson.requiredFrom(""))
        assertNull(
            OnlineBudgetLesson.requiredFrom(
                "prover worker discarded: the prover driver answered no liveness ping for 25000ms — wedged inside the wasm",
            ),
        )
        assertNull(OnlineBudgetLesson.requiredFrom("prover worker errored: undefined"))
        // Right marker, no numbers — a shape we must not guess at.
        assertNull(OnlineBudgetLesson.requiredFrom("record layer error: ... online phase ... but no fields"))
        // Numbers without the marker are some other error's fields, not ours.
        assertNull(OnlineBudgetLesson.requiredFrom("some other failure: current=16, additional=786"))
    }

    @Test
    fun a_requirement_under_the_floor_is_not_worth_remembering() {
        // 802 + 25% = 1002, under the 1024 floor. Returning the floor instead of null would store a row that
        // changes nothing today and lies tomorrow: a later raise of the configured default would be silently
        // pinned back down by a stored value that never meant "at most this".
        assertNull(OnlineBudgetLesson.learn(realRefusal, previous = null, floor = 1024, marginPercent = 25))
    }

    @Test
    fun a_requirement_over_the_floor_is_raised_with_margin() {
        val refusal = "online phase: current=1024, additional=800"
        // 1824 + 25% = 2280.
        assertEquals(2280, OnlineBudgetLesson.learn(refusal, previous = null, floor = 1024, marginPercent = 25))
    }

    @Test
    fun repeated_refusals_only_ever_move_up() {
        // The reported requirement is a LOWER bound — `current` counts what was decrypted before the prover
        // stopped, so records that would have followed are not in it. A later refusal can therefore report a
        // smaller number than one already learned, and taking it would undo the lesson and re-refuse forever.
        val high = "online phase: current=4000, additional=1000"
        val low = "online phase: current=16, additional=786"

        val first = OnlineBudgetLesson.learn(high, previous = null, floor = 1024, marginPercent = 25)
        assertEquals(6250, first)
        assertNull(
            OnlineBudgetLesson.learn(low, previous = first, floor = 1024, marginPercent = 25),
            "the lesson still stands, and null is how that is said — never a walk back down to the lower bound",
        )
    }

    @Test
    fun a_zero_margin_is_honoured_as_the_exact_requirement() {
        // The knob has to be able to say "no headroom" for a measurement run.
        assertEquals(1824, OnlineBudgetLesson.learn("online phase: current=1024, additional=800", null, 32, 0))
    }

    @Test
    fun null_means_leave_the_stored_value_alone_whatever_the_reason() {
        // The caller keeps no is-this-new check of its own, so every "nothing to store" case has to answer
        // null here — including the one where the previous lesson wins the raise and returning it verbatim
        // would look like a fresh value worth writing.
        assertNull(
            OnlineBudgetLesson.learn("wedged inside the wasm", previous = 2048, floor = 1024, marginPercent = 25),
            "a failure that teaches nothing",
        )
        assertNull(
            OnlineBudgetLesson.learn(realRefusal, previous = 2048, floor = 1024, marginPercent = 25),
            "a requirement the previous lesson already covers",
        )
    }

    @Test
    fun an_absurd_requirement_is_capped_rather_than_forgotten() {
        // Clamping, not rejecting: returning null on overflow would discard the single largest requirement
        // ever reported, which is the exact opposite of "only ever move up".
        val huge = "online phase: current=${Int.MAX_VALUE}, additional=${Int.MAX_VALUE}"

        assertEquals(Int.MAX_VALUE, OnlineBudgetLesson.requiredFrom(huge))
        assertEquals(Int.MAX_VALUE, OnlineBudgetLesson.learn(huge, previous = null, floor = 1024, marginPercent = 25))
    }
}
