package com.dmarket.p2p.sdk.prover

import com.dmarket.p2p.sdk.health.ProblemCode
import com.dmarket.p2p.tracker.notary.KillCause
import com.dmarket.p2p.tracker.notary.ProofFailure
import kotlin.test.Test
import kotlin.test.assertEquals

/** Each notary refusal maps to its problem, by the notary's own table. How long it waits is the library's. */
class ProofProblemsTest {
    private val table: List<Pair<ProofFailure, ProblemCode?>> = listOf(
        Pair(ProofFailure.UpgradeRefused(401), ProblemCode.DMARKET_AUTH_FAILED),
        Pair(ProofFailure.UpgradeRefused(503), ProblemCode.NOTARY_UNAVAILABLE),
        Pair(ProofFailure.UpgradeRefused(502), ProblemCode.NOTARY_UNAVAILABLE),
        Pair(ProofFailure.SessionClosed(1013, "queue full"), ProblemCode.NOTARY_UNAVAILABLE),
        Pair(ProofFailure.NotaryUnreachable("ECONNREFUSED"), ProblemCode.NOTARY_UNAVAILABLE),
        // Sent only by the deferred admission gate: mapped now, so its return needs no SDK release.
        Pair(ProofFailure.UpgradeRefused(403), ProblemCode.NOTARY_NOT_ADMITTED),
        Pair(ProofFailure.SessionClosed(4029), ProblemCode.NOTARY_NOT_ADMITTED),
        Pair(ProofFailure.SessionClosed(4008), ProblemCode.PROOF_FAILED),
        Pair(ProofFailure.VersionMismatch("prover version does not match with verifier"), ProblemCode.SDK_UPDATE_REQUIRED),
        Pair(ProofFailure.SteamFailed(401, "Steam answered HTTP 401"), ProblemCode.STEAM_SESSION_REJECTED),
        Pair(ProofFailure.SteamFailed(429, "Steam answered HTTP 429"), ProblemCode.STEAM_RATE_LIMITED),
        Pair(ProofFailure.SteamFailed(detail = "ECONNRESET"), ProblemCode.STEAM_UNREACHABLE),
        Pair(ProofFailure.LimitsExceeded("online phase"), ProblemCode.PROOF_FAILED),
        Pair(ProofFailure.ProverKilled(KillCause.OUT_OF_MEMORY), ProblemCode.PROVER_KILLED),
        Pair(ProofFailure.ProverKilled(KillCause.SESSION_DEADLINE), ProblemCode.PROVER_KILLED),
        Pair(ProofFailure.Other("context mux error"), ProblemCode.PROOF_FAILED),
        Pair(ProofFailure.UpgradeRefused(400), ProblemCode.PROOF_FAILED),
        Pair(ProofFailure.NotStarted("backoff"), null),
    )

    @Test
    fun each_failure_raises_its_problem() {
        for ((failure, code) in table) assertEquals(code, ProofProblems.problemFor(failure), "$failure")
    }

    @Test
    fun a_notary_401_on_a_skewed_clock_is_the_clock() {
        assertEquals(ProblemCode.CLOCK_SKEW, ProofProblems.problemFor(ProofFailure.UpgradeRefused(401), clockSkewed = true))
    }
}
