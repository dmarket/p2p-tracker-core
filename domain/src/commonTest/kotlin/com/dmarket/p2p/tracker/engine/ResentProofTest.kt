package com.dmarket.p2p.tracker.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class ResentProofTest {
    private val replayed = "this presentation was already accepted for this deal"
    private val wrongPhase = "a deal in AWAITING_TERMINAL is not waiting on a provable transition"

    @Test
    fun the_deal_decides_and_the_reason_only_when_the_deal_says_nothing() {
        val cases = listOf(
            Triple(false, wrongPhase, true), // the deal moved on: the first send landed, whatever the text
            Triple(false, null, true),
            Triple(true, replayed, false), // the deal still waits on this proof: a real refusal, whatever the text
            Triple(true, null, false),
            Triple(null, replayed, true), // no deal read: the text is the fallback
            Triple(null, "THIS PRESENTATION WAS ALREADY ACCEPTED", true),
            Triple(null, wrongPhase, false),
            Triple(null, null, false),
        )
        for ((needsStatusUpdate, reason, landed) in cases) {
            assertEquals(landed, ResentProof.landed(needsStatusUpdate, reason), "needsStatusUpdate=$needsStatusUpdate, reason=$reason")
        }
    }
}
