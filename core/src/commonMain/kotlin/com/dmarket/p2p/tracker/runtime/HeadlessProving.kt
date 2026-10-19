package com.dmarket.p2p.tracker.runtime

import com.dmarket.p2p.tracker.notary.HostedNotaryProver
import com.dmarket.p2p.tracker.notary.ProofHost

/**
 * How a [HeadlessTracker] proves: [host] runs each proof (a Node host forks a prover process per proof), at most
 * [maxConcurrency] at once. Each proof's notary upgrade is signed with the tracker's Trading API key when the host
 * asks for it, and no proof starts on a Steam token with less than [HeadlessTracker.MIN_WRITE_TOKEN_LIFETIME]
 * left. See [HostedNotaryProver].
 *
 * @property maxConcurrency proofs in flight at once: 1 by default, 2 at most. One proof peaks at up to ~700 MB.
 */
class HeadlessProving(val host: ProofHost, val maxConcurrency: Int = 1) {
    init {
        require(maxConcurrency in 1..HostedNotaryProver.MAX_CONCURRENCY) {
            "maxConcurrency must be 1..${HostedNotaryProver.MAX_CONCURRENCY}, was $maxConcurrency"
        }
    }
}
