package com.dmarket.p2p.headless.prover

import com.dmarket.p2p.tracker.notary.ProofFailure
import com.dmarket.p2p.tracker.notary.ProofHost

/** A proof host that also hears when a new key or proxy may answer an earlier failure, and when to stop. */
interface NodeProofHost : ProofHost {
    fun keyChanged()

    /** The host pushed another Steam proxy, or none: proofs held back for the old one may start. */
    fun proxyChanged()

    fun close()
}

/** Builds the proof host, given where its outcomes go (`null`: proved). */
fun interface NodeProofHostFactory {
    fun create(onOutcome: (dealId: String, failure: ProofFailure?) -> Unit): NodeProofHost
}
