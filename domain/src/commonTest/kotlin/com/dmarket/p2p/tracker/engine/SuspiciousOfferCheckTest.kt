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
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
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
    private fun received(from: SteamId = partner, gives: Set<String> = setOf("a1"), receives: Set<String> = emptySet(), state: Int = 2) =
        offer(to = from, gives = gives, receives = receives, state = state, direction = OfferDirection.RECEIVED)

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
}
