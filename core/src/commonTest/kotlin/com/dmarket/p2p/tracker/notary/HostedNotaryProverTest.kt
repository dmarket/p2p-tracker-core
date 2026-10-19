package com.dmarket.p2p.tracker.notary

import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.support.FakeClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class HostedNotaryProverTest {
    private val clock = FakeClock()
    private val binding = ProvenReadBinding(dealId = DealId("deal-1"), steamOfferId = OfferId("555"))
    private val headers = NotaryUpgradeHeaders(mapOf("X-Api-Key" to "pub", "X-Sign-Date" to "1", "X-Request-Sign" to "dmar ed25519 ab"))
    private val signer = NotaryUpgradeSigner { headers }

    private fun credential(left: kotlin.time.Duration = 2.hours) = SteamCredential(TOKEN, SteamId(STEAM_ID), clock.now() + left)

    /** A host that records what it was handed and answers [answer]. */
    private class RecordingHost(var answer: HostedProofOutcome = HostedProofOutcome.Proved("cHJvb2Y=")) : ProofHost {
        val proofs = mutableListOf<HostedProofRequest>()
        val signed = mutableListOf<NotaryUpgradeHeaders>()

        override suspend fun prove(proof: HostedProofRequest, upgrade: NotaryUpgradeSigner): HostedProofOutcome {
            proofs += proof
            signed += upgrade.sign()
            return answer
        }
    }

    private fun prover(host: ProofHost, maxConcurrency: Int = 1) = HostedNotaryProver(
        host = host,
        upgrade = signer,
        clock = clock,
        steamSessionId = SESSION_ID,
        maxConcurrency = maxConcurrency,
        minTokenLifetime = 30.minutes,
    )

    @Test
    fun a_proof_goes_to_the_host_with_the_token_beside_a_credential_free_request() = runTest {
        val host = RecordingHost()
        val proof = prover(host).proveRead(binding, ProvenReadKind.TRADE_OFFER, credential())

        assertEquals(DealId("deal-1"), proof.dealId)
        assertEquals("cHJvb2Y=", proof.proofPayload)
        val sent = host.proofs.single()
        assertEquals(TOKEN, sent.steamAccessToken)
        assertEquals(SESSION_ID, sent.steamSessionId)
        assertEquals(notaryProofRequest(binding, ProvenReadKind.TRADE_OFFER, SteamId(STEAM_ID)), sent.request)
        val json = Json.encodeToString(NotaryProofRequest.serializer(), sent.request)
        assertFalse(TOKEN in json, "the request JSON carries no token")
        assertFalse(TOKEN in sent.toString() || SESSION_ID in sent.toString(), "toString redacts both Steam values")
        assertEquals(listOf(headers), host.signed)
    }

    @Test
    fun no_proof_starts_on_a_token_with_under_thirty_minutes_left() = runTest {
        val host = RecordingHost()
        val failure = assertFailsWith<HostedProofException> {
            prover(host).proveRead(binding, ProvenReadKind.TRADE_OFFER, credential(left = 29.minutes))
        }
        assertEquals(ProofFailure.NotStarted(PROOF_TOKEN_EXPIRING_REASON), failure.failure)
        assertTrue(PROOF_TOKEN_EXPIRING_REASON in failure.message.orEmpty())
        assertTrue(host.proofs.isEmpty(), "no host was asked")

        prover(host).proveRead(binding, ProvenReadKind.TRADE_OFFER, credential(left = 30.minutes))
        assertEquals(1, host.proofs.size)
    }

    @Test
    fun a_failure_keeps_its_structure_and_says_it_in_the_message() = runTest {
        val host = RecordingHost(HostedProofOutcome.Failed(ProofFailure.UpgradeRefused(503)))
        val failure = assertFailsWith<HostedProofException> { prover(host).proveRead(binding, ProvenReadKind.TRADE_OFFER, credential()) }
        assertEquals(ProofFailure.UpgradeRefused(503), failure.failure)
        assertEquals("the notary refused the upgrade: HTTP 503", failure.message)
    }

    @Test
    fun a_host_that_throws_is_read_as_another_failure() = runTest {
        val host = ProofHost { _, _ -> error("fork failed: EAGAIN") }
        val failure = assertFailsWith<HostedProofException> { prover(host).proveRead(binding, ProvenReadKind.TRADE_OFFER, credential()) }
        assertEquals(ProofFailure.Other("fork failed: EAGAIN"), failure.failure)
    }

    @Test
    fun one_proof_at_a_time_by_default_and_two_when_configured() = runTest {
        for (limit in 1..2) {
            val release = CompletableDeferred<Unit>()
            var running = 0
            var peak = 0
            val host = ProofHost { _, _ ->
                running++
                peak = maxOf(peak, running)
                release.await()
                running--
                HostedProofOutcome.Proved("cA==")
            }
            val prover = prover(host, maxConcurrency = limit)
            val proofs = List(3) { async { prover.proveRead(binding, ProvenReadKind.TRADE_OFFER, credential()) } }
            runCurrent()
            assertEquals(limit, peak, "at most $limit at once")
            release.complete(Unit)
            proofs.forEach { it.await() }
        }
    }

    @Test
    fun more_than_two_at_once_is_refused() {
        assertFailsWith<IllegalArgumentException> { prover(RecordingHost(), maxConcurrency = 3) }
        assertFailsWith<IllegalArgumentException> { prover(RecordingHost(), maxConcurrency = 0) }
        assertEquals(1, HostedNotaryProver(RecordingHost(), signer, clock, SESSION_ID).maxConcurrency)
    }

    @Test
    fun the_upgrade_headers_print_no_value() {
        assertEquals("NotaryUpgradeHeaders(X-Api-Key, X-Sign-Date, X-Request-Sign: <redacted>)", headers.toString())
    }

    @Test
    fun failures_round_trip_as_json_with_a_type() {
        val failures = listOf(
            ProofFailure.UpgradeRefused(401),
            ProofFailure.SessionClosed(1013, "queue full"),
            ProofFailure.NotaryUnreachable("ECONNREFUSED"),
            ProofFailure.VersionMismatch(ProofFailures.VERSION_ERROR),
            ProofFailure.SteamFailed(403, "Steam answered HTTP 403"),
            ProofFailure.LimitsExceeded("online phase"),
            ProofFailure.ProverKilled(KillCause.OUT_OF_MEMORY),
            ProofFailure.NotStarted("backoff"),
            ProofFailure.Other("boom"),
        )
        for (failure in failures) {
            val json = Json.encodeToString(ProofFailure.serializer(), failure)
            assertTrue("\"type\"" in json, json)
            assertEquals(failure, Json.decodeFromString(ProofFailure.serializer(), json))
        }
        assertIs<ProofFailure.ProverKilled>(
            Json.decodeFromString(ProofFailure.serializer(), """{"type":"prover_killed","cause":"session_deadline"}"""),
        )
    }

    private companion object {
        const val STEAM_ID = "76561198000000001"
        const val TOKEN = "eyJhbGciOiJFZERTQSJ9.eyJzdWIiOiI3NjU2MTE5ODAwMDAwMDAwMSJ9.c2lnbmF0dXJl"
        const val SESSION_ID = "0123456789abcdef01234567"
    }
}
