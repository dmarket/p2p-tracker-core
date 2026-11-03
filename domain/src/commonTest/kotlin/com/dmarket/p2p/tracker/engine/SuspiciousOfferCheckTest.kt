package com.dmarket.p2p.tracker.engine

import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.marketplace.TrackedDeal
import com.dmarket.p2p.tracker.model.steam.CreateLease
import com.dmarket.p2p.tracker.model.steam.OfferDirection
import com.dmarket.p2p.tracker.model.steam.SteamSentOffer
import com.dmarket.p2p.tracker.model.steam.SuspicionLevel
import com.dmarket.p2p.tracker.model.steam.SuspiciousOffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class SuspiciousOfferCheckTest {
    private val now = Instant.parse("2026-10-30T12:00:00Z")
    private val partner = SteamId("76561198000000002")
    private val scammer = SteamId("76561198781084366")

    private fun offer(
        to: SteamId = partner,
        gives: Set<String> = setOf("a1", "a2"),
        receives: Set<String> = emptySet(),
        state: Int = 2,
        at: Instant = now - 1.minutes,
        direction: OfferDirection = OfferDirection.SENT,
    ) = SteamSentOffer(
        OfferId("o1"),
        to,
        state,
        gives.mapTo(mutableSetOf(), ::AssetId),
        at,
        receives.mapTo(mutableSetOf(), ::AssetId),
        direction,
    )

    /** A received offer that takes this account's items and gives nothing: the shape of a DMarket deposit bot's. */
    private fun received(
        from: SteamId = partner,
        gives: Set<String> = setOf("a1"),
        receives: Set<String> = emptySet(),
        state: Int = 2,
        at: Instant = now - 1.minutes,
    ) = offer(to = from, gives = gives, receives = receives, state = state, at = at, direction = OfferDirection.RECEIVED)

    private fun lease(to: SteamId = partner, assets: Set<String> = setOf("a1", "a2"), at: Instant = now - 2.minutes) =
        CreateLease(DealId("deal-1"), to, assets.mapTo(mutableSetOf(), ::AssetId), at)

    private fun level(
        offer: SteamSentOffer,
        leases: List<CreateLease> = emptyList(),
        tracking: List<TrackedDeal> = emptyList(),
        trusted: Set<SteamId> = emptySet(),
        scam: Set<SteamId> = setOf(scammer),
        names: Map<SteamId, String> = emptyMap(),
    ) = SuspiciousOfferCheck.classify(offer, tracking, leases, trusted, scam, now, names)

    @Test
    fun a_give_only_offer_with_no_deal_is_unlinked() {
        assertEquals(SuspicionLevel.UNLINKED_GIVE_ONLY, level(offer()))
    }

    @Test
    fun the_same_offer_covered_by_a_lease_is_none() {
        assertEquals(SuspicionLevel.NONE, level(offer(), leases = listOf(lease())))
    }

    @Test
    fun a_known_scam_partner_is_impersonation_even_with_a_lease() {
        val toScammer = offer(to = scammer)
        assertEquals(SuspicionLevel.IMPERSONATION, level(toScammer, leases = listOf(lease(to = scammer)), trusted = setOf(scammer)))
    }

    @Test
    fun an_offer_that_receives_items_is_none() {
        assertEquals(SuspicionLevel.NONE, level(offer(receives = setOf("b1"))))
    }

    @Test
    fun an_accepted_offer_is_none() {
        assertEquals(SuspicionLevel.NONE, level(offer(state = 3)))
        assertEquals(SuspicionLevel.NONE, level(offer(to = scammer, state = 3)))
    }

    @Test
    fun an_offer_waiting_for_mobile_confirmation_is_checked() {
        assertEquals(SuspicionLevel.UNLINKED_GIVE_ONLY, level(offer(state = 9)))
    }

    @Test
    fun an_offer_a_tracked_deal_names_is_none() {
        val deal = TrackedDeal(dealId = DealId("deal-1"), steamOfferId = OfferId("o1"))
        assertEquals(SuspicionLevel.NONE, level(offer(), tracking = listOf(deal)))
    }

    @Test
    fun a_trusted_partner_is_none() {
        assertEquals(SuspicionLevel.NONE, level(offer(), trusted = setOf(partner)))
    }

    @Test
    fun a_lease_covers_only_its_own_partner_assets_and_time() {
        assertEquals(SuspicionLevel.UNLINKED_GIVE_ONLY, level(offer(), leases = listOf(lease(to = SteamId("76561198000000003")))))
        assertEquals(SuspicionLevel.UNLINKED_GIVE_ONLY, level(offer(gives = setOf("a1", "x9")), leases = listOf(lease())))
        // Made well before this device's create: not that create's offer.
        assertEquals(SuspicionLevel.UNLINKED_GIVE_ONLY, level(offer(at = now - 1.hours), leases = listOf(lease())))
        // Past the lease's 24 h.
        assertEquals(
            SuspicionLevel.UNLINKED_GIVE_ONLY,
            level(offer(at = now - 25.hours), leases = listOf(lease(at = now - 25.hours))),
        )
    }

    @Test
    fun evaluate_lists_only_flagged_offers() {
        val flagged = SuspiciousOfferCheck.evaluate(
            sent = listOf(offer(), offer(state = 3), offer(to = scammer, receives = setOf("b1"))),
            tracking = emptyList(),
            leases = emptyList(),
            trustedPartners = emptySet(),
            knownScamPartners = setOf(scammer),
            now = now,
        )
        val assets = setOf(AssetId("a1"), AssetId("a2"))
        assertEquals(
            listOf(
                SuspiciousOffer(OfferId("o1"), partner, assets, SuspicionLevel.UNLINKED_GIVE_ONLY),
                SuspiciousOffer(OfferId("o1"), scammer, assets, SuspicionLevel.IMPERSONATION),
            ),
            flagged,
        )
    }

    @Test
    fun a_partner_named_like_dmarket_is_impersonation_even_when_leased_trusted_or_linked() {
        val names = mapOf(partner to "DMarket Support")
        val deal = TrackedDeal(dealId = DealId("deal-1"), steamOfferId = OfferId("o1"))
        assertEquals(
            SuspicionLevel.IMPERSONATION,
            level(
                offer(receives = setOf("b1")),
                leases = listOf(lease()),
                tracking = listOf(deal),
                trusted = setOf(partner),
                names = names,
            ),
        )
        assertEquals(SuspicionLevel.NONE, level(offer(state = 3), names = names))
        assertEquals(SuspicionLevel.UNLINKED_GIVE_ONLY, level(offer(), names = mapOf(partner to "Dmitry")))
    }

    @Test
    fun evaluate_carries_the_persona_name() {
        val flagged = SuspiciousOfferCheck.evaluate(
            sent = listOf(offer()),
            tracking = emptyList(),
            leases = emptyList(),
            trustedPartners = emptySet(),
            knownScamPartners = emptySet(),
            now = now,
            personaNames = mapOf(partner to "dmraket"),
        ).single()
        assertEquals(SuspicionLevel.IMPERSONATION to "dmraket", flagged.level to flagged.personaName)
    }

    @Test
    fun a_received_give_only_offer_from_a_partner_named_like_dmarket_is_none() {
        // A real DMarket deposit bot sends exactly this; its name is no evidence against it in stage one.
        assertEquals(SuspicionLevel.NONE, level(received(), names = mapOf(partner to "DM Bot")))
    }

    @Test
    fun a_received_offer_from_a_known_scam_account_taking_an_item_is_impersonation() {
        assertEquals(SuspicionLevel.IMPERSONATION, level(received(from = scammer)))
        assertEquals(SuspicionLevel.IMPERSONATION, level(received(from = scammer, receives = setOf("b1"))))
        assertEquals(SuspicionLevel.IMPERSONATION, level(received(from = scammer, state = 9)))
    }

    @Test
    fun a_received_offer_from_a_known_scam_account_that_takes_nothing_is_none() {
        assertEquals(SuspicionLevel.NONE, level(received(from = scammer, gives = emptySet(), receives = setOf("b1"))))
    }

    @Test
    fun a_received_offer_that_is_no_longer_active_is_none() {
        for (state in listOf(3, 6, 7)) assertEquals(SuspicionLevel.NONE, level(received(from = scammer, state = state)), "state $state")
    }

    @Test
    fun a_received_give_only_offer_is_never_unlinked_whatever_the_trust() {
        assertEquals(SuspicionLevel.NONE, level(received()))
        assertEquals(SuspicionLevel.NONE, level(received(), trusted = setOf(partner)))
    }

    @Test
    fun a_known_scam_account_outranks_trust_on_a_received_offer_too() {
        // Pin: the known-scam rule ran before trust already.
        assertEquals(SuspicionLevel.IMPERSONATION, level(received(from = scammer), trusted = setOf(scammer)))
    }

    @Test
    fun only_an_active_received_offer_that_takes_an_item_is_a_candidate() {
        assertEquals(true, SuspiciousOfferCheck.candidate(received()))
        assertEquals(false, SuspiciousOfferCheck.candidate(received(gives = emptySet(), receives = setOf("b1"))))
        assertEquals(false, SuspiciousOfferCheck.candidate(received(state = 3)))
        assertEquals(true, SuspiciousOfferCheck.candidate(offer(gives = emptySet(), receives = setOf("b1"))))
    }

    @Test
    fun evaluate_carries_the_direction() {
        val flagged = SuspiciousOfferCheck.evaluate(
            sent = listOf(received(from = scammer), offer()),
            tracking = emptyList(),
            leases = emptyList(),
            trustedPartners = emptySet(),
            knownScamPartners = setOf(scammer),
            now = now,
        )
        assertEquals(listOf(OfferDirection.RECEIVED, OfferDirection.SENT), flagged.map { it.direction })
    }

    // ---- received-offer name check (DMarket's own verdict) ----

    private val grace = 2.minutes
    private val dmBot = mapOf(partner to "DM Bot")
    private val oldEnough = now - grace

    private fun named(
        offer: SteamSentOffer,
        verdicts: Map<OfferId, Boolean>,
        on: Boolean = true,
        names: Map<SteamId, String> = dmBot,
        scam: Set<SteamId> = setOf(scammer),
        held: Map<OfferId, Duration> = mapOf(OfferId("o1") to grace),
    ) = SuspiciousOfferCheck.classify(offer, emptyList(), emptyList(), emptySet(), scam, now, names, on, verdicts, grace, held)

    @Test
    fun a_received_offer_named_like_dmarket_that_dmarket_disowns_past_the_grace_is_impersonation() {
        assertEquals(SuspicionLevel.IMPERSONATION, named(received(at = oldEnough), mapOf(OfferId("o1") to false)))
    }

    @Test
    fun dmarkets_not_ours_held_for_less_than_the_grace_is_none() {
        val young = mapOf(OfferId("o1") to grace - 1.seconds)
        assertEquals(SuspicionLevel.NONE, named(received(at = oldEnough), mapOf(OfferId("o1") to false), held = young))
    }

    @Test
    fun the_grace_runs_from_the_first_not_ours_and_not_from_steams_time_created() {
        // A device clock running fast makes Steam's time_created look old; DMarket's own clock does not.
        val skewed = received(at = now - 1.hours)
        val fresh = mapOf(OfferId("o1") to 10.seconds)
        assertEquals(SuspicionLevel.NONE, named(skewed, mapOf(OfferId("o1") to false), held = fresh))
    }

    @Test
    fun dmarkets_not_ours_with_no_measurement_is_none_unless_the_grace_is_zero() {
        val notOurs = mapOf(OfferId("o1") to false)
        assertEquals(SuspicionLevel.NONE, named(received(at = oldEnough), notOurs, held = emptyMap()))
        val noGrace = SuspiciousOfferCheck.classify(
            received(), emptyList(), emptyList(), emptySet(), setOf(scammer), now, dmBot, true, notOurs, Duration.ZERO,
        )
        assertEquals(SuspicionLevel.IMPERSONATION, noGrace)
    }

    @Test
    fun a_received_offer_dmarket_owns_is_none() {
        assertEquals(SuspicionLevel.NONE, named(received(at = oldEnough), mapOf(OfferId("o1") to true)))
    }

    @Test
    fun a_received_offer_with_no_dmarket_answer_is_none() {
        assertEquals(SuspicionLevel.NONE, named(received(at = oldEnough), emptyMap()))
    }

    @Test
    fun dmarkets_not_ours_is_ignored_while_the_name_check_is_off() {
        assertEquals(SuspicionLevel.NONE, named(received(at = oldEnough), mapOf(OfferId("o1") to false), on = false))
    }

    @Test
    fun dmarkets_not_ours_does_not_flag_a_partner_whose_name_is_not_dmarkets() {
        val plain = mapOf(partner to "Alice")
        assertEquals(SuspicionLevel.NONE, named(received(at = oldEnough), mapOf(OfferId("o1") to false), names = plain))
        assertEquals(false, SuspiciousOfferCheck.asksDmarket(received(), setOf(scammer), plain))
    }

    @Test
    fun a_known_scam_partner_outranks_dmarkets_ours() {
        // Pin of order: the known-scam list is checked before DMarket's answer.
        val fromScammer = received(from = scammer, at = oldEnough)
        assertEquals(SuspicionLevel.IMPERSONATION, named(fromScammer, mapOf(OfferId("o1") to true), names = mapOf(scammer to "DM Bot")))
        assertEquals(false, SuspiciousOfferCheck.asksDmarket(fromScammer, setOf(scammer), mapOf(scammer to "DM Bot")))
    }

    @Test
    fun only_an_active_received_offer_named_like_dmarket_that_takes_an_item_asks_dmarket() {
        assertEquals(true, SuspiciousOfferCheck.asksDmarket(received(), setOf(scammer), dmBot))
        assertEquals(false, SuspiciousOfferCheck.asksDmarket(received(gives = emptySet(), receives = setOf("b1")), setOf(scammer), dmBot))
        assertEquals(false, SuspiciousOfferCheck.asksDmarket(received(state = 3), setOf(scammer), dmBot))
        assertEquals(false, SuspiciousOfferCheck.asksDmarket(offer(), setOf(scammer), dmBot))
    }

    @Test
    fun a_sent_offer_ignores_dmarkets_verdict() {
        // Pin: the sent path keeps its own rules.
        val sent = offer(at = oldEnough)
        assertEquals(SuspicionLevel.IMPERSONATION, named(sent, mapOf(OfferId("o1") to true)))
        assertEquals(SuspicionLevel.UNLINKED_GIVE_ONLY, named(sent, mapOf(OfferId("o1") to true), names = emptyMap()))
    }

    @Test
    fun only_a_received_offer_flagged_on_the_name_is_marked_by_name() {
        val byName = received(at = oldEnough)
        val fromScammer = received(from = scammer, at = oldEnough).copy(offerId = OfferId("o2"))
        val sent = offer(at = oldEnough).copy(offerId = OfferId("o3"))
        val found = SuspiciousOfferCheck.evaluate(
            sent = listOf(byName, fromScammer, sent),
            tracking = emptyList(),
            leases = emptyList(),
            trustedPartners = emptySet(),
            knownScamPartners = setOf(scammer),
            now = now,
            personaNames = dmBot,
            receivedNameCheck = true,
            dmarketVerdicts = mapOf(OfferId("o1") to false, OfferId("o2") to false),
            isTradeOurGrace = grace,
            notOursFor = mapOf(OfferId("o1") to grace, OfferId("o2") to grace),
        )
        assertEquals(mapOf("o1" to true, "o2" to false, "o3" to false), found.associate { it.offerId.value to it.byName })
    }
}
