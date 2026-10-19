package com.dmarket.p2p.sdk.prover

import com.dmarket.p2p.sdk.T0
import com.dmarket.p2p.sdk.health.ProblemCode
import com.dmarket.p2p.tracker.notary.KillCause
import com.dmarket.p2p.tracker.notary.ProofFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Each notary refusal maps to its problem and its retry, by the notary's own table. */
class ProofProblemsTest {
    private val table: List<Triple<ProofFailure, ProblemCode?, Duration?>> = listOf(
        Triple(ProofFailure.UpgradeRefused(401), ProblemCode.DMARKET_AUTH_FAILED, 30.seconds),
        Triple(ProofFailure.UpgradeRefused(503), ProblemCode.NOTARY_UNAVAILABLE, 30.seconds),
        Triple(ProofFailure.UpgradeRefused(502), ProblemCode.NOTARY_UNAVAILABLE, 30.seconds),
        Triple(ProofFailure.SessionClosed(1013, "queue full"), ProblemCode.NOTARY_UNAVAILABLE, 30.seconds),
        Triple(ProofFailure.NotaryUnreachable("ECONNREFUSED"), ProblemCode.NOTARY_UNAVAILABLE, 30.seconds),
        // Sent only by the deferred admission gate: mapped now, so its return needs no SDK release.
        Triple(ProofFailure.UpgradeRefused(403), ProblemCode.NOTARY_NOT_ADMITTED, 60.seconds),
        Triple(ProofFailure.SessionClosed(4029), ProblemCode.NOTARY_NOT_ADMITTED, 60.seconds),
        Triple(ProofFailure.SessionClosed(4008), ProblemCode.PROOF_FAILED, null),
        Triple(
            ProofFailure.VersionMismatch("prover version does not match with verifier"),
            ProblemCode.SDK_UPDATE_REQUIRED,
            Duration.INFINITE,
        ),
        Triple(ProofFailure.SteamFailed(401, "Steam answered HTTP 401"), ProblemCode.STEAM_SESSION_REJECTED, null),
        Triple(ProofFailure.SteamFailed(429, "Steam answered HTTP 429"), ProblemCode.STEAM_RATE_LIMITED, null),
        Triple(ProofFailure.SteamFailed(detail = "ECONNRESET"), ProblemCode.STEAM_UNREACHABLE, null),
        Triple(ProofFailure.LimitsExceeded("online phase"), ProblemCode.PROOF_FAILED, null),
        Triple(ProofFailure.ProverKilled(KillCause.OUT_OF_MEMORY), ProblemCode.PROVER_KILLED, null),
        Triple(ProofFailure.ProverKilled(KillCause.SESSION_DEADLINE), ProblemCode.PROVER_KILLED, null),
        Triple(ProofFailure.Other("context mux error"), ProblemCode.PROOF_FAILED, null),
        Triple(ProofFailure.UpgradeRefused(400), ProblemCode.PROOF_FAILED, null),
        Triple(ProofFailure.NotStarted("backoff"), null, null),
    )

    @Test
    fun each_failure_raises_its_problem_and_waits_its_time() {
        for ((failure, code, wait) in table) {
            assertEquals(code, ProofProblems.problemFor(failure), "$failure")
            assertEquals(wait, ProofProblems.waitAfter(failure, consecutive = 0), "$failure")
        }
    }

    @Test
    fun a_notary_401_on_a_skewed_clock_is_the_clock() {
        assertEquals(ProblemCode.CLOCK_SKEW, ProofProblems.problemFor(ProofFailure.UpgradeRefused(401), clockSkewed = true))
    }

    @Test
    fun a_busy_notary_backs_off_from_thirty_seconds_doubling_to_five_minutes() {
        val waits = (0..6).map { ProofProblems.waitAfter(ProofFailure.UpgradeRefused(503), it) }
        assertEquals(listOf(30.seconds, 60.seconds, 120.seconds, 240.seconds, 5.minutes, 5.minutes, 5.minutes), waits)
    }

    @Test
    fun the_backoff_holds_proofs_until_its_time_and_a_proof_clears_it() {
        val backoff = ProverBackoff()
        assertNull(backoff.blocked(T0))
        backoff.failed(ProofFailure.UpgradeRefused(503), T0)
        assertNotNull(backoff.blocked(T0 + 29.seconds))
        assertNull(backoff.blocked(T0 + 30.seconds))
        backoff.failed(ProofFailure.SessionClosed(1013), T0 + 30.seconds)
        assertNotNull(backoff.blocked(T0 + 89.seconds), "the second refusal in a row waits 60 s")
        backoff.succeeded()
        assertNull(backoff.blocked(T0 + 31.seconds))
        backoff.failed(ProofFailure.SessionClosed(4008), T0)
        assertNull(backoff.blocked(T0), "a stalled session waits for nothing but the next intent")
    }

    @Test
    fun a_version_error_holds_every_proof_until_an_update_and_a_key_push_does_not_lift_it() {
        val backoff = ProverBackoff()
        backoff.failed(ProofFailure.VersionMismatch("prover version does not match with verifier"), T0)
        val reason = backoff.blocked(T0 + 365.minutes * 24)
        assertTrue(reason.orEmpty().contains("updated"), reason)
        backoff.keyChanged()
        assertNotNull(backoff.blocked(T0 + 1.minutes))
    }

    @Test
    fun a_new_key_lifts_the_wait_a_refused_key_set() {
        val backoff = ProverBackoff()
        backoff.failed(ProofFailure.UpgradeRefused(401), T0)
        assertNotNull(backoff.blocked(T0 + 1.seconds))
        backoff.keyChanged()
        assertNull(backoff.blocked(T0 + 1.seconds))
    }
}
