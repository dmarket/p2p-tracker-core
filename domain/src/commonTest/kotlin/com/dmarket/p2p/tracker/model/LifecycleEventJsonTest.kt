package com.dmarket.p2p.tracker.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LifecycleEventJsonTest {
    private fun parse(event: LifecycleEvent) = Json.parseToJsonElement(event.toWireJson()).jsonObject

    @Test
    fun linked_steam_id_mismatch_carries_only_the_two_public_ids() {
        val json = parse(LifecycleEvent.LinkedSteamIdMismatch("76561198000000001", "76561198000000099"))
        assertEquals("LinkedSteamIdMismatch", json["event"]?.jsonPrimitive?.content)
        assertEquals("76561198000000001", json["linkedSteamId"]?.jsonPrimitive?.content)
        assertEquals("76561198000000099", json["tokenSteamId"]?.jsonPrimitive?.content)
        // Exactly event + the two public Steam ids — nothing else can have leaked into the frame.
        assertEquals(setOf("event", "linkedSteamId", "tokenSteamId"), json.keys)
    }

    @Test
    fun heartbeat_sent_carries_counts_and_ttl() {
        val json = parse(LifecycleEvent.HeartbeatSent(ttlSeconds = 180, trackingCount = 3, directiveCount = 1))
        assertEquals("HeartbeatSent", json["event"]?.jsonPrimitive?.content)
        assertEquals("180", json["ttlSeconds"]?.jsonPrimitive?.content)
        assertEquals("3", json["tracking"]?.jsonPrimitive?.content)
        assertEquals("1", json["directives"]?.jsonPrimitive?.content)
    }

    @Test
    fun directive_executed_maps_kind_status_and_optional_offer_id() {
        val json = parse(LifecycleEvent.DirectiveExecuted("create_offer", "NEEDS_CONFIRMATION", "778899"))
        assertEquals("DirectiveExecuted", json["event"]?.jsonPrimitive?.content)
        assertEquals("create_offer", json["kind"]?.jsonPrimitive?.content)
        assertEquals("NEEDS_CONFIRMATION", json["status"]?.jsonPrimitive?.content)
        assertEquals("778899", json["steamOfferId"]?.jsonPrimitive?.content)
    }

    @Test
    fun proof_submitted_carries_the_backend_verdict() {
        // `verified` is the whole point of the event: a delivered-but-rejected proof is terminal and moves
        // no counter, so the frame has to say so rather than leaving it to be inferred from an absence.
        val json = parse(LifecycleEvent.ProofSubmitted("deal1", "offer", verified = false))
        assertEquals("ProofSubmitted", json["event"]?.jsonPrimitive?.content)
        assertEquals("deal1", json["dealId"]?.jsonPrimitive?.content)
        assertEquals("offer", json["source"]?.jsonPrimitive?.content)
        assertEquals("false", json["verified"]?.jsonPrimitive?.content)
        assertEquals(setOf("event", "dealId", "source", "verified", "reason", "prover", "demanded"), json.keys)
    }

    @Test
    fun proof_submitted_carries_the_rejection_reason_and_the_prover() {
        // `verified = false` alone cannot be acted on: "the backend rejected a real proof" and "we submitted
        // an empty stub because no notary is configured" are the same frame without these two fields, and they
        // call for opposite responses. Both were reachable in-core and both were being dropped.
        val json = parse(
            LifecycleEvent.ProofSubmitted("deal1", "offer", verified = false, reason = "empty proof_payload", prover = "noop"),
        )
        assertEquals("empty proof_payload", json["reason"]?.jsonPrimitive?.content)
        assertEquals("noop", json["prover"]?.jsonPrimitive?.content)
    }

    @Test
    fun proof_failed_carries_the_axis_and_a_reason() {
        val json = parse(LifecycleEvent.ProofFailed("deal1", "history", "notary handshake failed"))
        assertEquals("ProofFailed", json["event"]?.jsonPrimitive?.content)
        assertEquals("deal1", json["dealId"]?.jsonPrimitive?.content)
        assertEquals("history", json["source"]?.jsonPrimitive?.content)
        assertEquals("notary handshake failed", json["reason"]?.jsonPrimitive?.content)
    }

    @Test
    fun proof_suppressed_names_the_transition_and_why() {
        val json = parse(LifecycleEvent.ProofSuppressed("deal1", "offer", "already refused"))
        assertEquals("ProofSuppressed", json["event"]?.jsonPrimitive?.content)
        assertEquals("deal1", json["dealId"]?.jsonPrimitive?.content)
        assertEquals("offer", json["source"]?.jsonPrimitive?.content)
        assertEquals("already refused", json["reason"]?.jsonPrimitive?.content)
    }

    @Test
    fun report_deferred_names_the_transition_and_why() {
        val json = parse(LifecycleEvent.TradeStatusReportDeferred("deal1", "offer", 2, "awaiting proof"))
        assertEquals("TradeStatusReportDeferred", json["event"]?.jsonPrimitive?.content)
        assertEquals("deal1", json["dealId"]?.jsonPrimitive?.content)
        assertEquals("offer", json["source"]?.jsonPrimitive?.content)
        assertEquals(2, json["steamStatusCode"]?.jsonPrimitive?.content?.toInt())
        assertEquals("awaiting proof", json["reason"]?.jsonPrimitive?.content)
    }

    @Test
    fun cycle_started_is_a_bare_event() {
        assertEquals("""{"event":"CycleStarted"}""", LifecycleEvent.CycleStarted.toWireJson())
    }

    @Test
    fun cycle_completed_maps_all_counters() {
        val json = parse(LifecycleEvent.CycleCompleted(directivesExecuted = 2, reportsSent = 1, proofsSubmitted = 0, watching = 4))
        assertEquals("CycleCompleted", json["event"]?.jsonPrimitive?.content)
        assertEquals("2", json["directivesExecuted"]?.jsonPrimitive?.content)
        assertEquals("1", json["reportsSent"]?.jsonPrimitive?.content)
        assertEquals("0", json["proofsSubmitted"]?.jsonPrimitive?.content)
        assertEquals("4", json["watching"]?.jsonPrimitive?.content)
    }

    @Test
    fun marketplace_server_error_carries_endpoint_and_status() {
        val json = parse(LifecycleEvent.MarketplaceServerError(endpoint = "heartbeat", statusCode = 404))
        assertEquals("MarketplaceServerError", json["event"]?.jsonPrimitive?.content)
        assertEquals("heartbeat", json["endpoint"]?.jsonPrimitive?.content)
        assertEquals("404", json["statusCode"]?.jsonPrimitive?.content)
        assertEquals(setOf("event", "endpoint", "statusCode"), json.keys)
    }

    @Test
    fun a_watch_summary_separates_deduped_silence_from_an_unseen_axis() {
        val json = parse(
            LifecycleEvent.WatchSummary(
                watched = 4,
                observed = 4,
                historyObserved = 0,
                uncorrelated = 4,
                planned = 0,
                suppressed = 4,
                demanded = 0,
            ),
        )
        assertEquals("WatchSummary", json["event"]?.jsonPrimitive?.content)
        assertEquals("4", json["uncorrelated"]?.jsonPrimitive?.content)
        assertEquals("0", json["planned"]?.jsonPrimitive?.content)
        assertEquals("4", json["suppressed"]?.jsonPrimitive?.content)
        assertEquals(
            setOf("event", "watched", "observed", "historyObserved", "uncorrelated", "planned", "suppressed", "demanded"),
            json.keys,
        )
    }

    @Test
    fun a_watch_summary_that_answered_a_mark_does_not_read_as_nothing_changed() {
        // The shape this counter exists for. A demanded re-attestation plans no report and its unchanged code
        // lands in `suppressed`, so without `demanded` this frame is byte-identical to an ordinary deduped
        // cycle — printed while a money-critical MPC session ran.
        val json = parse(
            LifecycleEvent.WatchSummary(
                watched = 1,
                observed = 1,
                historyObserved = 0,
                uncorrelated = 0,
                planned = 0,
                suppressed = 1,
                demanded = 1,
            ),
        )
        assertEquals("1", json["demanded"]?.jsonPrimitive?.content)
    }

    @Test
    fun a_freshness_demand_carries_both_wire_values() {
        // The only frame in the stream that does, which is the whole reason it is an event rather than a flag:
        // an exported log has to be able to answer "did the mark reach this device, for which trade, and which
        // mark" — the one question a stranded payout is investigated with.
        val json = parse(LifecycleEvent.FreshProofDemanded("deal1", "744935517744884653", "2026-09-02T10:15:30Z"))

        assertEquals("FreshProofDemanded", json["event"]?.jsonPrimitive?.content)
        assertEquals("deal1", json["dealId"]?.jsonPrimitive?.content)
        assertEquals("744935517744884653", json["tradeId"]?.jsonPrimitive?.content)
        assertEquals("2026-09-02T10:15:30Z", json["proveAfter"]?.jsonPrimitive?.content)
        assertEquals(setOf("event", "dealId", "tradeId", "proveAfter"), json.keys)
    }

    @Test
    fun a_proof_frame_says_whether_it_answered_a_mark() {
        // `proofsSubmitted` only moves in the verified branch, so a demand refused on its ladder reports 0 in
        // every CycleCompleted; and a demanded proof arrives with no accompanying report, which is otherwise
        // the signature of nothing having happened.
        assertEquals(
            "true",
            parse(LifecycleEvent.ProofSubmitted("deal1", "history", verified = true, demanded = true))["demanded"]
                ?.jsonPrimitive?.content,
        )
        assertEquals(
            "false",
            parse(LifecycleEvent.ProofSubmitted("deal1", "offer", verified = true))["demanded"]?.jsonPrimitive?.content,
        )
        assertEquals(
            "true",
            parse(LifecycleEvent.ProofFailed("deal1", "history", "notary unreachable", demanded = true))["demanded"]
                ?.jsonPrimitive?.content,
        )
    }

    @Test
    fun a_failed_status_report_carries_the_axis_and_the_code_that_did_not_land() {
        val json = parse(LifecycleEvent.TradeStatusReportFailed("deal1", "history", 12, "no result for this report"))
        assertEquals("TradeStatusReportFailed", json["event"]?.jsonPrimitive?.content)
        assertEquals("history", json["source"]?.jsonPrimitive?.content)
        assertEquals("12", json["steamStatusCode"]?.jsonPrimitive?.content)
        assertEquals("no result for this report", json["reason"]?.jsonPrimitive?.content)
    }

    @Test
    fun encodes_a_deferred_create_with_its_reason_and_retry_hint() {
        val json = parse(
            LifecycleEvent.SteamWriteDeferred(
                kind = "create_offer",
                directiveId = "dir-1",
                reason = "partner cooling down after a steam refusal",
                dealId = "deal-1",
                retryAfterSeconds = 120,
            ),
        )
        assertEquals("SteamWriteDeferred", json["event"]?.jsonPrimitive?.content)
        assertEquals("create_offer", json["kind"]?.jsonPrimitive?.content)
        assertEquals("dir-1", json["directiveId"]?.jsonPrimitive?.content)
        assertEquals("partner cooling down after a steam refusal", json["reason"]?.jsonPrimitive?.content)
        assertEquals("deal-1", json["dealId"]?.jsonPrimitive?.content)
        assertEquals("120", json["retryAfterSeconds"]?.jsonPrimitive?.content)
    }

    @Test
    fun encodes_a_stopped_create_chain_with_its_partner_and_skipped_count() {
        val json = parse(
            LifecycleEvent.CreateChainStopped(
                partnerSteamId = "76561199497281579",
                directiveId = "dir-7",
                reason = "you have sent too many trade offers",
                skipped = 21,
            ),
        )
        assertEquals("CreateChainStopped", json["event"]?.jsonPrimitive?.content)
        assertEquals("76561199497281579", json["partnerSteamId"]?.jsonPrimitive?.content)
        assertEquals("dir-7", json["directiveId"]?.jsonPrimitive?.content)
        assertEquals("21", json["skipped"]?.jsonPrimitive?.content)
    }

    @Test
    fun every_variant_emits_a_nonblank_event_tag() {
        // Hand-maintained on purpose: the encoder's `when` is exhaustiveness-checked, but nothing forces a
        // NEW variant to be exercised here — so a variant added without a sample silently loses its coverage.
        val samples = listOf(
            LifecycleEvent.CycleStarted,
            LifecycleEvent.HeartbeatSent(60, 0, 0),
            LifecycleEvent.DirectiveExecuted("create_offer", "SUCCESS"),
            LifecycleEvent.DirectiveReportFailed("create_offer", "d1", "rejected"),
            LifecycleEvent.DirectiveOutcomeResent("cancel_offer", "d2", "SUCCESS", accepted = true),
            LifecycleEvent.HandledDirectiveSkipped("report_inventory", "d3"),
            LifecycleEvent.DirectiveDropped("create_offer", "d4", "missing partner"),
            LifecycleEvent.DuplicateWriteSuppressed("create_offer", "deal1", "d5", "COMPLETED", "778899"),
            LifecycleEvent.SteamWriteDeferred("create_offer", "d6", "partner cooling down", "deal1", 120),
            LifecycleEvent.CreateChainStopped("76561199497281579", "d7", "too many trade offers", 21),
            LifecycleEvent.SteamReadFailed("offer", "500"),
            LifecycleEvent.HistoryCorrelationMiss("deal1", rows = 51, refetched = true),
            LifecycleEvent.DealLookupFailed("deal1", "404"),
            LifecycleEvent.ProgressStoreFailed("loadReported", "storage unavailable"),
            LifecycleEvent.WatchSummary(4, 4, 4, 0, 1, 3, 1),
            LifecycleEvent.TradeStatusReported("deal1", "offer", 9),
            LifecycleEvent.TradeStatusReportFailed("deal1", "history", 12, "rejected"),
            LifecycleEvent.ProofSubmitted("deal1", "offer", verified = true),
            LifecycleEvent.ProofFailed("deal1", "offer", "notary unreachable"),
            LifecycleEvent.ProofSuppressed("deal1", "offer", "already refused"),
            LifecycleEvent.FreshProofDemanded("deal1", "744935517744884653", "2026-09-02T10:15:30Z"),
            LifecycleEvent.TradeStatusReportDeferred("deal1", "offer", 2, "awaiting proof"),
            LifecycleEvent.CredentialRefreshed("steam", ok = true),
            LifecycleEvent.ReLoginNeeded("marketplace"),
            LifecycleEvent.MarketplaceServerError("heartbeat", 404),
            LifecycleEvent.LinkedSteamIdMismatch("1", "2"),
            LifecycleEvent.SteamSessionAccountMismatch("create_offer", "76561198000000001"),
            LifecycleEvent.CycleFailed("storage unavailable"),
            LifecycleEvent.CycleCompleted(0, 0, 0, 0),
        )
        for (event in samples) {
            val tag = parse(event)["event"]?.jsonPrimitive?.content
            assertTrue(!tag.isNullOrBlank(), "missing event tag for $event")
        }
    }
}
