package com.dmarket.p2p.tracker.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FailureSummaryTest {
    private val secretJwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI3NjU2MTE5ODAwMDAwMDAwMSJ9.c2ln"

    @Test
    fun summary_scrubs_an_access_token_from_a_free_form_message() {
        // Verbatim shape of Ktor's pre-fix ClientRequestException message. The core no longer produces it,
        // but this layer also covers throwables the core does not own (the engine, a host-supplied client).
        val raw = "Client request(GET https://api.steampowered.com/IEconService/GetTradeOffers/v1/" +
            "?access_token=$secretJwt&get_sent_offers=1) invalid: 403. Text: \"{\"nonce\":\"$secretJwt\"}\""

        val summary = RuntimeException(raw).redactedSummary()

        assertFalse(secretJwt in summary, summary)
        assertTrue("403" in summary, "the status must survive: $summary")
        assertTrue("RuntimeException" in summary, "the class names the failure: $summary")
    }

    @Test
    fun summary_is_capped() {
        val summary = RuntimeException("x".repeat(10_000)).redactedSummary()

        assertTrue(summary.length < 300, "expected a capped summary, got ${summary.length} chars")
        assertTrue(summary.endsWith("…[truncated]"), summary)
    }

    @Test
    fun summary_names_the_exception_class() {
        assertEquals("IllegalStateException: boom", IllegalStateException("boom").redactedSummary())
    }

    @Test
    fun a_null_message_degrades_to_a_dash() {
        assertEquals("IllegalStateException: -", IllegalStateException().redactedSummary())
    }

    @Test
    fun a_renamed_secret_param_is_honoured() {
        val summary = RuntimeException("GET https://h/p?steam_tok=$secretJwt failed")
            .redactedSummary(NetworkRedaction.plusSecretParam("steam_tok"))

        assertFalse(secretJwt in summary, summary)
    }
}
