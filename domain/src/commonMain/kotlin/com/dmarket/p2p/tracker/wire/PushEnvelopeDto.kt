package com.dmarket.p2p.tracker.wire

import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.PushSignal
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A backend→client push wake-up frame (host-delivered; the lib owns no push transport). Under the
 * C1 contract, push is just a nudge to run a cycle **now** instead of waiting
 * for the next scheduled wake — it carries no cadence (cadence is client-owned + the heartbeat
 * `ttlSeconds`) and at most a deal hint.
 *
 * **PROVISIONAL** — push is a later optimization. A plain `type` string + `ignoreUnknownKeys` means an
 * unknown type or extra fields decode harmlessly, so only this DTO and [toSignalOrNull] change when
 * the real push schema lands — nothing downstream.
 */
@Serializable
data class PushEnvelopeDto(
    @SerialName("type") val type: String, // "wake_all" | "wake_deal"
    @SerialName("dealId") val dealId: String? = null,
)

/**
 * Decodes an inbound [PushEnvelopeDto] into a domain [PushSignal], or `null` for an unknown/malformed
 * frame (so an unexpected backend message is ignored rather than crashing the push loop). The
 * `isNotBlank` guard protects [DealId]'s non-blank `require`.
 */
fun PushEnvelopeDto.toSignalOrNull(): PushSignal? = when (type) {
    "wake_all" -> PushSignal.WakeAll
    "wake_deal" -> dealId?.takeIf { it.isNotBlank() }?.let { PushSignal.WakeForDeal(DealId(it)) }
    else -> null
}

/**
 * Parses a raw push **payload string** (delivered by the host's own push handler) into a
 * [PushEnvelopeDto], or `null` if it is absent/unparseable. This is the only push logic the core owns.
 */
fun parsePushEnvelope(json: String): PushEnvelopeDto? =
    runCatching { TrackerJson.decodeFromString(PushEnvelopeDto.serializer(), json) }.getOrNull()
