package com.dmarket.p2p.tracker.engine

import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.steam.OfferDirection
import com.dmarket.p2p.tracker.model.steam.SteamSentOffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class CreateOfferMatchTest {
    private val t0 = Instant.parse("2026-11-13T12:00:00Z")
    private val partner = SteamId("76561198000000002")
    private val assets = setOf(AssetId("a1"), AssetId("a2"))

    private fun offer(
        id: String = "o1",
        to: SteamId = partner,
        gives: Set<String> = setOf("a1", "a2"),
        state: Int = 9,
        at: Instant = t0,
        direction: OfferDirection = OfferDirection.SENT,
    ) = SteamSentOffer(OfferId(id), to, state, gives.mapTo(mutableSetOf(), ::AssetId), at, direction = direction)

    @Test
    fun an_offer_in_each_bindable_state_binds() {
        listOf(2, 3, 9, 11).forEach { state ->
            assertEquals(OfferId("o1"), CreateOfferMatch.bindable(listOf(offer(state = state)), partner, assets)?.offerId, "state $state")
        }
    }

    @Test
    fun an_offer_in_any_other_state_does_not_bind() {
        listOf(1, 4, 5, 6, 7, 8, 10).forEach { state ->
            assertNull(CreateOfferMatch.bindable(listOf(offer(state = state)), partner, assets), "state $state")
        }
    }

    @Test
    fun only_the_same_partner_and_exactly_the_same_assets_bind() {
        val others = listOf(
            offer(to = SteamId("76561198000000003")),
            offer(gives = setOf("a1")),
            offer(gives = setOf("a1", "a2", "a3")),
            offer(direction = OfferDirection.RECEIVED),
        )
        others.forEach { assertNull(CreateOfferMatch.bindable(listOf(it), partner, assets), "$it") }
    }

    @Test
    fun the_newest_matching_offer_binds() {
        val offers = listOf(offer("old", at = t0), offer("new", at = t0 + 5.seconds), offer("mid", at = t0 + 2.seconds))

        assertEquals(OfferId("new"), CreateOfferMatch.bindable(offers, partner, assets)?.offerId)
    }

    @Test
    fun gives_all_accepts_a_superset_and_gives_exactly_does_not() {
        val superset = offer(gives = setOf("a1", "a2", "a3"))

        assertTrue(CreateOfferMatch.givesAll(superset, partner, assets))
        assertFalse(CreateOfferMatch.givesExactly(superset, partner, assets))
        assertTrue(CreateOfferMatch.givesExactly(offer(), partner, assets))
        assertFalse(CreateOfferMatch.givesAll(offer(to = SteamId("76561198000000003")), partner, assets))
    }
}
