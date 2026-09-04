package com.dmarket.p2p.tracker.adapter.notary

import com.dmarket.p2p.tracker.config.NotaryConfig
import com.dmarket.p2p.tracker.model.marketplace.ProofSubmission
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.notary.ProvenReadBinding
import com.dmarket.p2p.tracker.notary.ProvenReadKind
import com.dmarket.p2p.tracker.port.notary.NotaryProver

/**
 * The default [NotaryProver]: no real TLSN proof generation.
 *
 * [proveRead] returns a **stub** [ProofSubmission] with an empty payload — clearly not a valid TLSN
 * presentation — so the deal flow runs end-to-end against the backend's MVP mock verify while a production
 * backend with real verification rejects it rather than settling on a forged proof.
 *
 * Answers for every [ProvenReadKind] rather than only the trade axes, and does so without performing
 * anything: a stub for a write kind is a stub, not a Steam write. That is the correct behaviour for the
 * shipping default — the write only happens once a real prover is selected.
 */
object NoOpNotaryProver : NotaryProver {
    // The constant, not `NotaryConfig().maxConcurrency`: this object is the default `notary` for both the loop
    // and the core façade, so it initializes on every service-worker spawn — and constructing a whole config
    // graph to read one Int made that spawn pay for two `ProvenRead` validations and a registry it discarded.
    override val maxConcurrency: Int = NotaryConfig.DEFAULT_MAX_CONCURRENCY

    /** Rides every `ProofSubmitted` so "rejected because it was never a proof" is legible in the log. */
    override val id: String = "noop"

    override suspend fun proveRead(binding: ProvenReadBinding, kind: ProvenReadKind, credential: SteamCredential): ProofSubmission =
        ProofSubmission(dealId = binding.dealId, proofPayload = "")
}
