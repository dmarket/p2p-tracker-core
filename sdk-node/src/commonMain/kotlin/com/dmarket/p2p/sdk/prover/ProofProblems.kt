package com.dmarket.p2p.sdk.prover

import com.dmarket.p2p.sdk.health.ProblemCode
import com.dmarket.p2p.tracker.notary.ProofFailure

/**
 * What a failed proof means for the SDK's health: the problem it raises. When the next proof may start is the
 * library's ([com.dmarket.p2p.headless.prover.ProverBackoff]). The notary's refusals follow the notary's own table:
 * a 503 before the upgrade or a close 1013 after it is the notary being busy or down; a 403 or a close 4029 is
 * admission; a 401 is the key or the clock; the protocol's version error only an update fixes; a close 4008 and
 * everything else is that proof's failure.
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

    private const val UNAUTHORIZED = 401
    private const val FORBIDDEN = 403
    private const val TOO_MANY_REQUESTS = 429
    private val SERVER_ERRORS = 500..599
    private const val TRY_AGAIN_LATER = 1013
    private const val AT_CAP = 4029
}
