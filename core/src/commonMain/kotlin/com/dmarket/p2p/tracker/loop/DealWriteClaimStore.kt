package com.dmarket.p2p.tracker.loop

import com.dmarket.p2p.tracker.adapter.host.InMemoryDeviceKeyValueStore
import com.dmarket.p2p.tracker.credential.steam.DeviceVaultKeys
import com.dmarket.p2p.tracker.engine.ClaimVerdict
import com.dmarket.p2p.tracker.engine.DealWriteGuard
import com.dmarket.p2p.tracker.model.ClaimPhase
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.DealWriteClaim
import com.dmarket.p2p.tracker.model.DealWriteKey
import com.dmarket.p2p.tracker.model.DirectiveId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.marketplace.DirectiveAction
import com.dmarket.p2p.tracker.model.marketplace.DirectiveOutcome
import com.dmarket.p2p.tracker.model.marketplace.DirectiveStatus
import com.dmarket.p2p.tracker.port.host.DeviceKeyValueStore
import com.dmarket.p2p.tracker.wire.TrackerJson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The device's ledger of **claims on non-idempotent Steam writes**, keyed by `(deal, action)` — the
 * store half of [DealWriteGuard].
 *
 * It answers exactly one question for the loop's two Steam write surfaces: *may this `create_offer` /
 * `cancel_offer` for this deal reach Steam, or has this device already done it?* Existing dedup can't:
 * [TrackerProgressStore] is keyed by `directive_id` (blind to a fresh id for an already-served deal),
 * and Steam itself will happily create a second live offer.
 *
 * [claim] is the only correctness-critical method: it must take the claim **atomically**, because two
 * callers racing for one deal is precisely the failure mode this exists to stop.
 *
 * **No method may throw.** The guard sits in front of a Steam write, so a throwing store would force the
 * caller to choose between a possible duplicate (fail-open) and a stranded deal (fail-closed).
 * Implementations swallow their own persistence failures instead and fall back to in-process state.
 */
interface DealWriteClaimStore {
    /**
     * Atomically evaluate + take the claim: returns [ClaimVerdict.Proceed] having stored [claim] (phase
     * [ClaimPhase.IN_FLIGHT]) when the write may go ahead, or the standing claim's verdict
     * ([ClaimVerdict.AlreadyCompleted] / [ClaimVerdict.InFlight]) having stored nothing. Implementations
     * must perform the load → [DealWriteGuard.evaluate] → store sequence under one lock.
     */
    suspend fun claim(claim: DealWriteClaim, now: Instant, ttl: Duration): ClaimVerdict

    /** Promote the claim for [outcome]'s deal + action to [ClaimPhase.COMPLETED], storing the replayable outcome. */
    suspend fun complete(key: DealWriteKey, outcome: DirectiveOutcome)

    /** Drop the claims for [keys] — the write failed (nothing was written) or the deal no longer needs guarding. */
    suspend fun release(keys: Set<DealWriteKey>)

    /** Every stored claim, for the heartbeat's [DealWriteGuard.staleClaims] reconciliation. */
    suspend fun all(): Collection<DealWriteClaim>
}

/** JSON surrogate for [DealWriteClaim] (the domain types stay non-`@Serializable`, keeping `:domain` pure). */
@Serializable
private data class StoredDealWriteClaim(
    val dealId: String,
    val action: String,
    val phase: String,
    val claimedAtMs: Long,
    val directiveId: String,
    val outcomeStatus: String? = null,
    val outcomeSteamOfferId: String? = null,
    val outcomeError: String? = null,
)

private fun DealWriteClaim.toStored(): StoredDealWriteClaim = StoredDealWriteClaim(
    dealId = dealId.value,
    action = action.wireName,
    phase = phase.name,
    claimedAtMs = claimedAt.toEpochMilliseconds(),
    directiveId = directiveId.value,
    outcomeStatus = outcome?.status?.wireName,
    outcomeSteamOfferId = outcome?.steamOfferId?.value,
    outcomeError = outcome?.error,
)

