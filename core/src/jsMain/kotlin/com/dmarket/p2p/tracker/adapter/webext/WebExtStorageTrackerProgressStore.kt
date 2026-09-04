package com.dmarket.p2p.tracker.adapter.webext

import com.dmarket.p2p.tracker.credential.steam.DeviceVaultKeys
import com.dmarket.p2p.tracker.engine.FreshProofProgress
import com.dmarket.p2p.tracker.engine.ProofIntent
import com.dmarket.p2p.tracker.engine.ReportedStatus
import com.dmarket.p2p.tracker.loop.TrackerProgressStore
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.DirectiveId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.marketplace.DirectiveAction
import com.dmarket.p2p.tracker.model.marketplace.DirectiveOutcome
import com.dmarket.p2p.tracker.model.marketplace.DirectiveStatus
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusSource
import com.dmarket.p2p.tracker.wire.TrackerJson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.time.Instant

/**
 * JSON surrogate for [ReportedStatus] (the domain type is not `@Serializable`, keeping `:domain` pure).
 *
 * [historyInitiatorReported] defaults to `false`, so an entry written by an earlier build simply reads as
 * "no actor on record yet" — which re-opens rollback attribution for a deal that was baselined without one,
 * rather than leaving it parked forever. [historySettlementReported] defaults the same way and for the same
 * reason: an entry from a build that predates the settlement window reads as "no window sent yet", so the
 * deal gets one chance to report the window it was baselined without.
 */
@Serializable
private data class StoredReportedStatus(
    val lastOfferCode: Int? = null,
    val lastHistoryCode: Int? = null,
    val historyInitiatorReported: Boolean = false,
    val historySettlementReported: Boolean = false,
)

/**
 * JSON surrogate for one entry of the accepted-proof ledger.
 *
 * A flat record rather than a map keyed by an encoded `dealId|source|code` string: a DMarket deal id is itself
 * a composite (`<opaque>:<uuid>`), so an encoded key needs a separator that is guaranteed absent from it and a
 * parser that agrees with the writer forever. Naming the three fields removes both.
 */
@Serializable
private data class StoredAcceptedProof(val dealId: String, val source: String, val steamStatusCode: Int, val atMs: Long)

/**
 * JSON surrogate for [FreshProofProgress] — the per-deal standing against DMA-280's freshness mark.
 *
 * Instants are stored as epoch **millis**, matching [StoredAcceptedProof.atMs] and the domain's own
 * millisecond-floored mark. Storing the mark at any finer granularity would defeat the latch it exists to be:
 * a value read back coarser than it was written is strictly less than the same mark re-parsed from the next
 * heartbeat, so `incoming > satisfied` would hold forever.
 *
 * Every field defaults, so a row written by an earlier build (or a partially-written one) reads as "no mark
 * satisfied, no ladder" — which costs one re-proof rather than parking the deal.
 */
@Serializable
private data class StoredFreshProofProgress(
    val satisfiedMs: Long? = null,
    val attemptingMs: Long? = null,
    val attempts: Int = 0,
    val retryAtMs: Long? = null,
)

private fun FreshProofProgress.toStored(): StoredFreshProofProgress = StoredFreshProofProgress(
    satisfiedMs = satisfied?.toEpochMilliseconds(),
    attemptingMs = attempting?.toEpochMilliseconds(),
    attempts = attempts,
    retryAtMs = retryAt?.toEpochMilliseconds(),
)

private fun StoredFreshProofProgress.toDomain(): FreshProofProgress = FreshProofProgress(
    satisfied = satisfiedMs?.let(Instant::fromEpochMilliseconds),
    attempting = attemptingMs?.let(Instant::fromEpochMilliseconds),
    attempts = attempts,
    retryAt = retryAtMs?.let(Instant::fromEpochMilliseconds),
)

/** JSON surrogate for [DirectiveOutcome] (enums stored by wire name; the domain type stays non-`@Serializable`). */
@Serializable
private data class StoredDirectiveOutcome(
    val action: String,
    val status: String,
    val dealId: String? = null,
    val steamOfferId: String? = null,
    val error: String? = null,
)

