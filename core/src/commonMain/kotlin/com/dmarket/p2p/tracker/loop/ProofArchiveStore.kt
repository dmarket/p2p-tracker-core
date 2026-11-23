package com.dmarket.p2p.tracker.loop

import com.dmarket.p2p.tracker.adapter.host.InMemoryDeviceKeyValueStore
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.marketplace.ProofSite
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusSource
import com.dmarket.p2p.tracker.port.host.DeviceKeyValueStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * The device's copy of the end-of-deal proofs it sent (D2): kept for [PersistedProofArchiveStore.retention] (30 days)
 * from the deal's final status, never sent anywhere from here (dispute evidence is the backend's). Mobile keeps it in
 * the account's storage, which logout, a Steam account switch and an unlink erase; web and headless keep none.
 */
interface ProofArchiveStore {
    /**
     * Keeps [payload], the proof of [code] on [source] for [dealId], sent at [finalAt]: the moment the device proved
     * the deal's final status. A later final status of the same deal (a reversal after completion) moves the deal's
     * expiry to its own moment.
     */
    suspend fun keep(dealId: DealId, source: TradeStatusSource, code: Int, payload: String, finalAt: Instant)

    /** Drops every deal whose retention ended by [now]. */
    suspend fun prune(now: Instant)

    /** Drops everything. */
    suspend fun clear()

    companion object {
        /** The sites that end a deal (D1): declined, cancelled, completed, reversed. Confirmed and accepted do not. */
        private val END_OF_DEAL_SITES =
            setOf(ProofSite.OFFER_DECLINED, ProofSite.OFFER_CANCELLED, ProofSite.TRADE_COMPLETED, ProofSite.TRADE_REVERSED)

        /** Whether a proof of [code] on [source] is an end-of-deal proof. */
        fun endsDeal(source: TradeStatusSource, code: Int): Boolean = ProofSite.forTransition(source, code) in END_OF_DEAL_SITES
    }
}

/** Keeps nothing: web and headless. */
object NoProofArchive : ProofArchiveStore {
    override suspend fun keep(dealId: DealId, source: TradeStatusSource, code: Int, payload: String, finalAt: Instant) = Unit

    override suspend fun prune(now: Instant) = Unit

    override suspend fun clear() = Unit
}

/**
 * [ProofArchiveStore] in one key of [storage], read through on every call (no cache, so a [clear] from the facade is
 * what the loop's next write sees). Pruned by expiry only: the tracked set drops a deal the moment it ends, which is
 * exactly when its proofs must stay.
 */
class PersistedProofArchiveStore(
    private val storage: DeviceKeyValueStore = InMemoryDeviceKeyValueStore(),
    val retention: Duration = 30.days,
) : ProofArchiveStore {
    override suspend fun keep(dealId: DealId, source: TradeStatusSource, code: Int, payload: String, finalAt: Instant) {
        val now = finalAt.toEpochMilliseconds()
        val deals = read().filter { !expired(it, now) }.toMutableList()
        val existing = deals.indexOfFirst { it.dealId == dealId.value }
        val proof = ArchivedProof(source.wireName, code, payload)
        val deal = if (existing < 0) {
            ArchivedDeal(dealId.value, now, listOf(proof))
        } else {
            val old = deals.removeAt(existing)
            ArchivedDeal(
                dealId.value,
                maxOf(old.finalAtMs, now),
                old.proofs.filterNot { it.source == proof.source && it.code == code } + proof,
            )
        }
        deals += deal
        write(deals)
    }

    override suspend fun prune(now: Instant) {
        val deals = read()
        val kept = deals.filter { !expired(it, now.toEpochMilliseconds()) }
        if (kept.size != deals.size) write(kept)
    }

    override suspend fun clear() = storage.remove(KEY)

    /** The deals held now, for tests and diagnostics; never for sending. */
    internal suspend fun dealIds(): Set<DealId> = read().mapTo(LinkedHashSet()) { DealId(it.dealId) }

    private fun expired(deal: ArchivedDeal, nowMs: Long) = nowMs - deal.finalAtMs >= retention.inWholeMilliseconds

    /** An unreadable value reads as empty: the archive is the device's copy, never what the backend relies on. */
    private suspend fun read(): List<ArchivedDeal> = storage.get(KEY)?.let {
        runCatching { json.decodeFromString<List<ArchivedDeal>>(it) }.getOrNull()
    }.orEmpty()

    private suspend fun write(deals: List<ArchivedDeal>) =
        if (deals.isEmpty()) storage.remove(KEY) else storage.set(KEY, json.encodeToString(deals))

    @Serializable
    private data class ArchivedDeal(val dealId: String, val finalAtMs: Long, val proofs: List<ArchivedProof>)

    @Serializable
    private data class ArchivedProof(val source: String, val code: Int, val payload: String)

    internal companion object {
        const val KEY = "end_of_deal_proofs"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
