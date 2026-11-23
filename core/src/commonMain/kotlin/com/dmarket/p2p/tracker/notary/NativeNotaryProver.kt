package com.dmarket.p2p.tracker.notary

import com.dmarket.p2p.tracker.config.NotaryConfig
import com.dmarket.p2p.tracker.game.Cs2GameAdapter
import com.dmarket.p2p.tracker.game.GameAdapter
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.marketplace.ProofSubmission
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.port.host.Clock
import com.dmarket.p2p.tracker.port.notary.NoSteamProofCookieSource
import com.dmarket.p2p.tracker.port.notary.NotaryProver
import com.dmarket.p2p.tracker.port.notary.NotaryTokenProvider
import com.dmarket.p2p.tracker.port.notary.SteamProofCookieSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The reason of a proof the user cancelled ([NativeNotaryProver.cancel]); a host tells it from a failure by it. */
const val PROOF_CANCELLED_REASON: String = "the proof was cancelled on the device"

/** A native proof's deadline: the extension's `NOTARY_PROOF_TIMEOUT_MS`. */
val NATIVE_PROOF_DEADLINE: Duration = 180.seconds

/** One running proof, for a progress screen (T-14): the prover's stage (0–6), its advisory fraction and its traffic. */
data class ProofProgress(
    val stageIndex: Int = 0,
    val stage: String? = null,
    val fraction: Float = 0f,
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
)

/** Bytes the native prover moved since it was built, notary and Steam together, for a traffic counter. */
data class ProofTraffic(val bytesSent: Long = 0, val bytesReceived: Long = 0)

/**
 * The mobile [NotaryProver]: the steam-provenance C ABI through a platform's [engine] (Android: JNI, OkHttp's
 * WebSocket to the notary, TCP to Steam). Everything but the connections and the bridge calls is here, so it is the
 * same on every platform and tested with a fake engine.
 *
 * Per proof, in order, each a [ProofFailure] thrown as [HostedProofException] (the loop's `ProofFailed` reason is
 * its summary, so a refusal naming its online budget still teaches the loop):
 *  - a kind outside [NotaryConfig.enabledReads], Worker egress, or no native library for this device:
 *    [ProofFailure.NotStarted];
 *  - at most [maxConcurrency] proofs run at once; a further one waits for a permit;
 *  - once it holds the permit, a Steam token with less than [minTokenLifetime] left:
 *    [ProofFailure.NotStarted] with [PROOF_TOKEN_EXPIRING_REASON], as in [HostedNotaryProver];
 *  - no DMarket session for the notary, or a read whose credentials are missing: [ProofFailure.NotStarted];
 *  - the issuance, over [NotaryConfig.mode] (`mpc`: notary + Steam connections; `proxy`: the notary only), within
 *    [deadline]: past it, [ProofFailure.ProverKilled] with [KillCause.SESSION_DEADLINE];
 *  - its outcome through [ProofFailures.classify]: a refused upgrade, an unreachable notary, a version mismatch, a
 *    closed session, a failed or non-2xx Steam read, exceeded limits.
 *
 * [cancel] ends a running proof with [PROOF_CANCELLED_REASON] (a failure, so the loop retries it later); cancelling
 * the caller's coroutine cancels the issuance and propagates. [progress] and [traffic] are what a progress screen
 * and a traffic counter read.
 */