/** `null` when the stored row no longer parses (dropped, not fatal — the claim simply stops guarding). */
private fun StoredDealWriteClaim.toDomain(): DealWriteClaim? {
    val action = DirectiveAction.fromWire(action).takeIf { it != DirectiveAction.UNKNOWN } ?: return null
    val phase = ClaimPhase.entries.firstOrNull { it.name == phase } ?: return null
    val directive = DirectiveId(directiveId)
    val deal = DealId(dealId)
    val status = outcomeStatus?.let { wire -> DirectiveStatus.entries.firstOrNull { it.wireName == wire } }
    return DealWriteClaim(
        dealId = deal,
        action = action,
        phase = phase,
        claimedAt = Instant.fromEpochMilliseconds(claimedAtMs),
        directiveId = directive,
        outcome = status?.let {
            DirectiveOutcome(
                directiveId = directive,
                action = action,
                status = it,
                dealId = deal,
                steamOfferId = outcomeSteamOfferId?.let(::OfferId),
                error = outcomeError,
            )
        },
    )
}

/**
 * The **only** [DealWriteClaimStore] implementation, shared by every target: an in-memory map guarded by
 * one [Mutex], written through to a [DeviceKeyValueStore] so claims survive a process death (an MV3
 * service-worker respawn, an Android process kill, an iOS relaunch).
 *
 * **Why the mutex is the atomicity primitive, not the storage:** none of the platform key-value backends
 * offers compare-and-swap — not `storage.local`, not `SharedPreferences`, not `NSUserDefaults` — so
 * [claim] holds the lock across the whole load → evaluate → store sequence. This is why the store must
 * be constructed **once per process** and injected (which is what `TradeTrackerLoop` does): a second
 * instance would carry a second, independent lock. Two OS processes sharing one profile (an Android
 * `:remote` process, an iOS app + extension pair) would defeat it the same way — keep the tracker in one
 * process.
 *
 * Storage failures never propagate: a claim that lives only in memory still blocks the duplicate inside
 * this process, and losing durability degrades to at most one re-write after a process death — never to a
 * wedged deal. Reads fall back to the in-memory map for the same reason.
 */
class PersistedDealWriteClaimStore(
    private val storage: DeviceKeyValueStore = InMemoryDeviceKeyValueStore(),
    private val storageKey: String = DeviceVaultKeys.DEAL_WRITE_CLAIMS,
) : DealWriteClaimStore {
    private val serializer = ListSerializer(StoredDealWriteClaim.serializer())
    private val mutex = Mutex()
    private val claims = mutableMapOf<DealWriteKey, DealWriteClaim>()

    /** Set once the persisted rows have been folded in, so the (single) restore happens lazily on first use. */
    private var restored = false

    override suspend fun claim(claim: DealWriteClaim, now: Instant, ttl: Duration): ClaimVerdict = mutex.withLock {
        restore()
        val verdict = DealWriteGuard.evaluate(claims[claim.key], now, ttl)
        if (verdict is ClaimVerdict.Proceed) {
            claims[claim.key] = claim.copy(phase = ClaimPhase.IN_FLIGHT, outcome = null)
            persist()
        }
        verdict
    }

    override suspend fun complete(key: DealWriteKey, outcome: DirectiveOutcome) = mutex.withLock {
        restore()
        val existing = claims[key] ?: return@withLock
        claims[key] = existing.copy(phase = ClaimPhase.COMPLETED, outcome = outcome)
        persist()
    }

    override suspend fun release(keys: Set<DealWriteKey>) = mutex.withLock {
        restore()
        if (keys.none { claims.containsKey(it) }) return@withLock
        keys.forEach(claims::remove)
        persist()
    }

    override suspend fun all(): Collection<DealWriteClaim> = mutex.withLock {
        restore()
        claims.values.toList()
    }

    /** Folds the persisted rows into the in-memory map once. Call inside [mutex]. */
    private suspend fun restore() {
        if (restored) return
        restored = true
        val json = runCatching { storage.get(storageKey) }.getOrNull() ?: return
        val stored = runCatching { TrackerJson.decodeFromString(serializer, json) }.getOrNull() ?: return
        // In-memory entries win: this instance's own claims are newer than anything on disk.
        stored.mapNotNull { it.toDomain() }.forEach { if (it.key !in claims) claims[it.key] = it }
    }

    /** Best-effort write-through of the whole (small, deal-bounded) set. Call inside [mutex]. */
    private suspend fun persist() {
        val encoded = TrackerJson.encodeToString(serializer, claims.values.map { it.toStored() })
        runCatching { storage.set(storageKey, encoded) }
    }
}
