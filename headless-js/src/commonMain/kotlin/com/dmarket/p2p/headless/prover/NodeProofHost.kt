package com.dmarket.p2p.headless.prover

import com.dmarket.p2p.tracker.model.LifecycleEvent
import com.dmarket.p2p.tracker.notary.ProofFailure
import com.dmarket.p2p.tracker.notary.ProofHost
import com.dmarket.p2p.tracker.port.host.EventObserver

/** A proof host that also hears when a new key or proxy may answer an earlier failure, and when to stop. */
interface NodeProofHost : ProofHost {
    fun keyChanged()

    /** The Steam proxy proofs were held back for is out of the way (the host pushed another, or none): they may start. */
    fun steamProxyChanged()

    /**
     * The same Steam proxy got the tracker's reads through again ([LifecycleEvent.SteamProxyRecovered]): proofs held
     * back for it may start, once per run of failures (a read's tunnel is not the prover's).
     */
    fun steamProxyRecovered()

    fun close()
}

/** Builds the proof host, given where its outcomes go (`null`: proved). */
fun interface NodeProofHostFactory {
    fun create(onOutcome: (dealId: String, failure: ProofFailure?) -> Unit): NodeProofHost
}

/**
 * [observer], with this host told first when the tracker reports its Steam proxy back
 * ([LifecycleEvent.SteamProxyRecovered]): proofs held back for the outage start with the next cycle, not after the
 * rest of their backoff, which may be minutes; once per run of failures ([ProverBackoff.proxyRecovered]).
 */
fun NodeProofHost.hearingSteamProxyRecovery(observer: EventObserver): EventObserver = EventObserver { event ->
    if (event == LifecycleEvent.SteamProxyRecovered) steamProxyRecovered()
    observer.onEvent(event)
}
