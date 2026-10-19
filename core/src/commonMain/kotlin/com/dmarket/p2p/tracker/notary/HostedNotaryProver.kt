package com.dmarket.p2p.tracker.notary

import com.dmarket.p2p.tracker.model.marketplace.ProofSubmission
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.port.host.Clock
import com.dmarket.p2p.tracker.port.notary.NotaryProver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.time.Duration

/**
 * The prover's reason when the Steam token has too little life left to start a proof. A host that maps the loop's
 * `ProofFailed` events to problems reads it to tell this deferral from a failure.
 */
const val PROOF_TOKEN_EXPIRING_REASON: String = "the Steam token is too close to its expiry to start a proof"

/**
 * A [NotaryProver] for a host that runs the prover itself: each proof goes to [host] with the Steam token and a
 * way to sign the notary upgrade ([upgrade]), and comes back as a presentation or a [ProofFailure].
 *
 * The extension's [DelegatingNotaryProver] does the same across an MV3 boundary with a DMarket session token. A
 * headless host has none: the notary authenticates the upgrade by headers signed with the Trading API key, and
 * the key stays with the tracker. The host asks [upgrade] right before its prover connects, so a slow start does
 * not use up the signature's window, and a retried connect gets a fresh signature.
 *
 * Two rules on top of the host's:
 *  - no proof starts on a token with less than [minTokenLifetime] left, the same bar a create meets: the proof
 *    fails at once with [ProofFailure.NotStarted] and [PROOF_TOKEN_EXPIRING_REASON], before any process starts;
 *  - at most [maxConcurrency] proofs run at once, each holding a process and up to ~700 MB.
 *
 * A failure is thrown as [HostedProofException], so the loop sees it like any prover's: a `ProofFailed` event
 * whose reason is [ProofFailure.summary], an unproven report, a retry on a later cycle. The summary keeps the
 * prover's own text, so a refusal that names the online budget it needed still teaches the loop that budget.
 *
 * @param steamSessionId the `sessionid` value the tracker's Steam writes send; handed to the host for
 *   cookie-authenticated reads.
 */
class HostedNotaryProver(
    private val host: ProofHost,
    private val upgrade: NotaryUpgradeSigner,
    private val clock: Clock,
    private val steamSessionId: String,
    override val maxConcurrency: Int = 1,
    private val minTokenLifetime: Duration = Duration.ZERO,
) : NotaryProver {
    init {
        require(maxConcurrency in 1..MAX_CONCURRENCY) { "maxConcurrency must be 1..$MAX_CONCURRENCY, was $maxConcurrency" }
    }

    /** Stated, not inherited: a real prover runs behind the host. */
    override val id: String = "tlsn"

    private val gate = Semaphore(maxConcurrency)

    override suspend fun proveRead(binding: ProvenReadBinding, kind: ProvenReadKind, credential: SteamCredential): ProofSubmission {
        val request = notaryProofRequest(binding, kind, credential.subjectSteamId)
        return gate.withPermit {
            // Checked once the permit is held: a proof that queued behind another one is older by then.
            if (credential.expiresAt - clock.now() < minTokenLifetime) {
                throw HostedProofException(ProofFailure.NotStarted(PROOF_TOKEN_EXPIRING_REASON))
            }
            val outcome = try {
                host.prove(HostedProofRequest(request, credential.token, steamSessionId), upgrade)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                HostedProofOutcome.Failed(ProofFailure.Other(e.message ?: e::class.simpleName ?: "the proof host failed"))
            }
            when (outcome) {
                is HostedProofOutcome.Proved -> ProofSubmission(dealId = binding.dealId, proofPayload = outcome.presentation)
                is HostedProofOutcome.Failed -> throw HostedProofException(outcome.failure)
            }
        }
    }

    companion object {
        /** A host runs up to two proofs at once; each one is a prover process with its own memory peak. */
        const val MAX_CONCURRENCY: Int = 2
    }
}
