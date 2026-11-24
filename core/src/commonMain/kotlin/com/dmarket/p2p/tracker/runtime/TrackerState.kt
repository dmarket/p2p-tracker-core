package com.dmarket.p2p.tracker.runtime

import com.dmarket.p2p.tracker.engine.TrackerBlock
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.port.steam.SteamWebSessionState
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * What a [P2pTracker] shows the app: the account screen's status (T-04), the deal's "Last update" (T-12) and the
 * Active tab's banners (T-21), and the Steam session's state (T-04, T-20, T-02).
 *
 * @property running whether a loop runs for [account].
 * @property foreground whether the app said it is on screen; only then does the tracker poll.
 * @property account the Steam account the loop serves, as the host named it.
 * @property block the one reason the tracker is blocked, by [TrackerBlock]'s precedence.
 * @property linkedSteamId the Steam account the last heartbeat named as linked to the DMarket account.
 * @property accountMismatch whether that account and the Steam token's disagree; every Steam request stops.
 * @property steamSession the state of the Steam session in the core's vault, and the one field the app reads for it on
 *   Android and iOS (never combined with [block]). Read after every cycle, import and check:
 *   [SteamWebSessionState.ALIVE], [SteamWebSessionState.NEEDS_REFRESH] (renewed on the next cycle or check),
 *   [SteamWebSessionState.GONE] (sign in to Steam again, T-20) or [SteamWebSessionState.OTHER_ACCOUNT] (the session or
 *   the token belongs to another account than the linked one; no Steam request goes out). Ranked like [block]: a gone
 *   session reads `GONE` whoever it belonged to. While a loop runs and the session is not read yet, it says what [block]
 *   says of the session: [TrackerBlock.STEAM_SESSION_MISSING] reads `GONE`, [TrackerBlock.STEAM_ACCOUNT_MISMATCH] reads
 *   `OTHER_ACCOUNT`, any other block `null`. `null` while no loop runs. An import before the start (even before the
 *   tracker is built) is read by the first cycle after it.
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
    val steamSession: SteamWebSessionState? = null,
    val lastSyncAt: Instant? = null,
    val deals: Map<DealId, DealTrackingState> = emptyMap(),
)

/** This state with [TrackerState.steamSession] filled from [TrackerState.block] while a loop runs and it is not read yet. */
internal fun TrackerState.withSteamSessionFromBlock(): TrackerState = if (!running || steamSession != null) {
    this
} else {
    copy(
        steamSession = when (block) {
            TrackerBlock.STEAM_SESSION_MISSING -> SteamWebSessionState.GONE
            TrackerBlock.STEAM_ACCOUNT_MISMATCH -> SteamWebSessionState.OTHER_ACCOUNT
            else -> null
        },
    )
}

/**
 * One deal as the tracker last saw it.
 *
 * @property checkedAt when a cycle last read its Steam status ("Last update").
 * @property lastCode the last raw Steam status code reported for it.
 * @property proofState whether its report waits on a proof.
 * @property cooldownUntil until when a manual "Update status" is not available: set by [P2pTracker.updateStatus] after a
 *   429, to the longer of its `Retry-After` and a backoff that doubles with each 429 in a row ([RateLimitBackoff]). Once
 *   the surface answers again, the backoff's part ends and only the `Retry-After` still holds. The server's own permit
 *   (`…/refresh`) is kept apart and shows in [StatusUpdate.nextAllowedAt].
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
 * @property code the raw Steam status code this run read for it (the history axis before the offer axis), else the last
 *   one reported; `null` when neither exists, e.g. the read failed right after a cold start.
 * @property proofState where its proof stands; [ProofState.DEFERRED] when the gate held it (T-13 asks for permission).
 * @property retryAfter how long until a retry can go out, set with [UpdateFailure.RATE_LIMITED] only: the refresh 429's
 *   `Retry-After` exactly, what is left of a spent permit, or a DMarket/Steam 429's wait from [RateLimitBackoff] (or
 *   what is left of it).
 * @property failure why the update fell short (T-16), or `null` when it ran through.
 * @property nextAllowedAt when the deal's next refresh is allowed (the server's `nextAllowedAt`, or a refresh 429's
 *   `Retry-After`); `null` when no wait is known, and the app may ask again.
 * @property retryLeft how many more notary sessions the deal's permit allows after its first (the Retry on T-16,
 *   DMA-1016); the `retries` asked for while no permit holds. `0` with [nextAllowedAt] set: the next run waits for it.
 *   At least `1` while a proof made for the deal was not delivered (within its hour), spent permit or not: the next run
 *   sends those bytes again with no notary session. A 429 on that delivery is [UpdateFailure.RATE_LIMITED] and still
 *   waits its [retryAfter].
 */
data class StatusUpdate(
    val checkedAt: Instant? = null,
    val code: Int? = null,
    val proofState: ProofState = ProofState.NONE,
    val retryAfter: Duration? = null,
    val failure: UpdateFailure? = null,
    val nextAllowedAt: Instant? = null,
    val retryLeft: Int = 0,
)

/**
 * Why an update fell short. Kept apart because the app treats them apart (T-12, T-16). A notary session spends one of
 * the permit's sessions whatever its end ([StatusUpdate.retryLeft], DMA-1016); a failure before any session (DMarket,
 * a timeout, Steam) spends none.
 */
enum class UpdateFailure {
    /**
     * DMarket or Steam answered 429 (the refresh, the sync, the reads or the proof's delivery), or the deal's permit is
     * spent; [StatusUpdate.retryAfter] says for how long.
     */
    RATE_LIMITED,

    /** The Steam session is gone or belongs to another account (T-20). */
    STEAM_SESSION,

    /** A Steam read failed: the connection dropped or Steam erred. */
    STEAM,

    /** The proof was not made: the prover or the notary connection failed. */
    NOTARY,

    /**
     * DMarket did not answer the sync, refused the report, or did not answer the proof's delivery (a 5xx, a timeout):
     * that proof's bytes go again on the next run, with no notary session.
     */
    DMARKET,

    /** The run outlived the cycle deadline and was cancelled. */
    TIMEOUT,

    /** No tracker runs: the app's user links no Steam account, its loop did not start, or it stopped during the run. */
    NOT_RUNNING,

    /** The cycle failed in a way none of the above names. */
    OTHER,

    /**
     * The proof reached DMarket and was refused (`verified: false`, or a 4xx verdict such as a deal that moved on), in
     * this run or, for the same bytes, earlier. A Retry would meet the same refusal. Bytes sent again after an answer
     * that was no verdict are a success when DMarket says it already accepted them or the deal no longer needs a status
     * update, and refused otherwise (`ResentProof`).
     */
    PROOF_REFUSED,
}
