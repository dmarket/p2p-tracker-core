package com.dmarket.p2p.tracker.loop

import com.dmarket.p2p.tracker.adapter.host.InMemoryDeviceKeyValueStore
import com.dmarket.p2p.tracker.config.SteamWriteConfig
import com.dmarket.p2p.tracker.credential.steam.DeviceVaultKeys
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.policy.PartnerCooldown
import com.dmarket.p2p.tracker.policy.SteamWriteFailureKind
import com.dmarket.p2p.tracker.policy.SteamWriteThrottle
import com.dmarket.p2p.tracker.policy.SteamWriteThrottleState
import com.dmarket.p2p.tracker.policy.WriteGate
import com.dmarket.p2p.tracker.port.host.DeviceKeyValueStore
import com.dmarket.p2p.tracker.wire.TrackerJson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.random.Random
import kotlin.time.Instant

/**
 * The device's record of **how hard it may still push Steam's `create_offer` surface** — the store half of
 * [SteamWriteThrottle].
 *
 * **No method may throw.** It sits in front of a Steam write, so a throwing store would force the caller to
 * choose between hammering a surface Steam is already refusing and stranding every deal. Implementations
 * swallow their own persistence failures and fall back to in-process state, exactly like
 * [DealWriteClaimStore].
 */
interface SteamWriteThrottleStore {
    /** The current state, for a planner pass over a whole batch of creates. */
    suspend fun snapshot(): SteamWriteThrottleState

    /** Whether a create for [partner] may reach Steam at [now] — the per-write re-check inside a chain. */
    suspend fun gate(partner: SteamId, now: Instant): WriteGate

    /**
     * Fold one finished create into the state: [kind] `null` means it succeeded, otherwise it is how the
     * failure was classified. Returns the state as stored, so a caller can report the resulting deadline.
     */
    suspend fun onResult(partner: SteamId, kind: SteamWriteFailureKind?, now: Instant): SteamWriteThrottleState

    /** Drop cooldowns whose deadline has passed, keeping the stored set bounded to partners still held back. */
    suspend fun prune(now: Instant)
}

/** JSON surrogate for one partner's cooldown (the domain types stay non-`@Serializable`, keeping `:domain` pure). */
@Serializable
private data class StoredPartnerCooldown(val partner: String, val untilMs: Long, val attempt: Int)

/** JSON surrogate for [SteamWriteThrottleState]. */
@Serializable
private data class StoredThrottleState(
    val partners: List<StoredPartnerCooldown> = emptyList(),
    val globalUntilMs: Long? = null,
    val globalAttempt: Int = 0,
    val consecutiveFailures: Int = 0,
)

private fun SteamWriteThrottleState.toStored(): StoredThrottleState = StoredThrottleState(
    partners = partners.map { (partner, cooldown) ->
        StoredPartnerCooldown(partner.value, cooldown.until.toEpochMilliseconds(), cooldown.attempt)
    },
    globalUntilMs = globalUntil?.toEpochMilliseconds(),
    globalAttempt = globalAttempt,
    consecutiveFailures = consecutiveFailures,
)

private fun StoredThrottleState.toDomain(): SteamWriteThrottleState = SteamWriteThrottleState(
    partners = partners.associate {
        SteamId(it.partner) to PartnerCooldown(Instant.fromEpochMilliseconds(it.untilMs), it.attempt)
    },
    globalUntil = globalUntilMs?.let(Instant::fromEpochMilliseconds),
    globalAttempt = globalAttempt,
    consecutiveFailures = consecutiveFailures,
)

/**
 * The **only** [SteamWriteThrottleStore] implementation, shared by every target: one in-memory state guarded
 * by a [Mutex], written through to a [DeviceKeyValueStore].
 *
 * **Why it must persist.** The backend's heartbeat TTL is well under the idle timeout that kills an MV3
 * service worker, so on web the worker respawns between most cycles. An in-memory-only cooldown would be
 * forgotten each time and the client would re-hit a partner Steam is still refusing on every single wake —
 * which is the behaviour this exists to stop.
 *
 * Construct it **once per process** and inject it (as `TradeTrackerLoop` does): the mutex is the atomicity
 * primitive, so a second instance would carry a second, independent lock. Storage failures never propagate —
 * a cooldown that lives only in memory still throttles this worker, and losing durability degrades to one
 * extra refused create after a respawn, never to a wedged deal.
 */
class PersistedSteamWriteThrottleStore(
    private val limits: SteamWriteConfig = SteamWriteConfig(),
    private val storage: DeviceKeyValueStore = InMemoryDeviceKeyValueStore(),
    private val storageKey: String = DeviceVaultKeys.STEAM_WRITE_THROTTLE,
    /** Injected so the jittered cooldowns are deterministic under test. */
    private val random: Random = Random.Default,
) : SteamWriteThrottleStore {
    private val mutex = Mutex()
    private var state = SteamWriteThrottleState.EMPTY

    /** Set once the persisted row has been folded in, so the (single) restore happens lazily on first use. */
    private var restored = false

    override suspend fun snapshot(): SteamWriteThrottleState = mutex.withLock {
        restore()
        state
    }

    override suspend fun gate(partner: SteamId, now: Instant): WriteGate = mutex.withLock {
        restore()
        SteamWriteThrottle.gate(state, partner, now)
    }

    override suspend fun onResult(partner: SteamId, kind: SteamWriteFailureKind?, now: Instant): SteamWriteThrottleState = mutex.withLock {
        restore()
        val updated = if (kind == null) {
            SteamWriteThrottle.onSuccess(state, partner)
        } else {
            SteamWriteThrottle.onFailure(state, partner, kind, now, limits, random)
        }
        if (updated != state) {
            state = updated
            persist()
        }
        updated
    }

    override suspend fun prune(now: Instant) = mutex.withLock {
        restore()
        val pruned = SteamWriteThrottle.prune(state, now)
        if (pruned != state) {
            state = pruned
            persist()
        }
    }

    /** Folds the persisted row into memory once. Call inside [mutex]. */
    private suspend fun restore() {
        if (restored) return
        restored = true
        val json = runCatching { storage.get(storageKey) }.getOrNull() ?: return
        val stored = runCatching { TrackerJson.decodeFromString<StoredThrottleState>(json) }.getOrNull() ?: return
        // Only adopt it into a state this instance has not already advanced: its own decisions are newer.
        if (state == SteamWriteThrottleState.EMPTY) state = stored.toDomain()
    }

    /** Best-effort write-through of the whole (partner-bounded) state. Call inside [mutex]. */
    private suspend fun persist() {
        val encoded = TrackerJson.encodeToString(state.toStored())
        runCatching { storage.set(storageKey, encoded) }
    }
}
