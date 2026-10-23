package com.dmarket.p2p.sdk.prover

import com.dmarket.p2p.headless.prover.ProverSettings
import com.dmarket.p2p.tracker.config.NotaryConfig
import com.dmarket.p2p.tracker.notary.HostedNotaryProver
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How the SDK proves: the notary, and the prover processes' limits. Part of the config file (`"prover"`).
 *
 * @property enabled `false` makes no proof at all: a deal that requires one is reported unproven.
 * @property notaryUrl the notary's WebSocket URL; its path is what each upgrade signs.
 * @property maxConcurrency proofs at once, 1 or 2; each holds a process of up to ~700 MB.
 * @property proofsPerChild proofs one prover process serves before it is ended, 1 to 5.
 * @property threadCount the prover's MPC threads per process.
 * @property peakMemoryMb one proof's memory peak, for the start-up check against the container's limit.
 * @property assetsDir where the prover's `pkg/` and `transport/` are; next to the SDK's script by default, else
 *   the installed `@dmarket/steam-provenance` package.
 */
@Serializable
data class ProverConfig(
    @SerialName("enabled") val enabled: Boolean = true,
    @SerialName("notaryUrl") val notaryUrl: String = NotaryConfig.PRODUCTION_NOTARY_URL,
    @SerialName("maxConcurrency") val maxConcurrency: Int = 1,
    @SerialName("proofsPerChild") val proofsPerChild: Int = 1,
    @SerialName("threadCount") val threadCount: Int = DEFAULT_THREADS,
    @SerialName("peakMemoryMb") val peakMemoryMb: Int = DEFAULT_PEAK_MB,
    @SerialName("assetsDir") val assetsDir: String? = null,
) {
    init {
        require(notaryUrl.startsWith("wss://") || notaryUrl.startsWith("ws://")) { "prover.notaryUrl must be a ws:// or wss:// URL" }
        require(maxConcurrency in 1..HostedNotaryProver.MAX_CONCURRENCY) {
            "prover.maxConcurrency must be 1..${HostedNotaryProver.MAX_CONCURRENCY}"
        }
        require(proofsPerChild in 1..MAX_PROOFS_PER_CHILD) { "prover.proofsPerChild must be 1..$MAX_PROOFS_PER_CHILD" }
        require(threadCount in 1..MAX_THREADS) { "prover.threadCount must be 1..$MAX_THREADS" }
        require(peakMemoryMb > 0) { "prover.peakMemoryMb must be positive" }
    }

    val peakMemoryBytes: Long get() = peakMemoryMb.toLong() * 1024L * 1024L

    companion object {
        const val DEFAULT_THREADS: Int = ProverSettings.DEFAULT_THREADS
        const val MAX_THREADS: Int = ProverSettings.MAX_THREADS
        const val MAX_PROOFS_PER_CHILD: Int = ProverSettings.MAX_PROOFS_PER_CHILD

        /**
         * One prover process's peak, with the tracker's bundle loaded: 525–570 MB measured on a local notary in a
         * `node:22` container (linux/arm64), plus headroom. A 512 MB container cannot hold it beside the SDK; a
         * 768 MB one can.
         */
        const val DEFAULT_PEAK_MB: Int = 640
    }
}
