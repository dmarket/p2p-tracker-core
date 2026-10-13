package com.dmarket.p2p.tracker.model.marketplace

/**
 * A place the backend enforces a TLSN proof at, and the Steam codes that reach it: the backend's own rows,
 * one to one. A code on an enforced site is refused unproven; a code whose site the deal does not enforce
 * is an ordinary raw report.
 *
 * [OFFER_CREATED] proves no code: that site gates only how far an unsigned report may move the deal, and
 * offer `2` belongs to [OFFER_CONFIRMED]. Every other (axis, code) pair belongs to exactly one site, which
 * is what lets [forTransition] answer with a single site.
 *
 * The union of the codes here *is* the client's decisive set
 * ([com.dmarket.p2p.tracker.engine.DecisiveTransitions]), so a site and its codes are stated once.
 *
 * @property wireName the site's name in `TrackedDeal.proofSites`. The backend never renames a site.
 * @property source the axis the codes are read on; `null` only for [UNKNOWN].
 * @property codes the raw Steam codes on [source] that reach this site.
 */
enum class ProofSite(val wireName: String, val source: TradeStatusSource?, val codes: Set<Int>) {
    OFFER_CREATED("offer_created", TradeStatusSource.OFFER, emptySet()),
    OFFER_CONFIRMED("offer_confirmed", TradeStatusSource.OFFER, setOf(2)),
    OFFER_ACCEPTED("offer_accepted", TradeStatusSource.OFFER, setOf(3)),
    OFFER_DECLINED("offer_declined", TradeStatusSource.OFFER, setOf(4, 7)),
    OFFER_CANCELLED("offer_cancelled", TradeStatusSource.OFFER, setOf(6, 8, 10)),
    TRADE_COMPLETED("trade_completed", TradeStatusSource.HISTORY, setOf(3, 9)),
    TRADE_REVERSED("trade_reversed", TradeStatusSource.HISTORY, setOf(4, 5, 6, 7, 11, 12)),

    /**
     * A name this build does not know. Its presence makes the deal prove the full decisive set (see
     * [TrackedDeal.provesTransition]): a new site may gate a code this build already proves, and guessing
     * that it does not would report a code the backend refuses unproven.
     */
    UNKNOWN("", null, emptySet()),
    ;

    companion object {
        fun fromWire(name: String): ProofSite {
            val token = name.trim().lowercase()
            return entries.firstOrNull { it != UNKNOWN && it.wireName == token } ?: UNKNOWN
        }

        /** The site a raw Steam [code] on [source] reaches, or `null` when no site proves it. */
        fun forTransition(source: TradeStatusSource, code: Int): ProofSite? =
            entries.firstOrNull { it.source == source && code in it.codes }
    }
}