private fun DirectiveOutcome.toStored(): StoredDirectiveOutcome = StoredDirectiveOutcome(
    action = action.wireName,
    status = status.wireName,
    dealId = dealId?.value,
    steamOfferId = steamOfferId?.value,
    error = error,
)

/** `null` when the stored action/status no longer parses (dropped, not fatal). */
private fun StoredDirectiveOutcome.toDomain(id: DirectiveId): DirectiveOutcome? {
    val action = DirectiveAction.fromWire(action).takeIf { it != DirectiveAction.UNKNOWN } ?: return null
    val status = DirectiveStatus.entries.firstOrNull { it.wireName == status } ?: return null
    return DirectiveOutcome(
        directiveId = id,
        action = action,
        status = status,
        dealId = dealId?.let(::DealId),
        steamOfferId = steamOfferId?.let(::OfferId),
        error = error,
    )
}

/**
 * [TrackerProgressStore] backed by `chrome.storage.local`, so the watch loop's per-deal reported codes
 * (dedup) and handled-directive set (single-flight) survive an MV3 service-worker respawn.
 */
class WebExtStorageTrackerProgressStore : TrackerProgressStore {
    private val reportedSerializer = MapSerializer(String.serializer(), StoredReportedStatus.serializer())
    private val handledSerializer = SetSerializer(String.serializer())
    private val outcomesSerializer = MapSerializer(String.serializer(), StoredDirectiveOutcome.serializer())
    private val acceptedProofsSerializer = ListSerializer(StoredAcceptedProof.serializer())

    /**
     * A plain `dealId -> bytes` map, with no surrogate record beside the two above: there is exactly one
     * scalar per deal and the key is a whole `dealId`, so nothing has to be encoded into it or parsed back
     * out — the hazard [StoredAcceptedProof] exists to avoid does not arise here.
     */
    private val onlineBudgetsSerializer = MapSerializer(String.serializer(), Int.serializer())

    /**
     * Keyed by the whole `dealId` for the same reason as the budgets above — one record per deal, nothing
     * encoded into the key — but with a surrogate record rather than a bare scalar, because the standing is
     * four values that are written by one fold. Splitting them across keys would make a half-updated ladder
     * representable, which is the argument [DeviceVaultKeys.NOTARY_PROOF_THROTTLE] already records.
     */
    private val freshProofSerializer = MapSerializer(String.serializer(), StoredFreshProofProgress.serializer())

    /**
     * Serialises the read-modify-write recorders below. Each of them loads the stored value, merges its own
     * update into it and writes the whole thing back, with a `storage.local` await in the middle — so two
     * overlapping calls would both read the pre-merge value and the second `set` would silently drop the
     * first one's update. That was unreachable while every writer ran sequentially inside one cycle; the
     * loop now runs its per-partner `create_offer` chains concurrently, and a lost
     * [recordHandledDirectives] write means a directive re-executed on the backend's next re-lease.
     *
     * **One lock per storage key**, not one for the store: the keys are independent, so a chain
     * recording a handled id has no reason to wait behind another chain recording an outcome. Sharing a
     * single lock would serialise exactly the writes the concurrent chains exist to overlap.
     * [recordDirectiveOutcome] and [clearDirectiveOutcomes] do share one — they share a key.
     *
     * Reads are deliberately left unguarded: they mutate nothing, and a reader that races a writer sees
     * either the old or the new value — both are states the caller already tolerates across respawns.
     */
    private val reportedMutex = Mutex()
    private val handledMutex = Mutex()
    private val outcomesMutex = Mutex()
    private val acceptedProofsMutex = Mutex()
    private val onlineBudgetsMutex = Mutex()
    private val freshProofMutex = Mutex()

    override suspend fun loadReported(): Map<DealId, ReportedStatus> {
        val json = webExtStorageGet(DeviceVaultKeys.TRACKER_REPORTED) ?: return emptyMap()
        val stored = runCatching { TrackerJson.decodeFromString(reportedSerializer, json) }.getOrNull() ?: return emptyMap()
        return stored.entries.associate { (id, s) ->
            DealId(id) to ReportedStatus(s.lastOfferCode, s.lastHistoryCode, s.historyInitiatorReported, s.historySettlementReported)
        }
    }

