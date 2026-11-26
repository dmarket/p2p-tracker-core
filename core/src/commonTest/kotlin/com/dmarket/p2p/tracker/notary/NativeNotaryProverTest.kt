package com.dmarket.p2p.tracker.notary

import com.dmarket.p2p.tracker.config.NotaryConfig
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.TradeId
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.port.notary.NotaryTokenProvider
import com.dmarket.p2p.tracker.support.FakeClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalEncodingApi::class)
class NativeNotaryProverTest {
    private val clock = FakeClock()
    private val binding = ProvenReadBinding(dealId = DealId("deal-1"), steamOfferId = OfferId("555"), tradeId = TradeId("777"))

    private fun credential(left: Duration = 2.hours) = SteamCredential(TOKEN, SteamId(STEAM_ID), clock.now() + left)

    /** Answers [answer], or waits on [hold] when set; records the issuances and whether one was cancelled. */
    private class FakeEngine(
        var answer: NativeIssuanceOutcome = NativeIssuanceOutcome(presentation = PRESENTATION, targetStatus = 200),
        var unavailable: String? = null,
    ) : NativeProverEngine {
        val issued = mutableListOf<NativeIssuance>()
        var hold: CompletableDeferred<Unit>? = null
        var forever = false
        var cancelled = 0

        /** Whether the prover reports a stage, which it does only once the notary answered the upgrade. */
        var staged = true

        override fun unavailableReason(): String? = unavailable

        override suspend fun issue(issuance: NativeIssuance, listener: NativeIssuanceListener): NativeIssuanceOutcome {
            issued += issuance
            if (staged) listener.onStage(2, "MPC_SETUP", 0.5f)
            listener.onBytes(sent = 1_000, received = 100)
            try {
                if (forever) awaitCancellation()
                hold?.await()
            } catch (e: CancellationException) {
                cancelled++
                throw e
            }
            return answer
        }
    }

    private fun prover(engine: NativeProverEngine, config: NotaryConfig = NotaryConfig(), deadline: Duration = NATIVE_PROOF_DEADLINE) =
        NativeNotaryProver(
            config = config,
            tokenProvider = NotaryTokenProvider { NOTARY_TOKEN },
            engine = engine,
            clock = clock,
            minTokenLifetime = 30.minutes,
            deadline = deadline,
        )

    private suspend fun failureOf(block: suspend () -> Unit): ProofFailure = assertFailsWith<HostedProofException> { block() }.failure

    @Test
    fun a_proof_returns_the_presentation_and_issues_over_mpc_with_the_target_withheld() = runTest {
        val engine = FakeEngine()
        val prover = prover(engine)

        val proof = prover.proveRead(binding, ProvenReadKind.TRADE_OFFER, credential())

        assertEquals("tlsn", prover.id)
        assertEquals(DealId("deal-1"), proof.dealId)
        assertEquals(Base64.encode(PRESENTATION), proof.proofPayload)
        val issuance = engine.issued.single()
        assertEquals(IssuanceMode.MPC, issuance.mode)
        assertEquals(NOTARY_TOKEN, issuance.notaryToken)
        assertEquals("tlsn.notary.v2", issuance.subprotocol)
        assertEquals("api.steampowered.com", issuance.targetHost)
        assertEquals(180.seconds, issuance.deadline)
        val config = Json.parseToJsonElement(issuance.config).jsonObject
        assertEquals(1, config["abi_version"]!!.jsonPrimitive.content.toInt())
        assertTrue("mpc" in config && "proxy" !in config)
        assertTrue("access_token=$TOKEN" in config["request"]!!.jsonObject["uri"]!!.jsonPrimitive.content)
        assertFalse(Json.parseToJsonElement(issuance.policy).jsonObject["reveal_request_target"]!!.jsonPrimitive.boolean)
        assertFalse(TOKEN in issuance.toString() || NOTARY_TOKEN in issuance.toString())
        // Progress lives while the proof runs; the traffic counter keeps the bytes.
        assertEquals(emptyMap(), prover.progress.value)
        assertEquals(ProofTraffic(1_000, 100), prover.traffic.value)
    }

