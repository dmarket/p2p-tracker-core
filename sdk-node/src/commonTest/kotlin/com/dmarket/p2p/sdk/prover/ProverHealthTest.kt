package com.dmarket.p2p.sdk.prover

import com.dmarket.p2p.sdk.STEAM_ID
import com.dmarket.p2p.sdk.T0
import com.dmarket.p2p.sdk.TestClock
import com.dmarket.p2p.sdk.health.HealthTracker
import com.dmarket.p2p.sdk.health.ProblemCode
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.LifecycleEvent
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.notary.KillCause
import com.dmarket.p2p.tracker.notary.PROOF_TOKEN_EXPIRING_REASON
import com.dmarket.p2p.tracker.notary.ProofFailure
import com.dmarket.p2p.tracker.runtime.HeadlessTrackerState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** What a proof's end does to health: the host's classification wins over the loop's generic `ProofFailed`. */
class ProverHealthTest {
    private val clock = TestClock()
    private val health = HealthTracker(clock, version = "1.2.3")

    private fun state(tokenExpiresIn: Duration = 24.hours, clockOffset: Duration? = null) = HeadlessTrackerState(
        hasTradingApiKey = true,
        steamTokenSteamId = SteamId(STEAM_ID),
        steamTokenExpiresAt = T0 + tokenExpiresIn,
        linkedSteamId = SteamId(STEAM_ID),
        readySince = null,
        trackedDealIds = listOf(DealId("deal-1")),
        clockOffset = clockOffset,
    )

    private fun problems(state: HeadlessTrackerState = state()) = health.snapshot(state).problems.associateBy { it.code }

    /** The order the two arrive in: the host's outcome, then the loop's event for the thrown failure. */
    private suspend fun failed(deal: String, failure: ProofFailure) {
        health.proofOutcome(deal, failure)
        health.onEvent(LifecycleEvent.ProofFailed(deal, "offer", "HostedProofException: ${failure.summary}"))
    }

    @Test
    fun each_refusal_raises_its_code_naming_the_deal_and_not_proof_failed() = runTest {
        val cases = mapOf(
            ProofFailure.UpgradeRefused(503) to ProblemCode.NOTARY_UNAVAILABLE,
            ProofFailure.SessionClosed(1013) to ProblemCode.NOTARY_UNAVAILABLE,
            ProofFailure.UpgradeRefused(403) to ProblemCode.NOTARY_NOT_ADMITTED,
            ProofFailure.SessionClosed(4029) to ProblemCode.NOTARY_NOT_ADMITTED,
            ProofFailure.ProverKilled(KillCause.OUT_OF_MEMORY) to ProblemCode.PROVER_KILLED,
            ProofFailure.VersionMismatch("prover version does not match with verifier") to ProblemCode.SDK_UPDATE_REQUIRED,
            ProofFailure.UpgradeRefused(401) to ProblemCode.DMARKET_AUTH_FAILED,
            ProofFailure.SessionClosed(4008) to ProblemCode.PROOF_FAILED,
        )
        for ((failure, code) in cases) {
            val tracker = HealthTracker(clock, version = "1.2.3")
            tracker.proofOutcome("deal-1", failure)
            tracker.onEvent(LifecycleEvent.ProofFailed("deal-1", "offer", "HostedProofException: ${failure.summary}"))
            val open = tracker.snapshot(state()).problems.associateBy { it.code }
            assertEquals(listOf("deal-1"), open[code]?.affectedDeals?.map { it.dealId }, "$failure")
            if (code != ProblemCode.PROOF_FAILED) assertFalse(ProblemCode.PROOF_FAILED in open, "$failure")
        }
    }

    @Test
    fun a_notary_401_on_a_skewed_clock_is_clock_skew() = runTest {
        failed("deal-1", ProofFailure.UpgradeRefused(401))
        val open = problems(state(clockOffset = 5.minutes))
        assertTrue(ProblemCode.CLOCK_SKEW in open)
        assertFalse(ProblemCode.DMARKET_AUTH_FAILED in open)
        assertEquals("down", health.snapshot(state(clockOffset = 5.minutes)).status)
    }

    @Test
    fun a_proof_that_comes_back_clears_the_provers_problems() = runTest {
        failed("deal-1", ProofFailure.UpgradeRefused(503))
        failed("deal-2", ProofFailure.ProverKilled(KillCause.SESSION_DEADLINE))
        health.proofOutcome("deal-1", null)
        val open = problems()
        assertFalse(ProblemCode.NOTARY_UNAVAILABLE in open || ProblemCode.PROVER_KILLED in open, "$open")
    }

    @Test
    fun a_proof_held_back_for_the_token_is_the_tokens_problem() = runTest {
        health.onEvent(LifecycleEvent.CycleStarted)
        health.onEvent(
            LifecycleEvent.ProofFailed("deal-9", "offer", "HostedProofException: proof not started: $PROOF_TOKEN_EXPIRING_REASON"),
        )
        val open = problems(state(tokenExpiresIn = 20.minutes))
        assertFalse(ProblemCode.PROOF_FAILED in open)
        assertTrue("deal-9" in open.getValue(ProblemCode.STEAM_TOKEN_EXPIRING).affectedDeals.map { it.dealId })
    }

    @Test
    fun a_delivery_failure_the_host_did_not_see_is_proof_failed() = runTest {
        health.proofOutcome("deal-1", null)
        health.onEvent(LifecycleEvent.ProofFailed("deal-1", "offer", "MarketplaceServerErrorException: HTTP 502"))
        assertTrue(ProblemCode.PROOF_FAILED in problems())
    }

    @Test
    fun a_proof_the_backoff_held_back_changes_nothing() = runTest {
        failed("deal-1", ProofFailure.UpgradeRefused(503))
        failed("deal-2", ProofFailure.NotStarted("no proof until …"))
        val open = problems()
        assertEquals(listOf("deal-1"), open.getValue(ProblemCode.NOTARY_UNAVAILABLE).affectedDeals.map { it.dealId })
        assertFalse(ProblemCode.PROOF_FAILED in open)
    }

    @Test
    fun low_memory_is_reported_from_start() {
        val mib = 1024L * 1024L
        health.proverMemoryLow(limitBytes = 512 * mib, usedBytes = 120 * mib, peakBytes = 768 * mib)
        val problem = problems().getValue(ProblemCode.PROVER_MEMORY_LOW)
        assertEquals("degraded", problem.severity)
        assertTrue("512 MiB" in problem.message, problem.message)
    }
}
