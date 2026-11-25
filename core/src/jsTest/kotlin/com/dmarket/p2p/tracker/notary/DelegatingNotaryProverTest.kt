package com.dmarket.p2p.tracker.notary

import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.port.notary.NotaryTokenProvider
import com.dmarket.p2p.tracker.support.fakeSteamCredential
import kotlinx.coroutines.test.runTest
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals

class DelegatingNotaryProverTest {
    @Test
    fun before_proof_runs_before_the_request_is_handed_over() = runTest {
        val order = mutableListOf<String>()
        val prover = DelegatingNotaryProver(
            maxConcurrency = 1,
            tokenProvider = NotaryTokenProvider { "dm-token" },
            delegate = { _, _, _ ->
                order += "delegate"
                Promise.resolve("presentation")
            },
            beforeProof = { order += "mint" },
        )

        val submission = prover.proveRead(
            ProvenReadBinding(dealId = DealId("d1"), steamOfferId = OfferId("555")),
            ProvenReadKind.TRADE_OFFER,
            fakeSteamCredential(),
        )

        assertEquals(listOf("mint", "delegate"), order)
        assertEquals("presentation", submission.proofPayload)
    }
}