    override suspend fun recordReported(updates: Map<DealId, ReportedStatus>) = reportedMutex.withLock {
        val merged = (loadReported() + updates).entries.associate { (id, s) ->
            id.value to StoredReportedStatus(s.lastOfferCode, s.lastHistoryCode, s.historyInitiatorReported, s.historySettlementReported)
        }
        webExtStorageSet(DeviceVaultKeys.TRACKER_REPORTED, TrackerJson.encodeToString(reportedSerializer, merged))
    }

    override suspend fun loadHandledDirectives(): Set<DirectiveId> {
        val json = webExtStorageGet(DeviceVaultKeys.TRACKER_HANDLED_DIRECTIVES) ?: return emptySet()
        val stored = runCatching { TrackerJson.decodeFromString(handledSerializer, json) }.getOrNull() ?: return emptySet()
        return stored.map(::DirectiveId).toSet()
    }

    override suspend fun recordHandledDirectives(ids: Set<DirectiveId>) = handledMutex.withLock {
        val merged = (loadHandledDirectives().map { it.value } + ids.map { it.value }).toSet()
        webExtStorageSet(DeviceVaultKeys.TRACKER_HANDLED_DIRECTIVES, TrackerJson.encodeToString(handledSerializer, merged))
    }

    override suspend fun loadDirectiveOutcomes(): Map<DirectiveId, DirectiveOutcome> =
        loadStoredOutcomes().entries.mapNotNull { (id, stored) ->
            stored.toDomain(DirectiveId(id))?.let { DirectiveId(id) to it }
        }.toMap()

    override suspend fun recordDirectiveOutcome(outcome: DirectiveOutcome) = outcomesMutex.withLock {
        val merged = loadStoredOutcomes() + (outcome.directiveId.value to outcome.toStored())
        webExtStorageSet(DeviceVaultKeys.TRACKER_DIRECTIVE_OUTCOMES, TrackerJson.encodeToString(outcomesSerializer, merged))
    }

    override suspend fun clearDirectiveOutcomes(ids: Set<DirectiveId>) = outcomesMutex.withLock {
        val keys = ids.map { it.value }.toSet()
        val remaining = loadStoredOutcomes().filterKeys { it !in keys }
        webExtStorageSet(DeviceVaultKeys.TRACKER_DIRECTIVE_OUTCOMES, TrackerJson.encodeToString(outcomesSerializer, remaining))
    }

    override suspend fun loadAcceptedProofs(): Map<ProofIntent, Instant> = loadStoredAcceptedProofs().mapNotNull { stored ->
        // An entry whose axis no longer parses is dropped rather than fatal — same policy as a stored
        // directive outcome. Dropping one only costs a re-proof; failing the read would abort the pass.
        val source = TradeStatusSource.entries.firstOrNull { it.wireName == stored.source } ?: return@mapNotNull null
        ProofIntent(DealId(stored.dealId), source, stored.steamStatusCode) to Instant.fromEpochMilliseconds(stored.atMs)
    }.toMap()

    override suspend fun recordAcceptedProof(intent: ProofIntent, at: Instant) = acceptedProofsMutex.withLock {
        val entry = StoredAcceptedProof(
            dealId = intent.dealId.value,
            source = intent.source.wireName,
            steamStatusCode = intent.steamStatusCode,
            atMs = at.toEpochMilliseconds(),
        )
        // Replace any earlier verdict for the same transition rather than appending beside it: this is a map
        // keyed by the intent, stored as a list, so the identity has to be enforced on write.
        val merged = loadStoredAcceptedProofs().filterNot { it.identifies(intent) } + entry
        webExtStorageSet(DeviceVaultKeys.TRACKER_ACCEPTED_PROOFS, TrackerJson.encodeToString(acceptedProofsSerializer, merged))
    }