    @Test
    fun proxy_mode_issues_with_proxy_limits_only() = runTest {
        val engine = FakeEngine()
        prover(engine, NotaryConfig(mode = "proxy")).proveRead(binding, ProvenReadKind.TRADE_OFFER, credential())

        val issuance = engine.issued.single()
        assertEquals(IssuanceMode.PROXY, issuance.mode)
        val config = Json.parseToJsonElement(issuance.config).jsonObject
        assertTrue("proxy" in config && "mpc" !in config)
    }

    @Test
    fun a_running_proof_publishes_its_progress_and_a_cancel_ends_it_as_a_failure() = runTest {
        val engine = FakeEngine().apply { forever = true }
        val prover = prover(engine)
        val proof = async { runCatching { prover.proveRead(binding, ProvenReadKind.TRADE_OFFER, credential()) } }
        runCurrent()

        assertEquals(ProofProgress(2, "MPC_SETUP", 0.5f, 1_000, 100), prover.progress.value[DealId("deal-1")])
        assertTrue(prover.cancel(DealId("deal-1")))

        val failure = assertIs<HostedProofException>(proof.await().exceptionOrNull()).failure
        assertEquals(ProofFailure.Other(PROOF_CANCELLED_REASON), failure)
        assertEquals(1, engine.cancelled)
        assertEquals(emptyMap(), prover.progress.value)
        assertFalse(prover.cancel(DealId("deal-1")))
    }

    @Test
    fun a_cancel_as_the_issuance_starts_finds_the_proof() = runTest(UnconfinedTestDispatcher()) {
        // The unconfined dispatcher runs the issuance at once, inside the launch of the proof.
        var found: Boolean? = null
        lateinit var prover: NativeNotaryProver
        val engine = object : NativeProverEngine {
            override fun unavailableReason(): String? = null

            override suspend fun issue(issuance: NativeIssuance, listener: NativeIssuanceListener): NativeIssuanceOutcome {
                found = prover.cancel(DealId("deal-1"))
                awaitCancellation()
            }
        }
        prover = prover(engine)

        val failure = failureOf { prover.proveRead(binding, ProvenReadKind.TRADE_OFFER, credential()) }

        assertEquals(true, found)
        assertEquals(ProofFailure.Other(PROOF_CANCELLED_REASON), failure)
    }

    @Test
    fun two_proofs_of_one_deal_are_tracked_apart() = runTest {
        val first = CompletableDeferred<Unit>()
        var calls = 0
        val engine = object : NativeProverEngine {
            override fun unavailableReason(): String? = null

            override suspend fun issue(issuance: NativeIssuance, listener: NativeIssuanceListener): NativeIssuanceOutcome {
                if (++calls == 1) first.await() else awaitCancellation()
                return NativeIssuanceOutcome(presentation = PRESENTATION, targetStatus = 200)
            }
        }
        val prover = prover(engine)
        val proofs = List(2) { async { runCatching { prover.proveRead(binding, ProvenReadKind.TRADE_OFFER, credential()) } } }
        runCurrent()

        first.complete(Unit)
        assertTrue(proofs[0].await().isSuccess)
        assertEquals(setOf(DealId("deal-1")), prover.progress.value.keys)
        assertTrue(prover.cancel(DealId("deal-1")), "the second proof is still running")
        val failure = assertIs<HostedProofException>(proofs[1].await().exceptionOrNull()).failure
        assertEquals(ProofFailure.Other(PROOF_CANCELLED_REASON), failure)
        assertEquals(emptyMap(), prover.progress.value)
    }

