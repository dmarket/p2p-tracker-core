package com.dmarket.p2p.sdk.prover

import com.dmarket.p2p.sdk.STEAM_ID
import com.dmarket.p2p.sdk.steamToken
import com.dmarket.p2p.tracker.notary.NotaryProofRequest
import com.dmarket.p2p.tracker.notary.ProofFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProverPiecesTest {
    private val request = NotaryProofRequest(dealId = "deal-1", subjectSteamId = STEAM_ID, steamOfferId = "555", kind = "TRADE_OFFER")
    private val settings = ProverSettings(notaryUrl = "wss://notary.test/provenance/v1/", threadCount = 4, assetsDir = "/opt/sdk")

    @Test
    fun the_ipc_round_trips_and_its_strings_hide_the_secrets() {
        val token = steamToken()
        val prove = ToChild.Prove(7, request, token, "abc123", settings)
        val headers = ToChild.Headers(7, headers = mapOf("X-Request-Sign" to "dmar ed25519 ff"))
        for (message in listOf(prove, headers)) {
            assertEquals(message, ProverIpc.toChild(ProverIpc.encode(message)))
        }
        assertTrue(ProverIpc.encode(prove).contains("\"type\":\"prove\""))
        assertFalse(token in prove.toString() || "abc123" in prove.toString())
        assertFalse("dmar ed25519 ff" in headers.toString())

        val answers = listOf(
            FromChild.HeadersWanted(7),
            FromChild.Proved(7, "cHJvb2Y="),
            FromChild.Failed(7, ProofFailure.UpgradeRefused(503)),
        )
        for (answer in answers) assertEquals(answer, ProverIpc.fromChild(ProverIpc.encode(answer)))
        assertEquals(
            FromChild.Failed(3, ProofFailure.SessionClosed(1013, "busy")),
            ProverIpc.fromChild("""{"type":"failed","id":3,"failure":{"type":"session_closed","code":1013,"reason":"busy"}}"""),
        )
    }

    @Test
    fun a_cgroup_limit_is_read_from_either_version() {
        assertEquals(536_870_912L, ProverMemory.limitBytes("536870912\n", null))
        assertNull(ProverMemory.limitBytes("max\n", "1"))
        assertEquals(805_306_368L, ProverMemory.limitBytes(null, "805306368"))
        assertNull(ProverMemory.limitBytes(null, "9223372036854771712"))
        assertNull(ProverMemory.limitBytes(null, null))
    }

    @Test
    fun memory_is_low_when_one_peak_does_not_fit_beside_the_sdk() {
        val mib = 1024L * 1024L
        assertTrue(ProverMemory.isLow(512 * mib, 120 * mib, 768 * mib))
        assertFalse(ProverMemory.isLow(1024 * mib, 120 * mib, 768 * mib))
        assertFalse(ProverMemory.isLow(null, 120 * mib, 768 * mib))
    }

    @Test
    fun prover_output_keeps_no_secret() {
        val token = steamToken()
        val sign = "dmar ed25519 " + "cd".repeat(64)
        val line = "upgrade X-Api-Key: pubkey X-Sign-Date: 1781611200 X-Request-Sign: $sign GET /x?access_token=$token jwt=$token"
        val redacted = ProverOutput.redact(line)
        assertFalse("pubkey" in redacted || "1781611200" in redacted || "cd".repeat(64) in redacted || token in redacted, redacted)
        assertTrue("<redacted>" in redacted)
    }
}
