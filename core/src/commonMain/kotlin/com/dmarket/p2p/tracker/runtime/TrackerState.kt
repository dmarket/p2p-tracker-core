package com.dmarket.p2p.tracker.runtime

import com.dmarket.p2p.tracker.engine.TrackerBlock
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.SteamId
import kotlin.time.Duration
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
 * @property cooldownUntil until when a manual "Update status" is not available: set by [P2pTracker.updateStatus] from a
 *   429's `Retry-After`. The server's own permit (DMA-660) is the app's.
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

    /**
     * The proof is put off by the network gate: a metered network without the user's cellular opt-in. Its report waits,
     * unsent, for an unmetered network or the opt-in; the app shows "waiting for a status update" (T-10, T-13).
     */
    DEFERRED,
}

/**
 * What one [P2pTracker.updateStatus] did for a deal (T-12): its [DealTrackingState] after the run, and why the run fell
 * short when it did.
 *
 * @property checkedAt when a cycle last read the deal's Steam status.
 * @property code the last raw Steam status code reported for it; `null` when none was reported since the process started.
 * @property proofState where its proof stands; [ProofState.DEFERRED] when the gate held it (T-13 asks for permission).
 * @property retryAfter how long until a retry can go out; set with [UpdateFailure.RATE_LIMITED] only.
 * @property failure why the update fell short (T-16), or `null` when it ran through.
 */
data class StatusUpdate(
    val checkedAt: Instant? = null,
    val code: Int? = null,
    val proofState: ProofState = ProofState.NONE,
    val retryAfter: Duration? = null,
    val failure: UpdateFailure? = null,
)

/**
 * Why an update fell short. Kept apart because the app treats them apart (T-12, D5): a failure on our side (DMarket,
 * the notary, a timeout) does not spend the user's manual-update permit.
 */
enum class UpdateFailure {
    /** DMarket or Steam answered 429; [StatusUpdate.retryAfter] says for how long. */
    RATE_LIMITED,

    /** The Steam session is gone or belongs to another account (T-20). */
    STEAM_SESSION,

    /** A Steam read failed: the connection dropped or Steam erred. */
    STEAM,

    /** The proof was not made: the prover or the notary connection failed. */
    NOTARY,

    /** DMarket did not answer the sync or refused the report. */
    DMARKET,

    /** The run outlived the cycle deadline and was cancelled. */
    TIMEOUT,

    /** No tracker runs: the app's user links no Steam account, or the tracker stopped during the run. */
    NOT_RUNNING,

    /** The cycle failed in a way none of the above names. */
    OTHER,
}
