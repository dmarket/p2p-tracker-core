package com.dmarket.p2p.tracker.engine

/**
 * Whether a proof sent again with the same bytes, after an answer that was no verdict (a 5xx, a timeout), had landed
 * the first time (contract `mobile-tracker.md` §6.3, DMA-1016). A first send's `verified: false` never comes here.
 *
 * p2p answers accepted bytes sent again with `verified: false` and no code. In order:
 * 1. the refusal's text says "already accepted": p2p's own word about these bytes, so they landed;
 * 2. else [needsStatusUpdate] `false` (the deal, read as this side, no longer waits on its proof): they landed;
 * 3. else (`true`, absent, or the deal could not be read): a refusal. `true` alone does not decide, because it is one
 *    flag per deal and the bytes are per transition: another proof of the same deal may be the one it waits for.
 *
 * Known gaps. Bytes that landed and moved the deal on are answered "a deal in <State> is not waiting on a provable
 * transition" (p2p checks the phase first); the text does not say so, and on a deal still flagged that stays a
 * refusal until p2p answers it otherwise. And `needsStatusUpdate` is `false` on a seller's offer proof in the first two
 * thirds of its create window, so a real refusal of bytes that never landed reads as landed there (unlikely).
 */
object ResentProof {
    /** What p2p tells accepted bytes sent again: "this presentation was already accepted for this deal". */
    const val ALREADY_ACCEPTED = "already accepted"

    fun landed(needsStatusUpdate: Boolean?, reason: String?): Boolean =
        reason?.contains(ALREADY_ACCEPTED, ignoreCase = true) == true || needsStatusUpdate == false
}
