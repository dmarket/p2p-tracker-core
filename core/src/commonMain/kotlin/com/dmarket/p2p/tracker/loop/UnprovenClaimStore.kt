package com.dmarket.p2p.tracker.loop

import com.dmarket.p2p.tracker.adapter.host.InMemoryDeviceKeyValueStore
import com.dmarket.p2p.tracker.credential.steam.DeviceVaultKeys
import com.dmarket.p2p.tracker.engine.ProofIntent
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusSource
import com.dmarket.p2p.tracker.port.host.DeviceKeyValueStore
import com.dmarket.p2p.tracker.wire.TrackerJson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * Which decisive transitions this device has already reported **unproven**.
 *
 * A decisive transition on a `proof_required` deal normally waits for its proof. When no proof can be
 * produced at all — no prover on this host, the prover parked, the cycle's budget spent — the client
 * reports the raw code anyway, because a closure the backend refuses is one it can still act on, whereas
 * a closure it never hears about drifts to the deal's deadline.
 *
 * That report is worth sending **once**. The backend refuses it (the place is enforced), so it never
 * enters the dedup baseline, so the transition is re-detected on every cycle for the life of the deal —
 * and an in-memory marker would be forgotten on every MV3 respawn, which happens between most cycles.
 * Hence a persisted ledger, and hence its shape: a spent claim, not a counter.
 *
 * The claim is released when the report is finally accepted (a proof arrived and cleared the way, or the
 * backend stopped enforcing the place), and the row is pruned when the deal leaves the tracked set.
 */
interface UnprovenClaimStore {
    /** Every transition already claimed, across all deals. The loop narrows it to the tracked ones. */
    suspend fun load(): Set<ProofIntent>

    /** Records [intent] as claimed. Idempotent. */
    suspend fun claim(intent: ProofIntent)

    /** Forgets [intents] — an accepted report, or a deal that is no longer tracked. */
    suspend fun release(intents: Set<ProofIntent>)
}

/** One persisted claim. Flat, like the accepted-proof rows: three scalars, no encoded composite key. */
@Serializable
private data class StoredUnprovenClaim(val dealId: String, val source: String, val steamStatusCode: Int) {
    /** `null` when the stored axis no longer parses — dropped, not fatal. */
    fun toDomain(): ProofIntent? {
        val axis = TradeStatusSource.entries.firstOrNull { it.wireName == source } ?: return null
        return ProofIntent(DealId(dealId), axis, steamStatusCode)
    }
}

private fun ProofIntent.toStored() = StoredUnprovenClaim(dealId.value, source.wireName, steamStatusCode)

/**
 * The **only** [UnprovenClaimStore] implementation, shared by every target: an in-memory set guarded by one
 * [Mutex] and written through to a [DeviceKeyValueStore], mirroring [PersistedDealWriteClaimStore].
 *
 * Storage failures never propagate, and the direction of that degradation is the deliberate part: a claim
 * that lives only in memory still suppresses the repeat inside this process, and losing durability costs at
 * most one extra refused report after a process death. The opposite failure — treating an unreadable ledger
 * as "already claimed" — would silence a closure the backend has never been told about, which is the whole
 * thing this reporting path exists to prevent.
 */
class PersistedUnprovenClaimStore(
    private val storage: DeviceKeyValueStore = InMemoryDeviceKeyValueStore(),
    private val storageKey: String = DeviceVaultKeys.UNPROVEN_CLAIMS,
) : UnprovenClaimStore {
    private val serializer = ListSerializer(StoredUnprovenClaim.serializer())
    private val mutex = Mutex()
    private val claimed = mutableSetOf<ProofIntent>()

    /** Set once the persisted rows have been folded in, so the (single) restore happens lazily on first use. */
    private var restored = false

    override suspend fun load(): Set<ProofIntent> = mutex.withLock {
        restore()
        claimed.toSet()
    }

    override suspend fun claim(intent: ProofIntent) = mutex.withLock {
        restore()
        if (claimed.add(intent)) persist()
    }

    override suspend fun release(intents: Set<ProofIntent>) = mutex.withLock {
        restore()
        if (claimed.removeAll(intents)) persist()
    }

    /** Folds the persisted rows into the in-memory set once. Call inside [mutex]. */
    private suspend fun restore() {
        if (restored) return
        restored = true
        val json = runCatching { storage.get(storageKey) }.getOrNull() ?: return
        val stored = runCatching { TrackerJson.decodeFromString(serializer, json) }.getOrNull() ?: return
        claimed += stored.mapNotNull { it.toDomain() }
    }

    /** Best-effort write-through. Call inside [mutex]. */
    private suspend fun persist() {
        val json = TrackerJson.encodeToString(serializer, claimed.map { it.toStored() })
        runCatching { storage.set(storageKey, json) }
    }
}
