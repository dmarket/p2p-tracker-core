package com.dmarket.p2p.tracker.model.marketplace

import com.dmarket.p2p.tracker.engine.DecisiveTransitions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProofSiteTest {

    /**
     * The backend's sites ([BACKEND_PROOF_SITES_JSON]): site name → axis → codes. A site whose codes drift from
     * these is a code proven where the backend does not want it, or reported unproven where it refuses one.
     */
    private val backendSites: Map<String, Map<String, Set<Int>>> =
        Json.parseToJsonElement(BACKEND_PROOF_SITES_JSON).jsonObject.getValue("sites").jsonObject.mapValues { (_, axes) ->
            axes.jsonObject.mapValues { (_, codes) -> codes.jsonArray.mapTo(mutableSetOf()) { it.jsonPrimitive.int } }
        }

    private val known = ProofSite.entries - ProofSite.UNKNOWN

    @Test
    fun every_site_maps_to_exactly_the_backends_codes() {
        assertEquals(backendSites.keys, known.map { it.wireName }.toSet(), "the site names")
        for (site in known) {
            val axes = backendSites.getValue(site.wireName)
            // A site that proves nothing names no axis; ours keeps the axis it gates, with no code on it.
            val expected = if (site.codes.isEmpty()) emptyMap() else mapOf(checkNotNull(site.source).wireName to site.codes)
            assertEquals(expected, axes, site.wireName)
        }
    }

    @Test
    fun every_decisive_code_belongs_to_exactly_one_site() {
        // Both directions, so neither copy can drift alone: a decisive code on no site would never be proven
        // on a deal that names its sites, and a site code outside the decisive set would never be proven on
        // one that names none. Checked against ours and against the backend's.
        for (source in TradeStatusSource.entries) {
            for (code in 0..20) {
                val expected = if (DecisiveTransitions.isDecisive(source, code)) 1 else 0
                val sites = known.filter { it.source == source && code in it.codes }
                assertEquals(expected, sites.size, "$source $code is on $sites")
                val backend = backendSites.filterValues { code in it[source.wireName].orEmpty() }.keys
                assertEquals(expected, backend.size, "$source $code is on the backend's $backend")
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
