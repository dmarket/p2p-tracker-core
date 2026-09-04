package com.dmarket.p2p.tracker.loop

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant

/**
 * Cross-tick loop bookkeeping that must survive process/worker restarts: when the presence
 * heartbeat is next due (so it stays on its own backend-`ttl_seconds` cadence instead of firing on
 * every deal-watch wake after an MV3 service-worker respawn), until when the expedited-poll
 * window is open (so a just-created offer keeps its fast cadence across a respawn), and how many
 * consecutive heartbeats have failed on a server error (so a persistent outage still crosses the
 * debounce threshold when the worker dies between retries), plus the two blocking states that are
 * otherwise in-memory only (a missing Steam web session and a wrong-account session — so the host's
 * prompt survives a respawn instead of reading as "nothing is blocking").
 *
 * Defaults to in-memory ([InMemoryLoopStateStore]); web backs it with `chrome.storage.local`.
 */
interface LoopStateStore {
    /** When the next presence heartbeat should run, or `null` if one is due now (e.g. first wake). */
    suspend fun nextHeartbeatAt(): Instant?

    suspend fun setNextHeartbeatAt(at: Instant)

    /** Until when the expedited-poll window is open, or `null` if it was never armed. */
    suspend fun expeditedUntil(): Instant?

    suspend fun setExpeditedUntil(at: Instant)

    /**
     * When the sparse revert watch (`GetTradeHistory`) last ran, or `null` if it never has.
     *
     * Persisted rather than in-memory because it gates traffic and an MV3 service worker is respawned every
     * few minutes: an in-memory stamp would reset to "never ran" on each spawn and the gate would not gate.
     *
     * Defaulted so no existing implementation has to change; a store that does not override it simply keeps
     * the pre-gate behaviour (a history read every cycle) rather than failing to compile.
     */
    suspend fun revertWatchAt(): Instant? = null

    suspend fun setRevertWatchAt(at: Instant) {
        // no-op default
    }

    /** Consecutive heartbeat server-error (non-401 4xx/5xx) failures, or `0` if the last one succeeded. */
    suspend fun serverErrorCount(): Int

    suspend fun setServerErrorCount(count: Int)

    /**
     * Whether the last cycle found no authenticated Steam web session. Unlike the other entries this is
     * not scheduling state but a *blocking* state the host renders: it is persisted because the flag
     * behind it is in-memory, so a respawned worker would otherwise report "nothing is blocking" on its
     * first wake — including on an idle wake that never re-checks the session.
     *
     * Defaulted (no-op / `false`) so hosts with their own [LoopStateStore] keep compiling; a host that
     * does not implement it simply loses the respawn-durability of that one prompt.
     */
    suspend fun steamSessionMissing(): Boolean = false

    suspend fun setSteamSessionMissing(missing: Boolean) {
        // no-op by default
    }

    /**
     * Whether Steam has already been asked to mint a new session during the current missing-session
     * episode. Persisted for the same reason as the flag above — it must bound the attempt across worker
     * respawns — but kept SEPARATE from it on purpose: "the session is missing" and "we have already asked"
     * are different facts, and gating the attempt on the first one means it can only ever fire on the exact
     * cycle that noticed, never for a client that was already in the state (an upgrade, or any respawn).
     *
     * Cleared whenever a credential is acquired, and by an explicit [forceHeartbeatNow] — a user asking for
     * a cycle now is asking for the retry too.
     */
    suspend fun steamMintAttempted(): Boolean = false

    suspend fun setSteamMintAttempted(attempted: Boolean) {
        // no-op by default
    }

    /**
     * The Steam id of the **token** the last heartbeat found bound to a different DMarket account, or
     * `null` when the accounts agree. Persisted for the same reason as [steamSessionMissing]: it is a
     * blocking state the host renders, so an in-memory-only value would report "nothing is blocking" on
     * the first wake of every respawn — and that wake emits its cycle-started event *before* anything
     * could re-derive the verdict.
     *
     * The **id**, not a boolean, deliberately: it names the credential the verdict was computed against,
     * which is what lets a later cycle notice the account changed (and bound the one re-acquisition the
     * loop is allowed per wrong-account episode) without waiting for a heartbeat.
     *
     * Defaulted (no-op / `null`) so hosts with their own [LoopStateStore] keep compiling; such a host
     * simply loses the respawn-durability of that one prompt.
     */
    suspend fun steamMismatchTokenId(): String? = null

    suspend fun setSteamMismatchTokenId(steamId: String?) {
        // no-op by default
    }

    /**
     * Whether the credential named by [steamMismatchTokenId] has already been re-acquired from Steam
     * during the current wrong-account episode. Kept separate from the verdict for the same reason
     * [steamMintAttempted] is kept separate from [steamSessionMissing]: the verdict names the held
     * credential on every wake of a *truthful* mismatch, so gating on it alone would re-scrape Steam on
     * every wake. Cleared when the verdict clears and by an explicit force-heartbeat.
     */
    suspend fun steamMismatchRechecked(): Boolean = false

    suspend fun setSteamMismatchRechecked(rechecked: Boolean) {
        // no-op by default
    }
}

/** In-memory [LoopStateStore] for a long-lived process or tests. */
class InMemoryLoopStateStore : LoopStateStore {
    private val mutex = Mutex()
    private var nextHeartbeatAt: Instant? = null
    private var expeditedUntil: Instant? = null
    private var revertWatchAt: Instant? = null
    private var serverErrorCount: Int = 0
    private var steamSessionMissing: Boolean = false
    private var steamMintAttempted: Boolean = false
    private var steamMismatchTokenId: String? = null
    private var steamMismatchRechecked: Boolean = false

    override suspend fun nextHeartbeatAt(): Instant? = mutex.withLock { nextHeartbeatAt }

    override suspend fun setNextHeartbeatAt(at: Instant) {
        mutex.withLock { nextHeartbeatAt = at }
    }

    override suspend fun expeditedUntil(): Instant? = mutex.withLock { expeditedUntil }

    override suspend fun setExpeditedUntil(at: Instant) {
        mutex.withLock { expeditedUntil = at }
    }

    override suspend fun revertWatchAt(): Instant? = mutex.withLock { revertWatchAt }

    override suspend fun setRevertWatchAt(at: Instant) {
        mutex.withLock { revertWatchAt = at }
    }

    override suspend fun serverErrorCount(): Int = mutex.withLock { serverErrorCount }

    override suspend fun setServerErrorCount(count: Int) {
        mutex.withLock { serverErrorCount = count }
    }

    override suspend fun steamSessionMissing(): Boolean = mutex.withLock { steamSessionMissing }

    override suspend fun setSteamSessionMissing(missing: Boolean) {
        mutex.withLock { steamSessionMissing = missing }
    }

    override suspend fun steamMintAttempted(): Boolean = mutex.withLock { steamMintAttempted }

    override suspend fun setSteamMintAttempted(attempted: Boolean) {
        mutex.withLock { steamMintAttempted = attempted }
    }

    override suspend fun steamMismatchTokenId(): String? = mutex.withLock { steamMismatchTokenId }

    override suspend fun setSteamMismatchTokenId(steamId: String?) {
        mutex.withLock { steamMismatchTokenId = steamId }
    }

    override suspend fun steamMismatchRechecked(): Boolean = mutex.withLock { steamMismatchRechecked }

    override suspend fun setSteamMismatchRechecked(rechecked: Boolean) {
        mutex.withLock { steamMismatchRechecked = rechecked }
    }
}
