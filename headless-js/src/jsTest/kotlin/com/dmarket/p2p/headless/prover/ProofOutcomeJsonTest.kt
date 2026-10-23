package com.dmarket.p2p.headless.prover

import com.dmarket.p2p.headless.STEAM_ID
import com.dmarket.p2p.headless.T0
import com.dmarket.p2p.headless.golden
import com.dmarket.p2p.headless.jwtPayload
import com.dmarket.p2p.headless.steamToken
import com.dmarket.p2p.tracker.notary.KillCause
import com.dmarket.p2p.tracker.notary.NotaryProofRequest
import com.dmarket.p2p.tracker.notary.ProofFailure
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * What a host reads about proofs, fixed in golden files: the JSON of every [ProofFailure] it can be handed, with its
 * texts redacted, the host's own lines, and the IPC message a package smoke test sends a prover process.
 */
class ProofOutcomeJsonTest {
    private val every = listOf(
        ProofFailure.UpgradeRefused(503),
        ProofFailure.SessionClosed(1013, "busy"),
        ProofFailure.SessionClosed(4008),
        ProofFailure.NotaryUnreachable("connect ECONNREFUSED 127.0.0.1:9"),
        ProofFailure.VersionMismatch("prover version does not match with verifier"),
        ProofFailure.SteamFailed(502, "Steam answered HTTP 502"),
        ProofFailure.SteamFailed(detail = "tls handshake failed"),
        ProofFailure.ProverKilled(KillCause.OUT_OF_MEMORY, "signal SIGKILL"),
        ProofFailure.ProverKilled(KillCause.CONNECT_DEADLINE),
        ProofFailure.ProverKilled(KillCause.SESSION_DEADLINE),
        ProofFailure.ProverKilled(KillCause.EXITED, "exit code 3"),
        ProofFailure.NotStarted("no proof until 2026-06-16T12:00:30Z, after the notary refused the upgrade: HTTP 503"),
        ProofFailure.LimitsExceeded("the online phase needs 4096 bytes"),
        ProofFailure.Other("prover worker errored"),
    )

    @Test
    fun every_failure_has_its_json() = runTest {
        assertEquals(golden("proof-failures.golden.jsonl").trimEnd('\n'), every.joinToString("\n") { ProofOutcomeJson.failure(it) })
    }

    @Test
    fun every_text_a_failure_carries_is_redacted_summary_included() = runTest {
        val leaky = "access_token=$TOKEN $TOKEN X-Api-Key: $API_KEY \"X-Sign-Date\":\"$SIGN_DATE\" X-Request-Sign=$SIGNATURE"
        val failures = listOf(
            ProofFailure.SessionClosed(1011, leaky),
            ProofFailure.NotaryUnreachable(leaky),
            ProofFailure.VersionMismatch(leaky),
            ProofFailure.SteamFailed(401, leaky),
            ProofFailure.LimitsExceeded(leaky),
            ProofFailure.ProverKilled(KillCause.EXITED, leaky),
            ProofFailure.NotStarted(leaky),
            ProofFailure.Other(leaky),
        )
        // The proof the backoff holds back quotes the failure before it, unredacted: the backoff sees it whole.
        val backoff = ProverBackoff()
        backoff.failed(ProofFailure.NotaryUnreachable(leaky), T0)
        val held = ProofFailure.NotStarted(assertNotNull(backoff.blocked(T0 + 1.seconds)))
        assertTrue(SIGNATURE in held.reason, "the backoff keeps the raw text")

        val json = (failures + held).map { ProofOutcomeJson.failure(it) }
        for (line in json) {
            for (secret in listOf(TOKEN.jwtPayload(), API_KEY, SIGN_DATE, SIGNATURE.removePrefix("dmar ed25519 "))) {
                assertFalse(secret in line, "a failure's JSON carries a secret: $line")
            }
        }
        assertEquals(golden("proof-failures-redacted.golden.jsonl").trimEnd('\n'), json.joinToString("\n"))
    }

    @Test
    fun a_host_line_is_one_json_object_with_its_level_and_fields_in_order() {
        assertEquals(
            """{"msg":"proof failed","level":"warn","dealId":"deal-1","pid":"42","reason":"the notary refused the upgrade: HTTP 503"}""",
            ProofOutcomeJson.notice(
                "proof failed",
                true,
                listOf("dealId" to "deal-1", "pid" to "42", "reason" to "the notary refused the upgrade: HTTP 503"),
            ),
        )
        assertEquals(
            """{"msg":"prover exited","level":"info","pid":"42","code":null,"signal":"SIGKILL","killedBySdk":"true"}""",
            ProofOutcomeJson.notice(
                "prover exited",
                false,
                listOf(
                    "pid" to "42",
                    "code" to null,
                    "signal" to "SIGKILL",
                    "killedBySdk" to "true",
                ),
            ),
        )
    }

    @Test
    fun the_prove_message_a_smoke_test_sends_matches_its_golden_file() = runTest {
        val prove = ToChild.Prove(
            id = 1,
            request = NotaryProofRequest(dealId = "deal-1", subjectSteamId = STEAM_ID, steamOfferId = "555", kind = "TRADE_OFFER"),
            // Unsigned, and good until 2100: a smoke test reaches the notary leg with it whenever it runs.
            steamAccessToken = steamToken(exp = Instant.parse("2100-01-01T00:00:00Z")),
            steamSessionId = "feedfacecafebeef01234567",
            settings = ProverSettings(notaryUrl = "ws://127.0.0.1:9/provenance/v1/", threadCount = 2, assetsDir = "/prover-assets"),
        )
        assertEquals(golden("prover-ipc-prove.json").trimEnd('\n'), ProverIpc.encode(prove))
    }

    private companion object {
        val TOKEN = steamToken()
        const val API_KEY = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9"
        const val SIGN_DATE = "1781611234"
        val SIGNATURE = "dmar ed25519 " + "ef".repeat(64)
    }
}
