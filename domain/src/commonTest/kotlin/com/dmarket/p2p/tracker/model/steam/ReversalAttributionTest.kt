package com.dmarket.p2p.tracker.model.steam

import com.dmarket.p2p.tracker.model.SteamId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class ReversalAttributionTest {

    private val modifiedAt = Instant.fromEpochSeconds(1_781_697_600)
    private val counterpartyAccountId = 39_780_002L
    private val counterparty = SteamId((counterpartyAccountId + SteamNotification.ACCOUNT_ID_OFFSET).toString())

    private fun notification(type: Int = SteamNotification.REVERSAL_TYPE, actor: Long = counterpartyAccountId, at: Instant = modifiedAt) =
        SteamNotification(type = type, actorAccountId = actor, timestamp = at)

    @Test
    fun exactly_one_matching_notification_resolves_the_actor() {
        val resolved = ReversalAttribution.resolve(listOf(notification()), counterparty, modifiedAt)
        assertEquals(counterparty, resolved)
    }

    @Test
    fun the_actor_is_expanded_from_a_32_bit_account_id() {
        // Steam reports `actor` as a 32-bit account id; the report carries a steamid64.
        val resolved = ReversalAttribution.resolve(listOf(notification()), counterparty, modifiedAt)
        assertEquals("76561198000045730", resolved?.value)
    }

    @Test
    fun ignores_notifications_that_fail_any_single_criterion() {
        // Each case differs from a match in exactly one respect, so each must resolve to nothing.
        val cases = mapOf(
            "wrong type" to notification(type = 1),
            "wrong actor" to notification(actor = counterpartyAccountId + 1),
            "timestamp one second early" to notification(at = modifiedAt - kotlin.time.Duration.parse("1s")),
            "timestamp one second late" to notification(at = modifiedAt + kotlin.time.Duration.parse("1s")),
        )
        for ((label, candidate) in cases) {
            assertNull(ReversalAttribution.resolve(listOf(candidate), counterparty, modifiedAt), "must not match on $label")
        }
    }

    @Test
    fun timestamp_matching_is_exact_with_no_tolerance() {
        // Stated explicitly because "close enough" is the tempting bug here, and a wrong actor on a
        // money-sensitive transition is worse than no actor at all.
        val nearMiss = notification(at = modifiedAt + kotlin.time.Duration.parse("1ms"))
        assertNull(ReversalAttribution.resolve(listOf(nearMiss), counterparty, modifiedAt))
    }

    @Test
    fun zero_matches_resolves_to_nothing() {
        assertNull(ReversalAttribution.resolve(emptyList(), counterparty, modifiedAt))
    }

    @Test
    fun several_matches_naming_one_actor_still_resolve_that_actor() {
        // A rollback is all-or-nothing over the initiator's whole protected set and Steam raises one
        // notification per affected trade, so a sweep of several trades produces several notifications at
        // the SAME instant naming the SAME party. Counting rows rejected that as ambiguous and threw away
        // an answer the filter had already proven unanimous — which then left the rollback unattributed
        // and re-reported on every tick.
        val sweep = listOf(notification(), notification(), notification())
        assertEquals(counterparty, ReversalAttribution.resolve(sweep, counterparty, modifiedAt))
    }

    @Test
    fun a_match_is_found_among_unrelated_notifications() {
        val stream = listOf(
            notification(type = 1, actor = 111),
            notification(type = 4, actor = 222),
            notification(),
            notification(type = 9, actor = counterpartyAccountId),
        )
        assertEquals(counterparty, ReversalAttribution.resolve(stream, counterparty, modifiedAt))
    }

    @Test
    fun an_unknown_counterparty_or_time_resolves_to_nothing_without_inspecting_the_stream() {
        val stream = listOf(notification())
        assertNull(ReversalAttribution.resolve(stream, counterparty = null, modifiedAt = modifiedAt))
        assertNull(ReversalAttribution.resolve(stream, counterparty = counterparty, modifiedAt = null))
    }
}
