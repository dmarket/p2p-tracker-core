package com.dmarket.p2p.headless.prover

import com.dmarket.p2p.headless.T0
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

/** How long each notary refusal holds the next proof back, by the notary's own table. */
class ProverBackoffTest {
    private val table: List<Pair<ProofFailure, Duration?>> = listOf(
        ProofFailure.UpgradeRefused(401) to 30.seconds,
        ProofFailure.UpgradeRefused(503) to 30.seconds,
        ProofFailure.UpgradeRefused(502) to 30.seconds,
        ProofFailure.UpgradeRefused(500) to 30.seconds,
        ProofFailure.UpgradeRefused(599) to 30.seconds,
        ProofFailure.SessionClosed(1013, "queue full") to 30.seconds,
        ProofFailure.NotaryUnreachable("ECONNREFUSED") to 30.seconds,
        // Sent only by the deferred admission gate: mapped now, so its return needs no release.
        ProofFailure.UpgradeRefused(403) to 60.seconds,
        ProofFailure.SessionClosed(4029) to 60.seconds,
        ProofFailure.SessionClosed(4008) to null,
        ProofFailure.VersionMismatch("prover version does not match with verifier") to Duration.INFINITE,
        ProofFailure.SteamFailed(401, "Steam answered HTTP 401") to null,
        ProofFailure.SteamFailed(429, "Steam answered HTTP 429") to null,
        ProofFailure.SteamFailed(detail = "ECONNRESET") to null,
        ProofFailure.LimitsExceeded("online phase") to null,
        ProofFailure.ProverKilled(KillCause.OUT_OF_MEMORY) to null,
        ProofFailure.ProverKilled(KillCause.SESSION_DEADLINE) to null,
        ProofFailure.Other("context mux error") to null,
        ProofFailure.UpgradeRefused(400) to null,
        ProofFailure.UpgradeRefused(600) to null,
        ProofFailure.NotStarted("backoff") to null,
    )

    @Test
    fun each_failure_waits_its_time() {
        for ((failure, wait) in table) assertEquals(wait, ProverBackoff.waitAfter(failure, consecutive = 0), "$failure")
    }

    @Test
    fun a_busy_notary_backs_off_from_thirty_seconds_doubling_to_five_minutes() {
        val waits = (0..6).map { ProverBackoff.waitAfter(ProofFailure.UpgradeRefused(503), it) }
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
