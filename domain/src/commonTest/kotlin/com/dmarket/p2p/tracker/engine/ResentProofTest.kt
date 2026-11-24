package com.dmarket.p2p.tracker.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class ResentProofTest {
    private val replayed = "this presentation was already accepted for this deal"
    private val wrongPhase = "a deal in AWAITING_TERMINAL is not waiting on a provable transition"

    @Test
    fun the_text_decides_first_then_the_deal_and_anything_else_is_a_refusal() {
        val cases = listOf(
            Triple(true, replayed, true), // p2p's word about these bytes outranks the deal's one flag
            Triple(null, replayed, true),
            Triple(null, "THIS PRESENTATION WAS ALREADY ACCEPTED", true),
            Triple(false, wrongPhase, true), // the deal no longer waits on this side
            Triple(false, null, true),
            Triple(true, wrongPhase, false), // the deal still waits: a refusal (wrong_phase stays one until p2p says more)
            Triple(true, null, false),
            Triple(null, wrongPhase, false), // no deal read and no word from p2p: a refusal
            Triple(null, null, false),
        )
        for ((needsStatusUpdate, reason, landed) in cases) {
            assertEquals(landed, ResentProof.landed(needsStatusUpdate, reason), "needsStatusUpdate=$needsStatusUpdate, reason=$reason")
        }
    }
}
