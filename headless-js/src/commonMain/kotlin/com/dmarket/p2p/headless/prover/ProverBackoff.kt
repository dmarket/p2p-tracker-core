package com.dmarket.p2p.headless.prover

import com.dmarket.p2p.tracker.notary.ProofFailure
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * When the next proof may start, after the failures [waitAfter] makes wait. A proof that comes back, or a newly held
 * key, clears the wait.
 */
class ProverBackoff {
    private var notBefore: Instant? = null
    private var consecutive = 0
    private var reason: String? = null

    /** Why proofs wait, and until when; `null` when one may start at [now]. */
    fun blocked(now: Instant): String? {
        val until = notBefore ?: return null
        if (now >= until) return null
        val after = reason ?: "a refusal"
        return if (until ==
            Instant.DISTANT_FUTURE
        ) {
            "no proof until the SDK is updated, after $after"
        } else {
            "no proof until $until, after $after"
        }
    }

    fun failed(failure: ProofFailure, now: Instant) {
        val wait = waitAfter(failure, consecutive) ?: return
        consecutive++
        reason = failure.summary
        notBefore = if (wait == Duration.INFINITE) Instant.DISTANT_FUTURE else now + wait
    }

    fun succeeded() {
        notBefore = null
        consecutive = 0
        reason = null
    }

    /** A new key may answer the notary's 401: let the next proof try it. An update block stays. */
    fun keyChanged() {
        if (notBefore != Instant.DISTANT_FUTURE) succeeded()
    }

    companion object {
        /**
         * How long after [failure] the next proof must wait, given [consecutive] waiting failures in a row before it;
         * `null` for none. The notary's refusals follow its own table: a 5xx before the upgrade, a close 1013 after
         * it or no notary at all is the notary being busy or down; a 401 is the key or the clock; a 403 or a close
         * 4029 is admission; the protocol's version error only an update fixes. Anything else, a close 4008
         * included, is that proof's own failure and holds back nothing.
         */
        fun waitAfter(failure: ProofFailure, consecutive: Int): Duration? = when (failure) {
            is ProofFailure.UpgradeRefused -> when (failure.status) {
                // A refused key or clock waits like a busy notary: each retry costs one refused upgrade, and a push
                // or a clock fix may come at any time.
                UNAUTHORIZED, in SERVER_ERRORS -> backoff(consecutive)
                // 403: at the next intent, at most once a minute (the record lags a transition by seconds).
                FORBIDDEN -> ADMISSION_WAIT
                else -> null
            }
            is ProofFailure.SessionClosed -> when (failure.code) {
                TRY_AGAIN_LATER -> backoff(consecutive)
                // 4029: after the lease's 60 s expiry.
                AT_CAP -> ADMISSION_WAIT
                else -> null
            }
            is ProofFailure.NotaryUnreachable -> backoff(consecutive)
            is ProofFailure.VersionMismatch -> Duration.INFINITE
            else -> null
        }

        /** Busy or down: from 30 s, doubling to 5 minutes. */
        private fun backoff(consecutive: Int): Duration {
            var wait = BACKOFF_FIRST
            repeat(consecutive.coerceIn(0, MAX_DOUBLINGS)) { wait *= 2 }
            return minOf(wait, BACKOFF_MAX)
        }

        private const val UNAUTHORIZED = 401
        private const val FORBIDDEN = 403
        private val SERVER_ERRORS = 500..599
        private const val TRY_AGAIN_LATER = 1013
        private const val AT_CAP = 4029
        private const val MAX_DOUBLINGS = 10
        private val BACKOFF_FIRST = 30.seconds
        private val BACKOFF_MAX = 5.minutes
        private val ADMISSION_WAIT = 60.seconds
    }
}
