package com.dmarket.p2p.tracker.engine

import com.dmarket.p2p.tracker.engine.DmarketImpersonation.damerauLevenshtein
import com.dmarket.p2p.tracker.engine.DmarketImpersonation.impersonates
import com.dmarket.p2p.tracker.engine.DmarketImpersonation.normalizeName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DmarketImpersonationTest {
    @Test
    fun normalize_name_folds_separators_leet_look_alikes_and_diacritics() {
        mapOf(
            "D market" to "dmarket",
            "D.M4RK3T_" to "dmarket",
            "Dm\u0430rket" to "dmarket", // Cyrillic а
            "\uFF24\uFF2D\uFF21\uFF32\uFF2B\uFF25\uFF34" to "dmarket", // full-width
            "Dm\u00E1rket" to "dmarket",
            "Dma\u0301rket" to "dmarket", // combining acute
            "D\u200Bmarket" to "dmarket", // zero-width space
            "DMARKET | Support" to "dmarketsupport",
            "\$teamUser" to "steamuser",
        ).forEach { (name, expected) -> assertEquals(expected, normalizeName(name), name) }
    }

    @Test
    fun damerau_levenshtein_counts_an_adjacent_swap_as_one_edit() {
        assertEquals(0, damerauLevenshtein("dmarket", "dmarket"))
        assertEquals(1, damerauLevenshtein("dmraket", "dmarket"))
        assertEquals(1, damerauLevenshtein("dmarke", "dmarket"))
        assertEquals(2, damerauLevenshtein("darkest", "dmarket"))
        assertEquals(3, damerauLevenshtein("", "abc"))
    }

    @Test
    fun impersonates_brand_variants_but_not_ordinary_names() {
        listOf(
            "D market", "D.M4RK3T", "Dm\u0430rket", "dmraket", "DMarket Support", "dmarketbot", "DMarket_P2P",
            // DMarket's own bots go by "DM Bot"; a lookalike of one is the same lie.
            "DM Bot", "DM-B0T", "dm_bot #12", "DM \u0412ot", "DMBot Support",
        )
            .forEach { assertTrue(impersonates(it), it) }
        listOf("Darkest", "market", "D", "", "Dmitry", "mark et", "DM", "Bot", "DM Bert", "DJ Bot").forEach {
            assertFalse(impersonates(it), it)
        }
    }
}
