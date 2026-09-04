package com.dmarket.p2p.tracker.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class BlockingStateTest {
    /**
     * The precedence contract, spelled out as the ranked list it is: most actionable and most upstream
     * first. A change to [BlockingState.resolve] that reorders anything fails
     * [resolves_by_precedence_for_every_combination_of_inputs] below, which drives all 16 input
     * combinations off THIS list rather than off a hand-written table — so the guard cannot be satisfied
     * by editing one case to match a new order, and a newly added input has nowhere to hide.
     */
    private val precedence = listOf(
        TrackerBlock.DM_SESSION_MISSING,
        TrackerBlock.STEAM_SESSION_MISSING,
        TrackerBlock.STEAM_ACCOUNT_MISMATCH,
        TrackerBlock.DM_CONNECTION_ERROR,
    )

    private fun resolve(inputs: Set<TrackerBlock>) = BlockingState.resolve(
        missingConnection = TrackerBlock.DM_SESSION_MISSING in inputs,
        serverError = TrackerBlock.DM_CONNECTION_ERROR in inputs,
        steamAccountMismatch = TrackerBlock.STEAM_ACCOUNT_MISMATCH in inputs,
        steamSessionMissing = TrackerBlock.STEAM_SESSION_MISSING in inputs,
    )

    /** Every subset of the four inputs must resolve to the highest-ranked one present. */
    @Test
    fun resolves_by_precedence_for_every_combination_of_inputs() {
        for (mask in 0 until (1 shl precedence.size)) {
            val inputs = precedence.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.toSet()
            val expected = precedence.firstOrNull { it in inputs } ?: TrackerBlock.NONE
            assertEquals(expected, resolve(inputs), "resolve($inputs)")
        }
    }

    /**
     * The ranking itself, asserted pairwise and independently of the loop that consumes it — this is the
     * product decision (DMarket sign-in → Steam sign-in → wrong Steam account → unreachable backend), so
     * a future change has to come here and say so out loud.
     */
    @Test
    fun the_ranking_is_dmarket_login_then_steam_login_then_wrong_account_then_backend_error() {
        assertEquals(
            listOf(
                TrackerBlock.DM_SESSION_MISSING,
                TrackerBlock.STEAM_SESSION_MISSING,
                TrackerBlock.STEAM_ACCOUNT_MISMATCH,
                TrackerBlock.DM_CONNECTION_ERROR,
            ),
            precedence,
            "the intended precedence changed — update the host surfaces (popup, on-page banner, toolbar " +
                "icon) and TradeTrackerLoop's evaluation order with it",
        )
        for (i in precedence.indices) {
            for (j in i + 1 until precedence.size) {
                val higher = precedence[i]
                val lower = precedence[j]
                assertEquals(higher, resolve(setOf(higher, lower)), "$higher must outrank $lower")
            }
        }
    }

    /** Nothing raised is the honest all-clear — never a guessed block. */
    @Test
    fun no_input_resolves_to_none() {
        assertEquals(TrackerBlock.NONE, resolve(emptySet()))
    }

    @Test
    fun steam_session_missing_defaults_to_false_for_callers_that_omit_it() {
        assertEquals(
            TrackerBlock.NONE,
            BlockingState.resolve(missingConnection = false, serverError = false, steamAccountMismatch = false),
        )
    }

    /**
     * Guards the enum's own declaration order, which its KDoc promises is ascending precedence. Nothing
     * reads an `ordinal`, so this is documentation integrity rather than behaviour — but a state added in
     * the wrong slot is exactly how the doc and the code drift apart.
     */
    @Test
    fun the_enum_is_declared_in_ascending_precedence_with_none_first() {
        assertEquals(
            listOf(TrackerBlock.NONE) + precedence.reversed(),
            TrackerBlock.entries.toList(),
        )
    }
}
