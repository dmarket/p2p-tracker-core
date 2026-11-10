package com.dmarket.p2p.tracker.runtime

import com.dmarket.p2p.tracker.port.steam.CreateOfferResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The outcome JSON a JS host receives for a create ([awaitDirectiveOutcome], and the debug harness's create entry).
 * One serialiser, so the shipped facade and the debug console cannot describe one result two ways.
 */
/** The outcome JSON for a call that named no deal: nothing ran. */
fun missingDealIdJson(): String = buildJsonObject {
    put("ok", false)
    put("error", "missing deal_id")
}.toString()

fun CreateOfferResult.toOutcomeJson(): String {
    val result = this
    return buildJsonObject {
        // A replayed duplicate is `ok` too: the offer it names really exists (this device created it),
        // so a caller that asked twice must not be shown a failure for its own retry.
        put(
            "ok",
            result is CreateOfferResult.NeedsConfirmation ||
                result is CreateOfferResult.Created ||
                result is CreateOfferResult.AlreadyCreated,
        )
        when (result) {
            is CreateOfferResult.NeedsConfirmation -> {
                put("status", "needs_confirmation")
                put("steamOfferId", result.offerId.value)
            }
            is CreateOfferResult.Created -> {
                put("status", "created")
                put("steamOfferId", result.offerId.value)
            }
            is CreateOfferResult.Failed -> {
                put("status", "failed")
                put("error", result.error)
                // The coded cause, classified inside the lib against Steam's own refusal wording. This
                // is the field a host should branch on and show: `error` is free-form third-party text
                // that may name urls, ids or the counterparty's persona, so a host relaying it to an
                // untrusted page (the extension's FE bridge does) must send only this.
                put("cause", result.cause.name)
            }
            // Blocked before any Steam write: the DMarket-linked Steam account ≠ the logged-in session.
            is CreateOfferResult.AccountMismatch -> {
                put("status", "account_mismatch")
                put("linkedSteamId", result.linkedSteamId.value)
                put("tokenSteamId", result.tokenSteamId.value)
            }
            // Duplicate request for a deal this device already created the offer for: answer with the
            // FIRST result (so a caller that asked twice renders the real offer, not an error) and flag
            // it, rather than writing a second live offer to Steam.
            is CreateOfferResult.AlreadyCreated -> {
                put("status", "needs_confirmation")
                put("steamOfferId", result.offerId.value)
                put("duplicate", true)
            }
            // Duplicate arriving while the first create is still in flight — no offer id to replay yet.
            is CreateOfferResult.CreateInFlight -> {
                put("status", "create_in_flight")
                put("duplicate", true)
            }
            // Deferred before any Steam write: Steam refused a create for this partner (or the whole
            // surface) recently, so the FE can offer "try again in N seconds" instead of a dead error.
            is CreateOfferResult.Throttled -> {
                put("status", "throttled")
                put("scope", result.scope.name.lowercase())
                put("retryAfterSeconds", result.retryAfterSeconds)
            }
            // Refused before any Steam write while the host keeps offer creation paused.
            CreateOfferResult.CreatesPaused -> put("status", "creates_paused")
        }
    }.toString()
}
