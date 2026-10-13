package com.dmarket.p2p.tracker.model.marketplace

import com.dmarket.p2p.tracker.engine.DecisiveTransitions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProofSiteTest {

    /**
     * The backend's rows, by site name: what its `provableTransitions` maps each (axis, code) to. A site whose
     * codes drift from these is a code proven where the backend does not want it, or reported unproven where
     * it refuses one.
     */
    private val backendRows: Map<String, Pair<TradeStatusSource, Set<Int>>> = mapOf(
        "offer_created" to (TradeStatusSource.OFFER to emptySet()),
        "offer_confirmed" to (TradeStatusSource.OFFER to setOf(2)),
        "offer_accepted" to (TradeStatusSource.OFFER to setOf(3)),
        "offer_declined" to (TradeStatusSource.OFFER to setOf(4, 7)),
        "offer_cancelled" to (TradeStatusSource.OFFER to setOf(6, 8, 10)),
        "trade_completed" to (TradeStatusSource.HISTORY to setOf(3, 9)),
        "trade_reversed" to (TradeStatusSource.HISTORY to setOf(4, 5, 6, 7, 11, 12)),
    )

    private val known = ProofSite.entries - ProofSite.UNKNOWN

    @Test
    fun every_site_maps_to_exactly_the_backends_codes() {
        assertEquals(backendRows.keys, known.map { it.wireName }.toSet(), "the site names")
        for (site in known) {
            assertEquals(backendRows.getValue(site.wireName), site.source to site.codes, site.wireName)
        }
    }

    @Test
    fun every_decisive_code_belongs_to_exactly_one_site() {
        // Both directions, so neither copy can drift alone: a decisive code on no site would never be proven
        // on a deal that names its sites, and a site code outside the decisive set would never be proven on
        // one that names none.
        for (source in TradeStatusSource.entries) {
            for (code in 0..20) {
                val sites = known.filter { it.source == source && code in it.codes }
                val expected = if (DecisiveTransitions.isDecisive(source, code)) 1 else 0
                assertEquals(expected, sites.size, "$source $code is on $sites")
            }
        }
    }

    @Test
    fun a_transition_resolves_to_its_site() {
        assertEquals(ProofSite.OFFER_CONFIRMED, ProofSite.forTransition(TradeStatusSource.OFFER, 2))
        assertEquals(ProofSite.OFFER_CANCELLED, ProofSite.forTransition(TradeStatusSource.OFFER, 10))
        assertEquals(ProofSite.TRADE_COMPLETED, ProofSite.forTransition(TradeStatusSource.HISTORY, 9))
        assertEquals(ProofSite.TRADE_REVERSED, ProofSite.forTransition(TradeStatusSource.HISTORY, 4))
        // The same number on the other axis is another transition entirely.
        assertEquals(ProofSite.OFFER_DECLINED, ProofSite.forTransition(TradeStatusSource.OFFER, 4))
        assertNull(ProofSite.forTransition(TradeStatusSource.OFFER, 9), "9 CreatedNeedsConfirmation is on no site")
        assertNull(ProofSite.forTransition(TradeStatusSource.HISTORY, 8), "8 RollbackFailed is on no site")
    }

    @Test
    fun wire_names_resolve_and_anything_else_is_unknown() {
        for (site in known) assertEquals(site, ProofSite.fromWire(site.wireName))
        assertEquals(ProofSite.OFFER_CANCELLED, ProofSite.fromWire(" Offer_Cancelled "))
        assertEquals(ProofSite.UNKNOWN, ProofSite.fromWire("offer_relisted"))
        assertEquals(ProofSite.UNKNOWN, ProofSite.fromWire(""))
    }
}
