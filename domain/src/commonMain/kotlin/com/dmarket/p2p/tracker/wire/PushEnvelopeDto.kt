package com.dmarket.p2p.tracker.wire

import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.PushSignal
import com.dmarket.p2p.tracker.model.Wake
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * A backend→client push wake-up frame (host-delivered; the lib owns no push transport). Push is a nudge to run a
 * cycle **now** instead of waiting for the next scheduled wake: it carries no cadence and at most a hint.
 *
 * The mobile wake is contract d455888 §2.2 (`specs/mobile-tracker.md`, still a draft PR, so its shape lives only here):
 * a flat map of strings `{v, type, wakeId, transactionId?, reason, proveAfter?, deadline?, issuedAt}`. The extension's
 * frames (`wake_all`, `offers_page`) share this DTO. `ignoreUnknownKeys` lets extra keys decode harmlessly.
 */
@Serializable
data class PushEnvelopeDto(
    @SerialName("type") val type: String, // "wake_all" | "wake_deal"
    /** Payload version, `"1"`; an unknown one reads as `wake_all` (§2.2). */
    @SerialName("v") val v: String? = null,
    /** Unique per send; echoed in the heartbeat and the wake ack. */
    @SerialName("wakeId") val wakeId: String? = null,
    /** `wake_deal` only: the deal record's id, a hint that is only logged; the sync is full whatever it names. */
    @SerialName("transactionId") val transactionId: String? = null,
    /** The extension's deal hint; the mobile wake names a deal by [transactionId] instead. */
    @SerialName("dealId") val dealId: String? = null,
    /** Why the host asks; [OFFERS_PAGE_REASON] turns the frame into [PushSignal.CheckSentOffers]. */
    @SerialName("reason") val reason: String? = null,
    /** ISO-8601; not acted on yet (DMA-664). */
    @SerialName("proveAfter") val proveAfter: String? = null,
    /** RFC 3339, when the work this wake serves times out; a wake past it is dropped without a sync. */
    @SerialName("deadline") val deadline: String? = null,
    /** ISO-8601, when the backend sent the wake. */
    @SerialName("issuedAt") val issuedAt: String? = null,
)

/** The payload version this client reads (§2.2). */
const val WAKE_PAYLOAD_VERSION = "1"

/** [PushEnvelopeDto.reason] of a host that shows Steam's trade-offers page and wants its sent offers checked. */
const val OFFERS_PAGE_REASON = "offers_page"

/**
 * Decodes an inbound [PushEnvelopeDto] into a domain [PushSignal], or `null` for an unknown/malformed
 * frame (so an unexpected backend message is ignored rather than crashing the push loop). The
 * `isNotBlank` guard protects [DealId]'s non-blank `require`.
 */
fun PushEnvelopeDto.toSignalOrNull(): PushSignal? = when (type) {
    "wake_all" -> if (reason == OFFERS_PAGE_REASON) PushSignal.CheckSentOffers else PushSignal.WakeAll
    "wake_deal" -> dealId?.takeIf { it.isNotBlank() }?.let { PushSignal.WakeForDeal(DealId(it)) }
    else -> null
}

/**
 * The mobile reading of the envelope (§2.2): the [PushSignal] plus what the wake carries beside it, or `null` for a
 * frame that is no wake. A `wake_deal` without the extension's `dealId` (the mobile one names a `transactionId`), an
 * unknown `v`, or any frame with a `wakeId` the type of which is not known reads as [PushSignal.WakeAll]: every wake
 * ends in a full sync. A timestamp that does not parse reads as absent rather than refusing the wake.
 */
fun PushEnvelopeDto.toWakeOrNull(): Wake? {
    val knownVersion = v == null || v == WAKE_PAYLOAD_VERSION
    val signal = (if (knownVersion) toSignalOrNull() else null)
        ?: PushSignal.WakeAll.takeIf { type == "wake_deal" || !knownVersion || !wakeId.isNullOrBlank() }
        ?: return null
    return Wake(
        signal = signal,
        type = type,
        reason = reason,
        proveAfter = instantOrNull(proveAfter),
        issuedAt = instantOrNull(issuedAt),
        wakeId = wakeId?.takeIf { it.isNotBlank() },
        transactionId = transactionId?.takeIf { it.isNotBlank() },
        deadline = instantOrNull(deadline),
    )
}

private fun instantOrNull(value: String?): Instant? = value?.let { runCatching { Instant.parse(it) }.getOrNull() }

/**
 * Parses a raw push **payload string** (delivered by the host's own push handler) into a
 * [PushEnvelopeDto], or `null` if it is absent/unparseable. This is the only push logic the core owns.
 */
fun parsePushEnvelope(json: String): PushEnvelopeDto? =
    runCatching { TrackerJson.decodeFromString(PushEnvelopeDto.serializer(), json) }.getOrNull()
