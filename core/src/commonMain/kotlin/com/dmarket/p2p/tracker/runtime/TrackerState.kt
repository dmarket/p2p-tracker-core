package com.dmarket.p2p.tracker.runtime

import com.dmarket.p2p.tracker.engine.TrackerBlock
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.SteamId
import kotlin.time.Instant

/**
 * What a [P2pTracker] shows the app: the account screen's status (T-04), the deal's "Last update" (T-12) and the
 * Active tab's banners (T-21). The Steam session's own state comes with DMA-662.
 *
 * @property running whether a loop runs for [account].
 * @property foreground whether the app said it is on screen; only then does the tracker poll.
 * @property account the Steam account the loop serves, as the host named it.
 * @property block the one reason the tracker is blocked, by [TrackerBlock]'s precedence.
 * @property linkedSteamId the Steam account the last heartbeat named as linked to the DMarket account.
 * @property accountMismatch whether that account and the Steam token's disagree; every Steam request stops.
 * @property lastSyncAt when the last heartbeat came back.
 * @property deals per tracked deal; a deal the last heartbeat no longer tracks is dropped.
 */
data class TrackerState(
    val running: Boolean = false,
    val foreground: Boolean = false,
    val account: SteamId? = null,
    val block: TrackerBlock = TrackerBlock.NONE,
    val linkedSteamId: SteamId? = null,
    val accountMismatch: Boolean = false,
    val lastSyncAt: Instant? = null,
    val deals: Map<DealId, DealTrackingState> = emptyMap(),
)

/**
 * One deal as the tracker last saw it.
 *
 * @property checkedAt when a cycle last read its Steam status ("Last update").
 * @property lastCode the last raw Steam status code reported for it.
 * @property proofState whether its report waits on a proof.
 * @property cooldownUntil until when a manual "Update status" is not available; set by `updateStatus` (DMA-661 4/5).
 */
data class DealTrackingState(
    val checkedAt: Instant? = null,
    val lastCode: Int? = null,
    val proofState: ProofState = ProofState.NONE,
    val cooldownUntil: Instant? = null,
)

/** Where a deal's proof stands. */
enum class ProofState {
    /** No proof is owed, or the last one was delivered. */
    NONE,

    /** A report waits on a proof the tracker is making. */
    PENDING,

    /** The proof is put off by the network gate (cellular, DMA-661 4/5); the app shows "waiting for a status update". */
    DEFERRED,
}
