package com.dmarket.p2p.headless

import com.dmarket.p2p.tracker.engine.FailedCancelKind
import com.dmarket.p2p.tracker.engine.ProofSkipReason
import com.dmarket.p2p.tracker.loop.STEAM_SESSION_MISSING_DROP_REASON
import com.dmarket.p2p.tracker.loop.TOKEN_EXPIRING_DEFER_REASON
import com.dmarket.p2p.tracker.model.LifecycleEvent
import com.dmarket.p2p.tracker.model.toWireJson
import com.dmarket.p2p.tracker.notary.PROOF_TOKEN_EXPIRING_REASON
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The wire JSON of every event a host's health or log reads, pinned byte for byte: `onEvent` hands the host these
 * strings, so a renamed key is a broken host. The golden file is one event per line, in the order below; a host's
 * own tests can take their fixtures from it.
 */
class EventContractTest {
    private val events: List<LifecycleEvent> = listOf(
        // Read by health.
        LifecycleEvent.CycleStarted,
        LifecycleEvent.HeartbeatSent(ttlSeconds = 100, trackingCount = 2, directiveCount = 1),
        LifecycleEvent.DirectiveDropped("cancel_offer", "dir-2", STEAM_SESSION_MISSING_DROP_REASON, dealId = "deal-1"),
        LifecycleEvent.ReLoginNeeded(axis = "steam"),
        LifecycleEvent.MarketplaceServerError(endpoint = "heartbeat", statusCode = 502),
        LifecycleEvent.LinkedSteamIdMismatch(linkedSteamId = STEAM_ID, tokenSteamId = OTHER_STEAM_ID),
        LifecycleEvent.SteamReadFailed(axis = "offers", reason = "HTTP 429"),
        LifecycleEvent.SteamWriteDeferred("create_offer", "dir-1", TOKEN_EXPIRING_DEFER_REASON, dealId = "deal-1", retryAfterSeconds = 60),
        LifecycleEvent.CycleCompleted(directivesExecuted = 1, reportsSent = 2, proofsSubmitted = 1, watching = 3),
        LifecycleEvent.DirectiveExecuted("cancel_offer", "FAILED", steamOfferId = "555", dealId = "deal-1", error = "Steam refused it"),
        LifecycleEvent.FailedCancelChecked("deal-1", "555", FailedCancelKind.ALREADY_TRADED.wireName, offerState = 3),
        LifecycleEvent.ProofFailed("deal-1", "offer", reason = "proof failed: $PROOF_TOKEN_EXPIRING_REASON", demanded = true),
        LifecycleEvent.ProofSubmitted("deal-1", "offer", verified = false, reason = "refused", prover = "wasm", demanded = true),
        LifecycleEvent.ProofSuppressed("deal-1", "offer", ProofSkipReason.NO_PROVER.message, retryAfterSeconds = 30),
        LifecycleEvent.TradeStatusReported("deal-1", "offer", steamStatusCode = 2),
        LifecycleEvent.DuplicateWriteSuppressed("create_offer", "deal-1", "dir-3", phase = "completed", steamOfferId = "777"),
        LifecycleEvent.CreateUnanswered("deal-1", "dir-1", error = "timeout"),
        // Read by the log alone, for its level.
        LifecycleEvent.CycleFailed(reason = "boom"),
        LifecycleEvent.DirectiveReportFailed("create_offer", "dir-1", reason = "HTTP 500"),
        LifecycleEvent.TradeStatusReportFailed("deal-1", "offer", steamStatusCode = 3, reason = "HTTP 500"),
        LifecycleEvent.LinkedSteamIdUnknown(tokenSteamId = STEAM_ID),
    )

    @Test
    fun every_event_health_and_the_log_read_keeps_its_wire_json() = runTest {
        assertEquals(21, events.size)
        val expected = golden("lifecycle-events.golden.jsonl").trimEnd('\n')
        assertEquals(expected, events.joinToString("\n") { it.toWireJson() })
    }

    @Test
    fun each_golden_line_names_its_event() = runTest {
        val names = golden("lifecycle-events.golden.jsonl").trimEnd('\n').lines().map { js("JSON.parse")(it).event as String }
        assertEquals(events.map { it::class.simpleName }, names)
    }
}
