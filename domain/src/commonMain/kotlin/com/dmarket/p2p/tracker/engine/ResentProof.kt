package com.dmarket.p2p.tracker.engine

/**
 * Whether a proof sent again with the same bytes, after an answer that was no verdict (a 5xx, a timeout), had landed
 * the first time (contract `mobile-tracker.md` §6.3, DMA-1016).
 *
 * p2p answers accepted bytes sent again with `verified: false` and no code, so a resend's refusal is read off the deal
 * (`GET /p2p/deals/{id}`, read as this side): [needsStatusUpdate] `false` means the deal no longer waits on this side's
 * proof, so the first send landed; `true` means it still waits, so the refusal is real. The refusal's text decides
 * only when the deal could not be read or did not carry the field. A first send's `verified: false` never comes here.
 */
object ResentProof {
    /** What p2p tells accepted bytes sent again: "this presentation was already accepted for this deal". */
    const val ALREADY_ACCEPTED = "already accepted"

    fun landed(needsStatusUpdate: Boolean?, reason: String?): Boolean =
        needsStatusUpdate?.not() ?: (reason?.contains(ALREADY_ACCEPTED, ignoreCase = true) == true)
}
