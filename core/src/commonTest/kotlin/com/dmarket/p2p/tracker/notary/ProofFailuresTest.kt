package com.dmarket.p2p.tracker.notary

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What ended a proof, read from what its host saw: the notary's refusals first, then Steam's, then the text. */
class ProofFailuresTest {
    private fun classify(
        upgradeStatus: Int? = null,
        closeCode: Int? = null,
        closeReason: String? = null,
        targetStatus: Int? = null,
        targetError: String? = null,
        notaryConnected: Boolean = true,
        message: String? = "boom",
    ) = ProofFailures.classify(upgradeStatus, closeCode, closeReason, targetStatus, targetError, notaryConnected, message)

    @Test
    fun a_refused_upgrade_is_its_status() {
        for (status in listOf(401, 403, 503, 400)) {
            assertEquals(ProofFailure.UpgradeRefused(status), classify(upgradeStatus = status, notaryConnected = false))
        }
    }

    @Test
    fun a_close_after_the_upgrade_is_its_code_and_a_normal_close_is_not_a_cause() {
        assertEquals(ProofFailure.SessionClosed(1013, "queue full"), classify(closeCode = 1013, closeReason = "queue full"))
        assertEquals(ProofFailure.SessionClosed(4029), classify(closeCode = 4029, closeReason = " "))
        assertEquals(ProofFailure.SessionClosed(4008), classify(closeCode = 4008))
        assertEquals(ProofFailure.Other("boom"), classify(closeCode = 1000))
    }

    @Test
    fun the_version_error_wins_over_the_close_that_follows_it() {
        val text = "verifier error: Prover version does not match with verifier: 0.1.12 != 0.1.13"
        assertEquals(ProofFailure.VersionMismatch(text), classify(closeCode = 1011, message = text))
    }

    @Test
    fun a_notary_that_never_answered_is_unreachable() {
        assertEquals(
            ProofFailure.NotaryUnreachable("notary handshake failed"),
            classify(notaryConnected = false, message = "notary handshake failed"),
        )
        assertEquals(ProofFailure.NotaryUnreachable("no answer to the upgrade"), classify(notaryConnected = false, message = ""))
    }

    @Test
    fun steam_failures_name_the_status_when_there_is_one() {
        assertEquals(ProofFailure.SteamFailed(detail = "ECONNRESET"), classify(targetError = "ECONNRESET"))
        assertEquals(ProofFailure.SteamFailed(401, "Steam answered HTTP 401"), classify(targetStatus = 401))
        assertEquals(
            ProofFailure.SteamFailed(detail = "invalid peer certificate: UnknownIssuer"),
            classify(message = "invalid peer certificate: UnknownIssuer"),
        )
        assertNull(classify(targetStatus = 200, message = null), "a 2xx proof is no failure")
    }

    @Test
    fun the_limits_refusal_is_its_own_case() {
        val text = "record layer error: attempted to decrypt more data in the online phase than was configured, " +
            "increase `max_recv_online` in the config: current=16, additional=786, max=32"
        assertEquals(ProofFailure.LimitsExceeded(text), classify(message = text))
    }

    @Test
    fun anything_else_keeps_the_provers_words() {
        assertEquals(ProofFailure.Other("context mux error"), classify(message = "context mux error"))
        assertEquals(ProofFailure.Other("the prover failed without a message"), classify(message = ""))
    }
}