class NativeNotaryProver(
    private val config: NotaryConfig,
    private val tokenProvider: NotaryTokenProvider,
    private val engine: NativeProverEngine,
    private val clock: Clock,
    private val minTokenLifetime: Duration = Duration.ZERO,
    private val deadline: Duration = NATIVE_PROOF_DEADLINE,
    private val adapter: GameAdapter = Cs2GameAdapter(),
    private val cookies: SteamProofCookieSource = NoSteamProofCookieSource,
) : NotaryProver {
    override val maxConcurrency: Int = config.maxConcurrency

    /** Stated, not inherited: a real prover runs here. */
    override val id: String = "tlsn"

    private val mapper = SteamProofReadMapper(config)
    private val gate = Semaphore(maxConcurrency)
    private val running = MutableStateFlow<Map<DealId, Running>>(emptyMap())
    private val mutableProgress = MutableStateFlow<Map<DealId, ProofProgress>>(emptyMap())
    private val mutableTraffic = MutableStateFlow(ProofTraffic())

    /** The proofs issuing now, by deal; an entry goes when its proof ends. */
    val progress: StateFlow<Map<DealId, ProofProgress>> = mutableProgress.asStateFlow()

    /** Bytes sent and received by every proof since this prover was built. */
    val traffic: StateFlow<ProofTraffic> = mutableTraffic.asStateFlow()

    /** Cancels [dealId]'s running proof; it ends with [PROOF_CANCELLED_REASON]. Whether one was running. */
    fun cancel(dealId: DealId): Boolean {
        val run = running.value[dealId] ?: return false
        run.cancelledByUser = true
        run.issue.cancel()
        return true
    }

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun proveRead(binding: ProvenReadBinding, kind: ProvenReadKind, credential: SteamCredential): ProofSubmission {
        if (kind !in config.enabledReads) notStarted("$kind is not in NotaryConfig.enabledReads, so no proof may be spent on it")
        if (config.transport != "direct") notStarted("the native prover has no Worker egress transport")
        engine.unavailableReason()?.let(::notStarted)
        return gate.withPermit {
            // Checked once the permit is held: a proof that queued behind another one is older by then.
            if (credential.expiresAt - clock.now() < minTokenLifetime) notStarted(PROOF_TOKEN_EXPIRING_REASON)
            val request = try {
                val subject = credential.subjectSteamId
                prepareProveRequest(config, tokenProvider, mapper, adapter, cookies, binding, kind, subject, credential.token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                notStarted(e.message ?: e::class.simpleName ?: "the proof request could not be built")
            }
            val mode = config.issuanceMode
            val issuance = NativeIssuance(
                mode = mode,
                notaryUrl = request.notaryUrl,
                notaryToken = request.notaryToken,
                subprotocol = config.subprotocol,
                targetHost = request.spec.serverName,
                config = nativeConfigJson(mode, request),
                body = request.proverHttpRequest().body?.encodeToByteArray(),
                policy = nativePolicyJson(request.spec.proverRevealPolicy()),
                deadline = deadline,
            )
            val outcome = issue(binding.dealId, issuance)
            val presentation = outcome.presentation
            val failure = outcome.failure()
            if (failure != null || presentation == null) {
                throw HostedProofException(failure ?: ProofFailure.Other("the native prover returned no presentation"))
            }
            ProofSubmission(dealId = binding.dealId, proofPayload = Base64.encode(presentation))
        }
    }

    /** Runs [issuance] as [dealId]'s proof, publishing its progress; a deadline or [cancel] ends as a failed outcome. */
    private suspend fun issue(dealId: DealId, issuance: NativeIssuance): NativeIssuanceOutcome {
        mutableProgress.update { it + (dealId to ProofProgress()) }
        val listener = object : NativeIssuanceListener {
            override fun onStage(index: Int, stage: String, fraction: Float) {
                mutableProgress.update { all ->
                    all[dealId]?.let {
                        all +
                            (dealId to it.copy(stageIndex = index, stage = stage, fraction = fraction))
                    }
                        ?: all
                }
            }

            override fun onBytes(sent: Long, received: Long) {
                mutableTraffic.update { ProofTraffic(it.bytesSent + sent, it.bytesReceived + received) }
                mutableProgress.update { all ->
                    all[dealId]?.let {
                        all +
                            (dealId to it.copy(bytesSent = it.bytesSent + sent, bytesReceived = it.bytesReceived + received))
                    }
                        ?: all
                }
            }
        }
        return try {
            coroutineScope {
                val issue = async { withTimeoutOrNull(issuance.deadline) { engine.issue(issuance, listener) } }
                val run = Running(issue)
                running.update { it + (dealId to run) }
                try {
                    issue.await()
                        ?: NativeIssuanceOutcome(status = NativeIssuanceOutcome.STATUS_TIMEOUT, diagnostic = "${issuance.deadline}")
                } catch (e: CancellationException) {
                    // The caller's own cancellation propagates; only a cancel() of this proof is an outcome.
                    currentCoroutineContext().ensureActive()
                    if (!run.cancelledByUser) throw e
                    NativeIssuanceOutcome(status = NativeIssuanceOutcome.STATUS_CANCELLED, diagnostic = PROOF_CANCELLED_REASON)
                }
            }
        } finally {
            running.update { it - dealId }
            mutableProgress.update { it - dealId }
        }
    }

    private class Running(val issue: Deferred<NativeIssuanceOutcome?>) {
        @Volatile var cancelledByUser = false
    }

    private fun notStarted(reason: String): Nothing = throw HostedProofException(ProofFailure.NotStarted(reason))
}

/** The failure [this] outcome is, or `null` for a presentation of a 2xx Steam read. */
internal fun NativeIssuanceOutcome.failure(): ProofFailure? = when (status) {
    NativeIssuanceOutcome.STATUS_TIMEOUT -> ProofFailure.ProverKilled(KillCause.SESSION_DEADLINE, diagnostic)
    NativeIssuanceOutcome.STATUS_CANCELLED -> ProofFailure.Other(PROOF_CANCELLED_REASON)
    else -> ProofFailures.classify(
        upgradeStatus = upgradeStatus,
        closeCode = closeCode,
        closeReason = closeReason,
        targetStatus = targetStatus,
        targetError = targetError,
        notaryConnected = notaryConnected,
        message = if (presentation == null) diagnostic ?: "the native prover failed (status $status)" else null,
    )
}