    @Test
    fun the_callers_cancellation_cancels_the_issuance_and_propagates() = runTest {
        val engine = FakeEngine().apply { forever = true }
        val proof = async { prover(engine).proveRead(binding, ProvenReadKind.TRADE_OFFER, credential()) }
        runCurrent()
        proof.cancel()
        runCurrent()

        assertTrue(proof.isCancelled)
        assertEquals(1, engine.cancelled)
    }

    @Test
    fun the_callers_cancellation_after_the_101_is_a_cut_session_and_before_it_a_plain_cancellation() = runTest {
        for (staged in listOf(false, true)) {
            val engine = FakeEngine().apply {
                forever = true
                this.staged = staged
            }
            var thrown: Throwable? = null
            val proof = async {
                try {
                    prover(engine).proveRead(binding, ProvenReadKind.TRADE_OFFER, credential())
                } catch (e: CancellationException) {
                    thrown = e
                    throw e
                }
            }
            runCurrent()
            proof.cancel()
            runCurrent()

            assertTrue(proof.isCancelled, "staged=$staged")
            assertEquals(staged, thrown is NotarySessionCutException, "staged=$staged: $thrown")
        }
    }

    @Test
    fun a_proof_queued_for_the_permit_goes_with_its_callers_scope_and_never_issues() = runTest {
        // Cancel on the app's screen cancels the caller's coroutine; cancel(dealId) sees only an issuing proof.
        val engine = FakeEngine().apply { hold = CompletableDeferred() }
        val prover = prover(engine, NotaryConfig(maxConcurrency = 1))
        val other = ProvenReadBinding(dealId = DealId("deal-2"), steamOfferId = OfferId("556"), tradeId = TradeId("778"))
        val holding = async { prover.proveRead(binding, ProvenReadKind.TRADE_OFFER, credential()) }
        val queued = async { prover.proveRead(other, ProvenReadKind.TRADE_OFFER, credential()) }
        runCurrent()
        assertEquals(1, engine.issued.size)
        assertFalse(prover.cancel(DealId("deal-2")), "a queued proof is not running")

        queued.cancel()
        runCurrent()
        engine.hold!!.complete(Unit)
        holding.await()

        assertTrue(queued.isCancelled)
        assertEquals(1, engine.issued.size, "the cancelled proof never issued")
        prover.proveRead(other, ProvenReadKind.TRADE_OFFER, credential())
        assertEquals(2, engine.issued.size, "its place in the queue did not keep the permit")
    }

    @Test
    fun a_proof_past_its_deadline_is_killed_and_its_issuance_cancelled() = runTest {
        val engine = FakeEngine().apply { forever = true }
        val proof =
            async { runCatching { prover(engine, deadline = 180.seconds).proveRead(binding, ProvenReadKind.TRADE_OFFER, credential()) } }
        advanceTimeBy(179.seconds)
        assertTrue(proof.isActive)
        advanceTimeBy(2.seconds)

        val failure = assertIs<HostedProofException>(proof.await().exceptionOrNull()).failure
        assertIs<ProofFailure.ProverKilled>(failure)
        assertEquals(KillCause.SESSION_DEADLINE, failure.cause)
        assertEquals(1, engine.cancelled)
    }

    @Test
    fun the_c_abi_timeout_is_the_same_deadline() = runTest {
        val engine = FakeEngine(NativeIssuanceOutcome(status = NativeIssuanceOutcome.STATUS_TIMEOUT, diagnostic = "TIMEOUT (7)"))
        val failure = failureOf { prover(engine).proveRead(binding, ProvenReadKind.TRADE_OFFER, credential()) }
        assertEquals(ProofFailure.ProverKilled(KillCause.SESSION_DEADLINE, "TIMEOUT (7)"), failure)
    }

    @Test
    fun a_notary_on_another_protocol_version_is_a_version_mismatch() = runTest {
        val engine = FakeEngine(NativeIssuanceOutcome(status = 4, diagnostic = "PROTOCOL (4): prover version does not match with verifier"))
        assertIs<ProofFailure.VersionMismatch>(failureOf { prover(engine).proveRead(binding, ProvenReadKind.TRADE_OFFER, credential()) })
    }

