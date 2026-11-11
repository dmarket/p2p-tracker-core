package com.dmarket.p2p.tracker.wire

import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.PushSignal
import com.dmarket.p2p.tracker.model.Wake
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * A backend→client push wake-up frame (host-delivered; the lib owns no push transport). Under the
 * C1 contract, push is just a nudge to run a cycle **now** instead of waiting
 * for the next scheduled wake — it carries no cadence (cadence is client-owned + the heartbeat
 * `ttlSeconds`) and at most a deal hint.
 *
 * **PROVISIONAL** — the mobile wake `{type, dealId?, reason, proveAfter?, issuedAt}` is described in DMA-659 and not
 * yet in the contract. A plain `type` string + `ignoreUnknownKeys` means an unknown type or extra fields decode
 * harmlessly, so only this DTO, [toSignalOrNull] and [toWakeOrNull] change when the real push schema lands.
 */
@Serializable
data class PushEnvelopeDto(
    @SerialName("type") val type: String, // "wake_all" | "wake_deal"
    @SerialName("dealId") val dealId: String? = null,
    /** Why the host asks; [OFFERS_PAGE_REASON] turns the frame into [PushSignal.CheckSentOffers]. */
    @SerialName("reason") val reason: String? = null,
    /** ISO-8601; not acted on yet (DMA-664). */
    @SerialName("proveAfter") val proveAfter: String? = null,
    /** ISO-8601, when the backend sent the wake. */
    @SerialName("issuedAt") val issuedAt: String? = null,
)

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
 * The mobile reading of the envelope: the [PushSignal] plus what the wake carries beside it, or `null` when the
 * signal is `null` (an unknown `type`, or a `wake_deal` without a deal). A timestamp that does not parse reads as
 * absent rather than refusing the wake.
 */
fun PushEnvelopeDto.toWakeOrNull(): Wake? = toSignalOrNull()?.let { signal ->
    Wake(signal = signal, type = type, reason = reason, proveAfter = instantOrNull(proveAfter), issuedAt = instantOrNull(issuedAt))
}

private fun instantOrNull(value: String?): Instant? = value?.let { runCatching { Instant.parse(it) }.getOrNull() }

/**
 * Parses a raw push **payload string** (delivered by the host's own push handler) into a
 * [PushEnvelopeDto], or `null` if it is absent/unparseable. This is the only push logic the core owns.
 */
fun parsePushEnvelope(json: String): PushEnvelopeDto? =
    runCatching { TrackerJson.decodeFromString(PushEnvelopeDto.serializer(), json) }.getOrNull()
