package com.dmarket.p2p.tracker.model

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Serialise a [LifecycleEvent] to a compact JSON object string for delivery over a host callback
 * (the JS `startTrackerWithEvents(onEvent)` surface, and any future mobile host sink).
 *
 * Pure and platform-independent — no IO, no browser assumptions — so every host reuses the **same**
 * event vocabulary. Every field emitted here is an already-public id, an enum name, a count, a boolean, or
 * a **redacted and length-capped** failure summary or remote rejection reason; **no credential, token, cookie, or `device_id` is ever
 * carried** (guaranteed by [LifecycleEvent] itself — see its KDoc, which spells out how the free-text
 * `reason` fields are held to that). Keeping the encoder here, next to the sealed type, means the `when`
 * is exhaustiveness-checked by the compiler: a new variant fails to compile until mapped.
 *
 * The key vocabulary matches the debug harness's session-log shape (`JsEventObserver`) so there is a
 * single canonical event JSON across the repo; a `category` wrapper is intentionally omitted (this
 * surface carries lifecycle events only).
 */
fun LifecycleEvent.toWireJson(): String = buildJsonObject {
    when (val event = this@toWireJson) {
        LifecycleEvent.CycleStarted -> put("event", "CycleStarted")
        is LifecycleEvent.HeartbeatSent -> {
            put("event", "HeartbeatSent")
            put("ttlSeconds", event.ttlSeconds)
            put("tracking", event.trackingCount)
            put("directives", event.directiveCount)
        }
        is LifecycleEvent.DirectiveExecuted -> {
            put("event", "DirectiveExecuted")
            put("kind", event.kind)
            put("status", event.status)
            put("steamOfferId", event.steamOfferId)
        }
        is LifecycleEvent.DirectiveReportFailed -> {
            put("event", "DirectiveReportFailed")
            put("kind", event.kind)
            put("directiveId", event.directiveId)
            put("reason", event.reason)
        }
        is LifecycleEvent.DirectiveOutcomeResent -> {
            put("event", "DirectiveOutcomeResent")
            put("kind", event.kind)
            put("directiveId", event.directiveId)
            put("status", event.status)
            put("accepted", event.accepted)
        }
        is LifecycleEvent.HandledDirectiveSkipped -> {
            put("event", "HandledDirectiveSkipped")
            put("kind", event.kind)
            put("directiveId", event.directiveId)
        }
        is LifecycleEvent.DirectiveDropped -> {
            put("event", "DirectiveDropped")
            put("kind", event.kind)
            put("directiveId", event.directiveId)
            put("reason", event.reason)
        }
        is LifecycleEvent.DuplicateWriteSuppressed -> {
            put("event", "DuplicateWriteSuppressed")
            put("kind", event.kind)
            put("dealId", event.dealId)
            put("directiveId", event.directiveId)
            put("phase", event.phase)
            put("steamOfferId", event.steamOfferId)
        }
        is LifecycleEvent.SteamWriteDeferred -> {
            put("event", "SteamWriteDeferred")
            put("kind", event.kind)
            put("directiveId", event.directiveId)
            put("reason", event.reason)
            put("dealId", event.dealId)
            put("retryAfterSeconds", event.retryAfterSeconds)
        }
        is LifecycleEvent.CreateChainStopped -> {
            put("event", "CreateChainStopped")
            put("partnerSteamId", event.partnerSteamId)
            put("directiveId", event.directiveId)
            put("reason", event.reason)
            put("skipped", event.skipped)
        }
        is LifecycleEvent.SteamReadFailed -> {
            put("event", "SteamReadFailed")
            put("axis", event.axis)
            put("reason", event.reason)
        }
        is LifecycleEvent.HistoryCorrelationMiss -> {
            put("event", "HistoryCorrelationMiss")
            put("dealId", event.dealId)
            put("rows", event.rows)
            put("refetched", event.refetched)
        }
        is LifecycleEvent.DealLookupFailed -> {
            put("event", "DealLookupFailed")
            put("dealId", event.dealId)
            put("reason", event.reason)
        }
        is LifecycleEvent.ProgressStoreFailed -> {
            put("event", "ProgressStoreFailed")
            put("operation", event.operation)
            put("reason", event.reason)
        }
        is LifecycleEvent.WatchSummary -> {
            put("event", "WatchSummary")
            put("watched", event.watched)
            put("observed", event.observed)
            put("historyObserved", event.historyObserved)
            put("uncorrelated", event.uncorrelated)
            put("planned", event.planned)
            put("suppressed", event.suppressed)
            put("demanded", event.demanded)
        }
        is LifecycleEvent.TradeStatusReported -> {
            put("event", "TradeStatusReported")
            put("dealId", event.dealId)
            put("source", event.source)
            put("steamStatusCode", event.steamStatusCode)
        }
        is LifecycleEvent.TradeStatusReportFailed -> {
            put("event", "TradeStatusReportFailed")
            put("dealId", event.dealId)
            put("source", event.source)
            put("steamStatusCode", event.steamStatusCode)
            put("reason", event.reason)
        }
        is LifecycleEvent.ProofSubmitted -> {
            put("event", "ProofSubmitted")
            put("dealId", event.dealId)
            put("source", event.source)
            put("verified", event.verified)
            put("reason", event.reason)
            put("prover", event.prover)
            put("demanded", event.demanded)
        }
        is LifecycleEvent.ProofFailed -> {
            put("event", "ProofFailed")
            put("dealId", event.dealId)
            put("source", event.source)
            put("reason", event.reason)
            put("demanded", event.demanded)
        }
        is LifecycleEvent.FreshProofDemanded -> {
            put("event", "FreshProofDemanded")
            put("dealId", event.dealId)
            put("tradeId", event.tradeId)
            put("proveAfter", event.proveAfter)
        }
        is LifecycleEvent.ProofSuppressed -> {
            put("event", "ProofSuppressed")
            put("dealId", event.dealId)
            put("source", event.source)
            put("reason", event.reason)
            // Only the parked-prover reason carries one; omitted rather than null for the others so a reader
            // does not have to tell "no deadline" from "a deadline of nothing".
            event.retryAfterSeconds?.let { put("retryAfterSeconds", it) }
        }
        is LifecycleEvent.TradeStatusReportDeferred -> {
            put("event", "TradeStatusReportDeferred")
            put("dealId", event.dealId)
            put("source", event.source)
            put("steamStatusCode", event.steamStatusCode)
            put("reason", event.reason)
        }
        is LifecycleEvent.CycleFailed -> {
            put("event", "CycleFailed")
            put("reason", event.reason)
        }
        is LifecycleEvent.CredentialRefreshed -> {
            put("event", "CredentialRefreshed")
            put("axis", event.axis)
            put("ok", event.ok)
        }
        is LifecycleEvent.ReLoginNeeded -> {
            put("event", "ReLoginNeeded")
            put("axis", event.axis)
        }
        is LifecycleEvent.MarketplaceServerError -> {
            put("event", "MarketplaceServerError")
            put("endpoint", event.endpoint)
            put("statusCode", event.statusCode)
        }
        is LifecycleEvent.LinkedSteamIdMismatch -> {
            put("event", "LinkedSteamIdMismatch")
            put("linkedSteamId", event.linkedSteamId)
            put("tokenSteamId", event.tokenSteamId)
        }
        is LifecycleEvent.SteamSessionAccountMismatch -> {
            put("event", "SteamSessionAccountMismatch")
            put("kind", event.kind)
            put("tokenSteamId", event.tokenSteamId)
        }
        is LifecycleEvent.CycleCompleted -> {
            put("event", "CycleCompleted")
            put("directivesExecuted", event.directivesExecuted)
            put("reportsSent", event.reportsSent)
            put("proofsSubmitted", event.proofsSubmitted)
            put("watching", event.watching)
        }
    }
}.toString()
