package com.dmarket.p2p.tracker.model

import kotlin.time.Instant

/**
 * A wake push as the mobile tracker reads it (DMA-659, provisional): the [signal] the loop acts on, and what the
 * envelope carries beside it. [proveAfter] is parsed and passed on only; acting on it is DMA-664.
 *
 * @property type the envelope's `type` (`wake_all` / `wake_deal`).
 */
data class Wake(
    val signal: PushSignal,
    val type: String,
    val reason: String? = null,
    val proveAfter: Instant? = null,
    val issuedAt: Instant? = null,
) {
    /** The deal a `wake_deal` names; `null` for any other wake. */
    val dealId: DealId? get() = (signal as? PushSignal.WakeForDeal)?.dealId
}