    @Test
    fun a_notary_that_never_answers_the_upgrade_is_unreachable_and_a_refused_upgrade_keeps_its_status() = runTest {
        val engine = FakeEngine(NativeIssuanceOutcome(notaryConnected = false, diagnostic = "connect timed out"))
        assertEquals(
            ProofFailure.NotaryUnreachable("connect timed out"),
            failureOf { prover(engine).proveRead(binding, ProvenReadKind.TRADE_OFFER, credential()) },
        )
        engine.answer = NativeIssuanceOutcome(upgradeStatus = 401, notaryConnected = false, diagnostic = "401")
        assertEquals(
            ProofFailure.UpgradeRefused(401),
            failureOf {
                prover(engine).proveRead(binding, ProvenReadKind.TRADE_OFFER, credential())
            },
        )
    }

    @Test
    fun a_presentation_of_a_steam_error_is_not_a_proof() = runTest {
        val engine = FakeEngine(NativeIssuanceOutcome(presentation = PRESENTATION, targetStatus = 429))
        assertIs<ProofFailure.SteamFailed>(failureOf { prover(engine).proveRead(binding, ProvenReadKind.TRADE_OFFER, credential()) })
    }

    @Test
    fun a_token_close_to_its_expiry_starts_no_proof() = runTest {
        val engine = FakeEngine()
        val failure = failureOf { prover(engine).proveRead(binding, ProvenReadKind.TRADE_OFFER, credential(left = 10.minutes)) }

        assertEquals(ProofFailure.NotStarted(PROOF_TOKEN_EXPIRING_REASON), failure)
        assertTrue(engine.issued.isEmpty())
    }

    @Test
    fun a_device_without_the_native_library_starts_no_proof() = runTest {
        val engine = FakeEngine(unavailable = "the native prover did not load: no libprovenance_client_cffi.so for x86")
        val failure = failureOf { prover(engine).proveRead(binding, ProvenReadKind.TRADE_OFFER, credential()) }

        assertEquals(ProofFailure.NotStarted("the native prover did not load: no libprovenance_client_cffi.so for x86"), failure)
        assertTrue(engine.issued.isEmpty())
    }

    @Test
    fun no_dmarket_session_and_an_unenabled_kind_start_no_proof() = runTest {
        val engine = FakeEngine()
        val noSession = NativeNotaryProver(NotaryConfig(), NotaryTokenProvider { error("no DMarket session") }, engine, clock)
        assertEquals(
            ProofFailure.NotStarted("no DMarket session"),
            failureOf {
                noSession.proveRead(binding, ProvenReadKind.TRADE_OFFER, credential())
            },
        )
        assertIs<ProofFailure.NotStarted>(failureOf { prover(engine).proveRead(binding, ProvenReadKind.CREATE_OFFER, credential()) })
        assertTrue(engine.issued.isEmpty())
    }

    @Test
    fun a_third_proof_waits_for_a_permit() = runTest {
        val engine = FakeEngine().apply { hold = CompletableDeferred() }
        val prover = prover(engine, NotaryConfig(maxConcurrency = 2))
        val proofs = (1..3).map { n ->
            async { prover.proveRead(binding.copy(dealId = DealId("deal-$n")), ProvenReadKind.TRADE_OFFER, credential()) }
        }
        runCurrent()
        assertEquals(2, engine.issued.size)
        assertEquals(2, prover.progress.value.size)

        engine.hold!!.complete(Unit)
        proofs.forEach { it.await() }
        assertEquals(3, engine.issued.size)
    }

    private companion object {
        const val TOKEN = "eyJhbGciOiJIUzI1NiJ9.steam-token.sig"
        const val NOTARY_TOKEN = "dmarket-access-token"
        const val STEAM_ID = "76561198000000001"
        val PRESENTATION = byteArrayOf(1, 2, 3, 4)
    }
}
