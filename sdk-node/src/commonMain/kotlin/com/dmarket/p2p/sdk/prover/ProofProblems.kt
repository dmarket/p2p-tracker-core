package com.dmarket.p2p.sdk.prover

import com.dmarket.p2p.sdk.health.ProblemCode
import com.dmarket.p2p.tracker.notary.ProofFailure
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * What a failed proof means for the SDK: the problem it raises, and when the next proof may start. The notary's
 * refusals follow the notary's own table: a 503 before the upgrade or a close 1013 after it is the notary being
 * busy or down; a 403 or a close 4029 is admission; a 401 is the key or the clock; the protocol's version error
 * only an update fixes; a close 4008 and everything else is that proof's failure.
 */
object ProofProblems {
    /** The problem [failure] raises, or `null` for one that was never started. [clockSkewed]: the host clock is off. */
    fun problemFor(failure: ProofFailure, clockSkewed: Boolean = false): ProblemCode? = when (failure) {
        is ProofFailure.UpgradeRefused -> when (failure.status) {
            UNAUTHORIZED -> if (clockSkewed) ProblemCode.CLOCK_SKEW else ProblemCode.DMARKET_AUTH_FAILED
            FORBIDDEN -> ProblemCode.NOTARY_NOT_ADMITTED
            in SERVER_ERRORS -> ProblemCode.NOTARY_UNAVAILABLE
            else -> ProblemCode.PROOF_FAILED
        }
        is ProofFailure.SessionClosed -> when (failure.code) {
            TRY_AGAIN_LATER -> ProblemCode.NOTARY_UNAVAILABLE
            AT_CAP -> ProblemCode.NOTARY_NOT_ADMITTED
            else -> ProblemCode.PROOF_FAILED
        }
        is ProofFailure.NotaryUnreachable -> ProblemCode.NOTARY_UNAVAILABLE
        is ProofFailure.VersionMismatch -> ProblemCode.SDK_UPDATE_REQUIRED
        is ProofFailure.SteamFailed -> when (failure.status) {
            UNAUTHORIZED, FORBIDDEN -> ProblemCode.STEAM_SESSION_REJECTED
            TOO_MANY_REQUESTS -> ProblemCode.STEAM_RATE_LIMITED
            else -> ProblemCode.STEAM_UNREACHABLE
        }
        is ProofFailure.ProverKilled -> ProblemCode.PROVER_KILLED
        is ProofFailure.NotStarted -> null
        is ProofFailure.LimitsExceeded, is ProofFailure.Other -> ProblemCode.PROOF_FAILED
    }

    /** How long after [failure] the next proof must wait, given [consecutive] busy refusals in a row before it. */
    fun waitAfter(failure: ProofFailure, consecutive: Int): Duration? = when (problemFor(failure)) {
        // Busy or down: back off from 30 s, doubling to 5 minutes. A refused key or clock waits the same way: each
        // retry costs one refused upgrade, and a push or a clock fix may come at any time.
        ProblemCode.NOTARY_UNAVAILABLE, ProblemCode.DMARKET_AUTH_FAILED, ProblemCode.CLOCK_SKEW -> backoff(consecutive)
        // 403: at the next intent, at most once a minute (the record lags a transition by seconds); 4029: after the
        // lease's 60 s expiry.
        ProblemCode.NOTARY_NOT_ADMITTED -> ADMISSION_WAIT
        // Nothing but an update fixes it.
        ProblemCode.SDK_UPDATE_REQUIRED -> Duration.INFINITE
        else -> null
    }

    private fun backoff(consecutive: Int): Duration {
        var wait = BACKOFF_FIRST
        repeat(consecutive.coerceIn(0, MAX_DOUBLINGS)) { wait *= 2 }
        return minOf(wait, BACKOFF_MAX)
    }

    private const val UNAUTHORIZED = 401
    private const val FORBIDDEN = 403
    private const val TOO_MANY_REQUESTS = 429
    private val SERVER_ERRORS = 500..599
    private const val TRY_AGAIN_LATER = 1013
    private const val AT_CAP = 4029
    private const val MAX_DOUBLINGS = 10
    private val BACKOFF_FIRST = 30.seconds
    private val BACKOFF_MAX = 5.minutes
    private val ADMISSION_WAIT = 60.seconds
}

/**
 * When the next proof may start, after the failures [ProofProblems.waitAfter] makes wait. A proof that comes
 * back, or a newly held key, clears the wait.
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
        val wait = ProofProblems.waitAfter(failure, consecutive) ?: return
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
}
