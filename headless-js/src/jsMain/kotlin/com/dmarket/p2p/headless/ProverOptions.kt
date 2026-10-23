package com.dmarket.p2p.headless

import com.dmarket.p2p.headless.prover.ProverLimits
import com.dmarket.p2p.headless.prover.ProverSettings
import com.dmarket.p2p.tracker.notary.HostedNotaryProver
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The `prover` option of [createHeadlessTracker]: with it the tracker proves, each proof in a prover process of its
 * own; without it, it makes none.
 *
 * Like every option, a refusal names the field and never its value.
 *
 * @property notaryUrl the notary proofs go to, and the one each upgrade is signed for; the tracker's `notaryUrl`
 *   when unset.
 * @property maxConcurrency proofs at once, 1 or 2; each holds a process of up to ~700 MB.
 * @property proofsPerChild proofs one prover process serves before it is ended, 1 to 5.
 * @property threadCount the prover's MPC threads per process, 1 to 16.
 * @property assetsDir where the prover's `pkg/` and `transport/` are; the package's own directory when unset.
 * @property connectDeadline from a proof's start to its process asking for the upgrade headers.
 * @property sessionDeadline from that ask to the proof's answer.
 */
internal class ProverOptions(
    val notaryUrl: String?,
    val maxConcurrency: Int,
    val proofsPerChild: Int,
    val threadCount: Int,
    val assetsDir: String?,
    val connectDeadline: Duration,
    val sessionDeadline: Duration,
) {
    companion object {
        private val KNOWN = setOf(
            "notaryUrl",
            "maxConcurrency",
            "proofsPerChild",
            "threadCount",
            "assetsDir",
            "connectDeadlineMs",
            "sessionDeadlineMs",
        )
        private val DEFAULTS = ProverLimits()

        fun parse(prover: dynamic): ProverOptions {
            require(!(js("Array.isArray")(prover) as Boolean)) { "prover must be an object" }
            HeadlessOptions.refuseUnknown(prover, KNOWN, prefix = "prover.")
            val notaryUrl = HeadlessOptions.optional<String>(prover.notaryUrl, "prover.notaryUrl", "string")
            require(notaryUrl == null || notaryUrl.startsWith("wss://") || notaryUrl.startsWith("ws://")) {
                "prover.notaryUrl must be a ws:// or wss:// URL"
            }
            val assetsDir = HeadlessOptions.optional<String>(prover.assetsDir, "prover.assetsDir", "string")
            require(assetsDir == null || assetsDir.isNotBlank()) { "prover.assetsDir must be a non-empty string" }
            return ProverOptions(
                notaryUrl = notaryUrl,
                maxConcurrency = whole(prover.maxConcurrency, "prover.maxConcurrency", 1..HostedNotaryProver.MAX_CONCURRENCY) ?: 1,
                proofsPerChild = whole(prover.proofsPerChild, "prover.proofsPerChild", 1..ProverSettings.MAX_PROOFS_PER_CHILD) ?: 1,
                threadCount = whole(prover.threadCount, "prover.threadCount", 1..ProverSettings.MAX_THREADS)
                    ?: ProverSettings.DEFAULT_THREADS,
                assetsDir = assetsDir,
                connectDeadline = milliseconds(prover.connectDeadlineMs, "prover.connectDeadlineMs") ?: DEFAULTS.connectDeadline,
                sessionDeadline = milliseconds(prover.sessionDeadlineMs, "prover.sessionDeadlineMs") ?: DEFAULTS.sessionDeadline,
            )
        }

        /** A whole number in [range], or `null` when absent. */
        private fun whole(value: dynamic, name: String, range: IntRange): Int? {
            val number = HeadlessOptions.optional<Double>(value, name, "number") ?: return null
            require(js("Number.isInteger")(number) as Boolean && number.toInt() in range) {
                "$name must be a whole number in ${range.first}..${range.last}"
            }
            return number.toInt()
        }

        /** A positive whole number of milliseconds, or `null` when absent. */
        private fun milliseconds(value: dynamic, name: String): Duration? {
            val number = HeadlessOptions.optional<Double>(value, name, "number") ?: return null
            require(js("Number.isSafeInteger")(number) as Boolean && number > 0) { "$name must be a positive whole number" }
            return number.toLong().milliseconds
        }
    }
}
