package com.dmarket.p2p.tracker.engine

/**
 * The **transient offer states** that warrant an expedited poll cadence: right now just `9`
 * (`CreatedNeedsConfirmation`) — the offer was created and is waiting for the **seller** to confirm on
 * the Steam mobile app, so the next meaningful transition (9 → `2 Active`, a decisive one; see
 * [DecisiveTransitions]) is expected within seconds-to-minutes. While a watched deal is here, the loop
 * polls at [com.dmarket.p2p.tracker.policy.PollClass.ExpeditedOffer] instead of the 3-min baseline.
 *
 * This is deliberately narrow — it does NOT include `2 Active` ("waiting for the *buyer*"), which can
 * sit for a long time and would keep the client fast-polling indefinitely. Offer axis only; the
 * history axis has no transient state a seller can hurry along.
 */
object ExpeditedTransitions {
    private val EXPEDITED_OFFER_CODES = setOf(9)

    /** Whether a raw Steam offer status code is a transient state warranting expedited polling. */
    fun isExpedited(offerCode: Int?): Boolean = offerCode != null && offerCode in EXPEDITED_OFFER_CODES

    /** Whether any observed deal is in a transient offer state (so the next wake should be expedited). */
    fun anyExpedited(observed: Collection<ObservedTrade>): Boolean = observed.any { isExpedited(it.offerState) }
}
