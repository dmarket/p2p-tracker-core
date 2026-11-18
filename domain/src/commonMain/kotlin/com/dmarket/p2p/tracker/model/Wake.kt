package com.dmarket.p2p.tracker.model

import kotlin.time.Instant

/**
 * A wake push as the mobile tracker reads it (contract d455888 §2.2): the [signal] the loop acts on, and what the
 * envelope carries beside it. [proveAfter] is parsed and passed on only; acting on it is DMA-664.
 *
 * @property type the envelope's `type` (`wake_all` / `wake_deal`).
 * @property wakeId the key of the wake's ack and of the heartbeat it triggers; `null` on a frame without one (no ack).
 * @property transactionId the deal record a `wake_deal` names; only logged, the sync is full.
 * @property deadline when the work the wake serves times out; past it the wake is dropped.
 */
data class Wake(
    val signal: PushSignal,
    val type: String,
    val reason: String? = null,
    val proveAfter: Instant? = null,
    val issuedAt: Instant? = null,
    val wakeId: String? = null,
    val transactionId: String? = null,
    val deadline: Instant? = null,
) {
    /** A `create_offer` wake (§2.4 `directive`): never acked `deferred` (§4.3). */
    val isDirective: Boolean get() = reason == DIRECTIVE_REASON

    /** The deal a `wake_deal` names; `null` for any other wake. */
    val dealId: DealId? get() = (signal as? PushSignal.WakeForDeal)?.dealId
}

/** The `reason` of a wake for a leased `create_offer` (§2.4, W1/W8). */
const val DIRECTIVE_REASON = "directive"
