package com.dmarket.p2p.tracker.engine

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExpeditedTransitionsTest {
    @Test
    fun only_state_9_is_expedited() {
        assertTrue(ExpeditedTransitions.isExpedited(9))
        for (code in listOf(1, 2, 3, 4, 5, 6, 7, 8, 10, 11, 12)) {
            assertFalse(ExpeditedTransitions.isExpedited(code), "offer code $code must not be expedited")
        }
    }

    @Test
    fun null_offer_code_is_not_expedited() {
        assertFalse(ExpeditedTransitions.isExpedited(null))
    }

    @Test
    fun any_expedited_scans_the_collection() {
        assertFalse(ExpeditedTransitions.anyExpedited(emptyList()))
        // No transient deal: an active offer and an accepted-with-rollback-history deal.
        assertFalse(
            ExpeditedTransitions.anyExpedited(
                listOf(ObservedTrade(offerState = 2), ObservedTrade(offerState = 3, historyStatus = 12)),
            ),
        )
        // One deal at state 9 is enough to expedite the whole tick.
        assertTrue(
            ExpeditedTransitions.anyExpedited(
                listOf(ObservedTrade(offerState = 2), ObservedTrade(offerState = 9)),
            ),
        )
    }

    @Test
    fun history_axis_alone_never_triggers_expedited() {
        // A deal observed only on the history axis (offerState null) is not expedited, even at code 9.
        assertFalse(ExpeditedTransitions.anyExpedited(listOf(ObservedTrade(historyStatus = 9))))
    }
}
