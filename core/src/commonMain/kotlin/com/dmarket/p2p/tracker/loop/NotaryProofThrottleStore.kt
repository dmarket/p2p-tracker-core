package com.dmarket.p2p.tracker.loop

import com.dmarket.p2p.tracker.adapter.host.InMemoryDeviceKeyValueStore
import com.dmarket.p2p.tracker.config.NotaryBreakerConfig
import com.dmarket.p2p.tracker.credential.steam.DeviceVaultKeys
import com.dmarket.p2p.tracker.policy.NotaryProofThrottle
import com.dmarket.p2p.tracker.policy.NotaryThrottleState
import com.dmarket.p2p.tracker.port.host.DeviceKeyValueStore
import com.dmarket.p2p.tracker.wire.TrackerJson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.random.Random
import kotlin.time.Instant

/**
 * The device's record of **whether it may still spend an MPC session on a proof** — the store half of
 * [NotaryProofThrottle], and the exact shape [SteamWriteThrottleStore] uses for the create surface.
 *
 * **No method may throw.** It sits in front of proof generation, so a throwing store would force the caller
 * to choose between re-spending ~30 MB on a prover that just failed twice and never proving again.
 * Implementations swallow their own persistence failures and fall back to in-process state.
 */
interface NotaryProofThrottleStore {
    /** Until when proving is parked at [now], or `null` if a proof may be minted. */
    suspend fun parkedUntil(now: Instant): Instant?

    /**
     * Fold one finished proof into the state: [generated] is whether the prover produced a proof and it
     * reached the backend, whatever the backend's verdict on it. Returns the state as stored, so a caller can
     * report the resulting deadline.
     */
    suspend fun onResult(generated: Boolean, now: Instant): NotaryThrottleState
}

/** JSON surrogate for [NotaryThrottleState] (the domain type stays non-`@Serializable`, keeping `:domain` pure). */
@Serializable
private data class StoredNotaryThrottleState(val parkedUntilMs: Long? = null, val attempt: Int = 0, val consecutiveFailures: Int = 0)

private fun NotaryThrottleState.toStored(): StoredNotaryThrottleState = StoredNotaryThrottleState(
    parkedUntilMs = parkedUntil?.toEpochMilliseconds(),
    attempt = attempt,
    consecutiveFailures = consecutiveFailures,
)

private fun StoredNotaryThrottleState.toDomain(): NotaryThrottleState = NotaryThrottleState(
    parkedUntil = parkedUntilMs?.let(Instant::fromEpochMilliseconds),
    attempt = attempt,
    consecutiveFailures = consecutiveFailures,
)

/**
 * The **only** [NotaryProofThrottleStore] implementation, shared by every target: one in-memory state guarded
 * by a [Mutex], written through to a [DeviceKeyValueStore] as a single JSON row.
 *
 * **Why one row and one lock.** All three fields are written by the same fold, so splitting them across rows
 * (or across two setters) makes a half-updated ladder representable — one that either never arms, because the
 * streak was lost, or never climbs, because the attempt was. Here it is unrepresentable.
 *
 * **Why it must persist at all.** The backend's heartbeat TTL is well under the idle timeout that kills an MV3
 * service worker, so on web the worker respawns between most cycles. An in-memory-only cooldown would be
 * forgotten each time and the client would re-spend a full MPC session on a broken prover on every single
 * wake — the behaviour this exists to stop.
 *
 * Restore is **lazy**, on first use rather than at construction, so a cycle that never reaches a proof intent
 * (every idle wake, and every wake at all while v1 runs client-reported with no `proof_required`) pays no
 * storage read. Construct it **once per process** and inject it: the mutex is the atomicity primitive, so a
 * second instance would carry a second, independent lock. Storage failures never propagate — a cooldown that
 * lives only in memory still throttles this worker.
 */
class PersistedNotaryProofThrottleStore(
    private val limits: NotaryBreakerConfig = NotaryBreakerConfig(),
    private val storage: DeviceKeyValueStore = InMemoryDeviceKeyValueStore(),
    private val storageKey: String = DeviceVaultKeys.NOTARY_PROOF_THROTTLE,
    /** Injected so the jittered cooldowns are deterministic under test. */
    private val random: Random = Random.Default,
) : NotaryProofThrottleStore {
    private val mutex = Mutex()
    private var state = NotaryThrottleState.EMPTY

    /** Set once the persisted row has been folded in, so the (single) restore happens lazily on first use. */
    private var restored = false

    override suspend fun parkedUntil(now: Instant): Instant? = mutex.withLock {
        restore()
        NotaryProofThrottle.parkedUntil(state, now)
    }

    override suspend fun onResult(generated: Boolean, now: Instant): NotaryThrottleState = mutex.withLock {
        restore()
        val updated = if (generated) {
            NotaryProofThrottle.onSuccess(state)
        } else {
            NotaryProofThrottle.onFailure(state, now, limits, random)
        }
        if (updated != state) {
            state = updated
            persist()
        }
        updated
    }

    /** Folds the persisted row into memory once. Call inside [mutex]. */
    private suspend fun restore() {
        if (restored) return
        restored = true
        val json = runCatching { storage.get(storageKey) }.getOrNull() ?: return
        val stored = runCatching { TrackerJson.decodeFromString<StoredNotaryThrottleState>(json) }.getOrNull() ?: return
        // Only adopt it into a state this instance has not already advanced: its own decisions are newer.
        // An elapsed deadline is adopted as-is — [NotaryProofThrottle.parkedUntil] reads it as expired, and the
        // ATTEMPT it comes with is the point: the rung has to survive its own cooldown, or the ladder restarts
        // from the bottom on every respawn, which on web is every few minutes.
        if (state == NotaryThrottleState.EMPTY) state = stored.toDomain()
    }

    /** Best-effort write-through of the whole (three-field) state. Call inside [mutex]. */
    private suspend fun persist() {
        val encoded = TrackerJson.encodeToString(state.toStored())
        runCatching { storage.set(storageKey, encoded) }
    }
}
