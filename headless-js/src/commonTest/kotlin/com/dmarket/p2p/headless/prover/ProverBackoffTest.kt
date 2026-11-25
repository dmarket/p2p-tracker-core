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
import kotlin.time.Instant

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
        ProofFailure.ProxyUnreachable("could not connect to the proxy: ECONNREFUSED") to 30.seconds,
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

    @Test
    fun another_proxy_or_the_same_one_back_ends_a_proxys_wait_and_leaves_the_notarys_alone() {
        val now = Instant.fromEpochSeconds(1_781_611_200)
        for (lift in listOf(ProverBackoff::proxyChanged, ProverBackoff::proxyRecovered)) {
            val proxyDown = ProverBackoff().apply { failed(ProofFailure.ProxyUnreachable("the proxy answered CONNECT with HTTP 407"), now) }
            assertNotNull(proxyDown.blocked(now))
            lift(proxyDown)
            assertNull(proxyDown.blocked(now), lift.name)

            val notaryDown = ProverBackoff().apply { failed(ProofFailure.NotaryUnreachable("ECONNREFUSED"), now) }
            lift(notaryDown)
            assertNotNull(notaryDown.blocked(now), "a busy notary is not the proxy's to clear (${lift.name})")
        }
    }

    @Test
    fun a_proxy_that_lets_reads_through_but_not_the_prover_lifts_one_wait_and_then_the_ladder_holds() {
        val refused = ProofFailure.ProxyUnreachable("the proxy answered CONNECT with HTTP 429")
        val backoff = ProverBackoff()
        var now = T0

        backoff.failed(refused, now)
        backoff.proxyRecovered()
        assertNull(backoff.blocked(now), "the first recovery lets the held proof start")

        // Each later cycle: the prover's tunnel is refused again, the tracker's reads get through again.
        val waits = (1..4).map {
            backoff.failed(refused, now)
            backoff.proxyRecovered()
            assertNotNull(backoff.blocked(now), "recovery $it must not lift the wait again")
            var wait = Duration.ZERO
            while (backoff.blocked(now + wait) != null) wait += 1.seconds
            now += wait
            wait
        }
        assertEquals(listOf(60.seconds, 120.seconds, 240.seconds, 5.minutes), waits)
    }

    @Test
    fun a_proof_that_comes_back_or_another_proxy_lets_a_recovery_lift_a_wait_again() {
        val refused = ProofFailure.ProxyUnreachable("the proxy answered CONNECT with HTTP 429")
        for (rearm in listOf<(ProverBackoff) -> Unit>({ it.succeeded() }, { it.proxyChanged() })) {
            val backoff = ProverBackoff()
            backoff.failed(refused, T0)
            backoff.proxyRecovered()
            backoff.failed(refused, T0)
            rearm(backoff)

            backoff.failed(refused, T0)
            assertEquals(
                "no proof until ${T0 + 30.seconds}, after ${refused.summary}",
                backoff.blocked(T0),
                "the run starts again from the first wait",
            )
            backoff.proxyRecovered()
            assertNull(backoff.blocked(T0))
        }
    }
}
