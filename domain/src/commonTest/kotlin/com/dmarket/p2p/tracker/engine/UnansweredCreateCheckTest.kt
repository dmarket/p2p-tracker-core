package com.dmarket.p2p.tracker.engine

import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.ClaimPhase
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.DealWriteClaim
import com.dmarket.p2p.tracker.model.DirectiveId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.UnansweredCreate
import com.dmarket.p2p.tracker.model.marketplace.DirectiveAction
import com.dmarket.p2p.tracker.model.steam.SteamSentOffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class UnansweredCreateCheckTest {
    private val t0 = Instant.parse("2026-10-19T12:00:00Z")
    private val partner = SteamId("76561198000000002")
    private val unanswered = UnansweredCreate(partner, setOf(AssetId("a1"), AssetId("a2")))
    private val claim = DealWriteClaim(DealId("deal-1"), DirectiveAction.CREATE_OFFER, ClaimPhase.IN_FLIGHT, t0, DirectiveId("dir-1"))
    private val grace = 2.minutes

    private fun offer(
        id: String = "o1",
        to: SteamId = partner,
        gives: Set<String> = setOf("a1", "a2"),
        at: Instant = t0 + 1.seconds,
        state: Int = 9,
    ) = SteamSentOffer(OfferId(id), to, state, gives.mapTo(mutableSetOf(), ::AssetId), at)

    private fun verdict(offers: List<SteamSentOffer>, after: Duration = 1.minutes) =
        UnansweredCreateCheck.evaluate(claim, unanswered, offers, t0 + after, grace)

    @Test
    fun the_offer_to_the_partner_giving_every_asset_since_the_attempt_is_found() {
        val match = offer(gives = setOf("a1", "a2", "extra"))
        assertEquals(UnansweredCreateVerdict.Found(match), verdict(listOf(match)))
    }

    @Test
    fun another_partner_a_missing_asset_or_an_older_offer_is_not_it() {
        val cases = listOf(
            offer(to = SteamId("76561198000000555")),
            offer(gives = setOf("a1")),
            offer(at = t0 - 6.minutes),
        )
        for (case in cases) assertEquals(UnansweredCreateVerdict.NotYet, verdict(listOf(case)), "$case")
        assertEquals(UnansweredCreateVerdict.Absent, verdict(cases, after = 3.minutes))
    }

    @Test
    fun steams_clock_may_run_up_to_five_minutes_behind() {
        val early = offer(at = t0 - 4.minutes)
        assertEquals(UnansweredCreateVerdict.Found(early), verdict(listOf(early)))
    }

    @Test
    fun of_several_matches_the_newest_wins() {
        val older = offer(id = "older", at = t0 + 1.seconds)
        val newer = offer(id = "newer", at = t0 + 5.seconds)
        assertEquals(UnansweredCreateVerdict.Found(newer), verdict(listOf(older, newer)))
    }

    @Test
    fun absence_counts_only_once_the_grace_has_passed() {
        assertEquals(UnansweredCreateVerdict.NotYet, verdict(emptyList(), after = 119.seconds))
        assertEquals(UnansweredCreateVerdict.Absent, verdict(emptyList(), after = 120.seconds))
        assertEquals(UnansweredCreateVerdict.Found(offer()), verdict(listOf(offer()), after = 10.minutes), "a late find still counts")
    }

    @Test
    fun the_check_closes_at_the_claims_ttl_or_the_create_deadline_whichever_is_first() {
        val ttl = 15.minutes
        assertEquals(t0 + 15.minutes, UnansweredCreateCheck.closesAt(claim, unanswered, ttl))
        val tight = unanswered.copy(deadline = t0 + 4.minutes)
        assertEquals(t0 + 4.minutes, UnansweredCreateCheck.closesAt(claim, tight, ttl))
        assertEquals(t0 + 15.minutes, UnansweredCreateCheck.closesAt(claim, unanswered.copy(deadline = t0 + 1.hours()), ttl))
        assertFalse(UnansweredCreateCheck.isClosed(claim, tight, t0 + 3.minutes, ttl))
        assertTrue(UnansweredCreateCheck.isClosed(claim, tight, t0 + 4.minutes, ttl))
    }

    private fun Int.hours(): Duration = (this * 60).minutes
}