    override suspend fun clearAcceptedProofs(intents: Set<ProofIntent>) = acceptedProofsMutex.withLock {
        val remaining = loadStoredAcceptedProofs().filterNot { stored -> intents.any { stored.identifies(it) } }
        webExtStorageSet(DeviceVaultKeys.TRACKER_ACCEPTED_PROOFS, TrackerJson.encodeToString(acceptedProofsSerializer, remaining))
    }

    override suspend fun loadOnlineBudgets(): Map<DealId, Int> = loadStoredOnlineBudgets().mapKeys { (dealId, _) -> DealId(dealId) }

    override suspend fun recordOnlineBudget(dealId: DealId, bytes: Int) = onlineBudgetsMutex.withLock {
        val merged = loadStoredOnlineBudgets() + (dealId.value to bytes)
        webExtStorageSet(DeviceVaultKeys.TRACKER_ONLINE_BUDGETS, TrackerJson.encodeToString(onlineBudgetsSerializer, merged))
    }

    override suspend fun clearOnlineBudgets(dealIds: Set<DealId>) = onlineBudgetsMutex.withLock {
        val keys = dealIds.mapTo(HashSet()) { it.value }
        val remaining = loadStoredOnlineBudgets().filterKeys { it !in keys }
        webExtStorageSet(DeviceVaultKeys.TRACKER_ONLINE_BUDGETS, TrackerJson.encodeToString(onlineBudgetsSerializer, remaining))
    }

    override suspend fun loadFreshProofProgress(): Map<DealId, FreshProofProgress> =
        loadStoredFreshProof().entries.associate { (dealId, stored) -> DealId(dealId) to stored.toDomain() }

    override suspend fun recordFreshProofProgress(dealId: DealId, progress: FreshProofProgress) = freshProofMutex.withLock {
        val merged = loadStoredFreshProof() + (dealId.value to progress.toStored())
        webExtStorageSet(DeviceVaultKeys.TRACKER_PROVE_AFTER, TrackerJson.encodeToString(freshProofSerializer, merged))
    }

    override suspend fun clearFreshProofProgress(dealIds: Set<DealId>) = freshProofMutex.withLock {
        val keys = dealIds.mapTo(HashSet()) { it.value }
        val remaining = loadStoredFreshProof().filterKeys { it !in keys }
        webExtStorageSet(DeviceVaultKeys.TRACKER_PROVE_AFTER, TrackerJson.encodeToString(freshProofSerializer, remaining))
    }

    private suspend fun loadStoredFreshProof(): Map<String, StoredFreshProofProgress> {
        val json = webExtStorageGet(DeviceVaultKeys.TRACKER_PROVE_AFTER) ?: return emptyMap()
        return runCatching { TrackerJson.decodeFromString(freshProofSerializer, json) }.getOrNull() ?: emptyMap()
    }

    private suspend fun loadStoredOnlineBudgets(): Map<String, Int> {
        val json = webExtStorageGet(DeviceVaultKeys.TRACKER_ONLINE_BUDGETS) ?: return emptyMap()
        return runCatching { TrackerJson.decodeFromString(onlineBudgetsSerializer, json) }.getOrNull() ?: emptyMap()
    }

    private suspend fun loadStoredOutcomes(): Map<String, StoredDirectiveOutcome> {
        val json = webExtStorageGet(DeviceVaultKeys.TRACKER_DIRECTIVE_OUTCOMES) ?: return emptyMap()
        return runCatching { TrackerJson.decodeFromString(outcomesSerializer, json) }.getOrNull() ?: emptyMap()
    }

    private suspend fun loadStoredAcceptedProofs(): List<StoredAcceptedProof> {
        val json = webExtStorageGet(DeviceVaultKeys.TRACKER_ACCEPTED_PROOFS) ?: return emptyList()
        return runCatching { TrackerJson.decodeFromString(acceptedProofsSerializer, json) }.getOrNull() ?: emptyList()
    }
}

private fun StoredAcceptedProof.identifies(intent: ProofIntent): Boolean =
    dealId == intent.dealId.value && source == intent.source.wireName && steamStatusCode == intent.steamStatusCode
