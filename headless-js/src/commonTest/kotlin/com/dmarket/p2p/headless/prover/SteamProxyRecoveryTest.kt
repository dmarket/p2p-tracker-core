package com.dmarket.p2p.headless.prover

import com.dmarket.p2p.headless.T0
import com.dmarket.p2p.tracker.model.LifecycleEvent
import com.dmarket.p2p.tracker.notary.HostedProofOutcome
import com.dmarket.p2p.tracker.notary.HostedProofRequest
import com.dmarket.p2p.tracker.notary.NotaryUpgradeSigner
import com.dmarket.p2p.tracker.notary.ProofFailure
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The tracker's word that its Steam proxy is back reaches the proof host, so proofs held back for it start again. */
class SteamProxyRecoveryTest {
    /** A proof host over a real [ProverBackoff], recording what it heard in [heard]. */
    private class BackoffHost(val backoff: ProverBackoff, val heard: MutableList<Any>) : NodeProofHost {
        override suspend fun prove(proof: HostedProofRequest, upgrade: NotaryUpgradeSigner): HostedProofOutcome = error("not proving")

        override fun keyChanged() = backoff.keyChanged()

        override fun steamProxyMayAnswer() {
            heard += "proof host"
            backoff.proxyMayAnswer()
        }

        override fun close() = Unit
    }

    @Test
    fun the_proxy_back_lifts_the_proofs_wait_before_the_host_hears_it_and_nothing_else_does() = runTest {
        val heard = mutableListOf<Any>()
        val backoff = ProverBackoff().apply { failed(ProofFailure.ProxyUnreachable("could not connect to the proxy: ECONNREFUSED"), T0) }
        val observer = BackoffHost(backoff, heard).hearingSteamProxyRecovery { heard += it }
        val down = LifecycleEvent.SteamProxyUnreachable(listOf("deal-1"), "could not connect to the proxy: ECONNREFUSED")

        observer.onEvent(down)
        observer.onEvent(LifecycleEvent.CycleStarted)
        assertNotNull(backoff.blocked(T0), "only the proxy's return lifts the wait")

        observer.onEvent(LifecycleEvent.SteamProxyRecovered)

        assertNull(backoff.blocked(T0), "a proof may start with the next cycle")
        assertEquals(listOf(down, LifecycleEvent.CycleStarted, "proof host", LifecycleEvent.SteamProxyRecovered), heard)
    }

    @Test
    fun the_proxy_back_leaves_a_notarys_wait_alone() = runTest {
        val backoff = ProverBackoff().apply { failed(ProofFailure.NotaryUnreachable("ECONNREFUSED"), T0) }

        BackoffHost(backoff, mutableListOf()).hearingSteamProxyRecovery { }.onEvent(LifecycleEvent.SteamProxyRecovered)

        assertNotNull(backoff.blocked(T0), "a notary that is down is not the proxy's to clear")
    }
}
