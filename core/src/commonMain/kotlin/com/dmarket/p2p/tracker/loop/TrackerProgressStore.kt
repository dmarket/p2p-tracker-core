package com.dmarket.p2p.tracker.loop

import com.dmarket.p2p.tracker.engine.FreshProofProgress
import com.dmarket.p2p.tracker.engine.ProofIntent
import com.dmarket.p2p.tracker.engine.ReportedStatus
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.DirectiveId
import com.dmarket.p2p.tracker.model.marketplace.DirectiveOutcome
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant

/**
 * Cross-tick idempotency the loop persists (and that survives MV3 worker respawns):
 *
 * - **Reported status** per deal ([ReportedStatus]) so [com.dmarket.p2p.tracker.engine.TrackerTick]
 *   only reports a raw Steam code when it *changes* — never re-POSTing an unchanged code each poll.
 * - **Handled directive ids** so a leased [com.dmarket.p2p.tracker.model.marketplace.Directive] is executed at most
 *   once (single-flight), even across respawns.
 * - **Unacknowledged directive outcomes** so a handled directive whose `/trade-actions` report failed
 *   can have its stored [DirectiveOutcome] re-*sent* when the backend re-leases it — without this the
 *   handled set makes the client permanently silent while the backend re-serves forever.
 * - **Accepted proofs** per [ProofIntent] so a transition the backend has already vouched for is not
 *   re-proved on every cycle while its report keeps being refused — the one entry here that exists to bound
 *   *cost* rather than duplicate work, because a re-proof is a full MPC session (tens of seconds, tens of MB).
 * - **Learned online-decryption budgets** per deal, so the refused MPC session that bought each lesson is
 *   spent once rather than on every wake ([loadOnlineBudgets]).
 * - **Freshness standing** per deal — the greatest `prove_after` mark a verified proof has satisfied, plus
 *   the retry ladder for a refused one, so a demand answered once is not re-answered on every respawn
 *   ([loadFreshProofProgress]).
 *
 * On mobile, back this with a host-persisted store so it survives cold starts; an in-memory store
 * simply means one harmless re-report/re-execute after a restart (`/trade-events` is LWW and directive
 * execution is lease-guarded server-side, so correctness holds either way).
 *
 * **The recorders must be safe under concurrent invocation.** The loop runs its `create_offer` work as one
 * chain per counterparty, concurrently, so two chains legitimately call [recordHandledDirectives] /
 * [recordDirectiveOutcome] at the same time. A read-modify-write implementation (load, merge, store) must
 * hold a lock across the whole sequence — otherwise both callers read the pre-merge value and the second
 * store silently drops the first one's update, and a lost handled `directive_id` means that directive is
 * **re-executed** on the backend's next re-lease. Same requirement as
 * [DealWriteClaimStore.claim]'s, and for the same reason.
 */
interface TrackerProgressStore {
    suspend fun loadReported(): Map<DealId, ReportedStatus>

    /** Merge [updates] (per-deal reported codes advanced this tick) into the store. */
    suspend fun recordReported(updates: Map<DealId, ReportedStatus>)

    suspend fun loadHandledDirectives(): Set<DirectiveId>

    /** Add [ids] (directives executed this tick) to the handled set. */
    suspend fun recordHandledDirectives(ids: Set<DirectiveId>)

    /** Outcomes of handled directives the backend has not yet acknowledged (pruned once accepted). */
    suspend fun loadDirectiveOutcomes(): Map<DirectiveId, DirectiveOutcome>

    /** Store [outcome] so it can be re-sent if its `/trade-actions` report fails and the directive is re-leased. */
    suspend fun recordDirectiveOutcome(outcome: DirectiveOutcome)

    /** Drop the stored outcomes for [ids] (their reports were accepted — nothing left to re-send). */
    suspend fun clearDirectiveOutcomes(ids: Set<DirectiveId>)

    /**
     * When the backend last answered `verified = true` for each transition, so a proof it already holds is
     * not re-minted every cycle while the report it corroborates keeps being refused. Keyed on the whole
     * [ProofIntent] — deal, axis and the exact Steam code — because a later decisive code on the same axis is
     * a different transition and must earn its own proof.
     *
     * **Persisted, unlike the loop's in-memory refused-proof latch**, and that is the whole point: an MV3
     * service worker is respawned every few minutes, so an in-memory record would be re-armed on nearly every
     * wake and the client would go straight back to one MPC session per cycle. The reuse window itself lives
     * in `NotaryConfig.acceptedProofTtlMs`, which carries the reasoning and the measured cost.
     *
     * Defaulted (empty / no-op) so no existing host implementation has to change; a store that does not
     * override these simply keeps the pre-gate behaviour of proving on every cycle rather than failing to
     * compile.
     */
    suspend fun loadAcceptedProofs(): Map<ProofIntent, Instant> = emptyMap()

    /** Record that the backend verified a proof for [intent] at [at]. */
    suspend fun recordAcceptedProof(intent: ProofIntent, at: Instant) {
        // no-op default
    }

    /**
     * Drop the records for [intents] — their reports were accepted (nothing left to corroborate), a fresh
     * proof for the same transition was refused, or their deal is no longer tracked.
     */
    suspend fun clearAcceptedProofs(intents: Set<ProofIntent>) {
        // no-op default
    }

    /**
     * Per-deal online-decryption budgets learned from a refused proof — see
     * [com.dmarket.p2p.tracker.notary.OnlineBudgetLesson].
     *
     * Persisted for the same reason the accepted-proof ledger is: the lesson is bought with a real MPC session
     * (~29 MB), and an MV3 worker respawns between most cycles, so an in-memory-only value would be re-bought
     * on nearly every wake. Defaulted so a host with its own store keeps compiling and simply re-learns.
     */
    suspend fun loadOnlineBudgets(): Map<DealId, Int> = emptyMap()

    /** Record that [dealId]'s proven read needs at least [bytes] decrypted online. */
    suspend fun recordOnlineBudget(dealId: DealId, bytes: Int) {
        // no-op default
    }

    /**
     * Forget the budgets learned for [dealIds].
     *
     * Exists so the map stays bounded the way the accepted-proof ledger is: this is a permanent per-deal row
     * in `storage.local`, and a deal that terminates would otherwise leave one behind forever.
     */
    suspend fun clearOnlineBudgets(dealIds: Set<DealId>) {
        // no-op default
    }

    /**
     * Where each deal stands against the backend's freshness mark — see
     * [com.dmarket.p2p.tracker.engine.FreshProofProgress].
     *
     * **Persisted, and the latch does not latch otherwise.** The demand itself is *not* persisted: the
     * tracking list that carries `prove_after` is an in-memory field, re-supplied by every heartbeat and
     * re-read by every watch-only wake. So an in-memory satisfaction would be re-armed on nearly every
     * respawn and on every watch-only cycle, at one full MPC session each. The asymmetry is the point — a
     * demand lost to a respawn is re-delivered within one heartbeat, a *satisfaction* lost to one costs
     * ~30 MB to the notary.
     *
     * **Written only on the backend's `verified = true`**, never on mint and never on submission: a mark
     * recorded ahead of the backend's word is the stale-flag payout DMA-280 exists to stop, in miniature.
     * Deliberately **not** cleared by a forced heartbeat either — a user (or a cookie change) asking for a
     * cycle now says nothing about a mark the backend is holding.
     *
     * Defaulted (empty / no-op) so no existing host implementation has to change; a store that does not
     * override these simply re-proves a demand it has already answered, which is a cost rather than a stuck
     * deal.
     */
    suspend fun loadFreshProofProgress(): Map<DealId, FreshProofProgress> = emptyMap()

    /** Record [progress] as this device's standing against [dealId]'s freshness mark. */
    suspend fun recordFreshProofProgress(dealId: DealId, progress: FreshProofProgress) {
        // no-op default
    }

    /** Forget the freshness standing for [dealIds] — they have left the tracked set. */
    suspend fun clearFreshProofProgress(dealIds: Set<DealId>) {
        // no-op default
    }
}

/** In-memory [TrackerProgressStore] for a long-lived process (web service worker) or tests. */
class InMemoryTrackerProgressStore : TrackerProgressStore {
    private val mutex = Mutex()
    private val reported = mutableMapOf<DealId, ReportedStatus>()
    private val handled = mutableSetOf<DirectiveId>()
    private val outcomes = mutableMapOf<DirectiveId, DirectiveOutcome>()
    private val acceptedProofs = mutableMapOf<ProofIntent, Instant>()
    private val onlineBudgets = mutableMapOf<DealId, Int>()
    private val freshProofProgress = mutableMapOf<DealId, FreshProofProgress>()

    override suspend fun loadReported(): Map<DealId, ReportedStatus> = mutex.withLock { reported.toMap() }

    override suspend fun recordReported(updates: Map<DealId, ReportedStatus>) {
        mutex.withLock { reported.putAll(updates) }
    }

    override suspend fun loadHandledDirectives(): Set<DirectiveId> = mutex.withLock { handled.toSet() }

    override suspend fun recordHandledDirectives(ids: Set<DirectiveId>) {
        mutex.withLock { handled.addAll(ids) }
    }

    override suspend fun loadDirectiveOutcomes(): Map<DirectiveId, DirectiveOutcome> = mutex.withLock { outcomes.toMap() }

    override suspend fun recordDirectiveOutcome(outcome: DirectiveOutcome) {
        mutex.withLock { outcomes[outcome.directiveId] = outcome }
    }

    override suspend fun clearDirectiveOutcomes(ids: Set<DirectiveId>) {
        mutex.withLock { ids.forEach(outcomes::remove) }
    }

    override suspend fun loadAcceptedProofs(): Map<ProofIntent, Instant> = mutex.withLock { acceptedProofs.toMap() }

    override suspend fun recordAcceptedProof(intent: ProofIntent, at: Instant) {
        mutex.withLock { acceptedProofs[intent] = at }
    }

    override suspend fun clearAcceptedProofs(intents: Set<ProofIntent>) {
        mutex.withLock { intents.forEach(acceptedProofs::remove) }
    }

    override suspend fun loadOnlineBudgets(): Map<DealId, Int> = mutex.withLock { onlineBudgets.toMap() }

    override suspend fun recordOnlineBudget(dealId: DealId, bytes: Int) {
        mutex.withLock { onlineBudgets[dealId] = bytes }
    }

    override suspend fun clearOnlineBudgets(dealIds: Set<DealId>) {
        mutex.withLock { dealIds.forEach(onlineBudgets::remove) }
    }

    override suspend fun loadFreshProofProgress(): Map<DealId, FreshProofProgress> = mutex.withLock { freshProofProgress.toMap() }

    override suspend fun recordFreshProofProgress(dealId: DealId, progress: FreshProofProgress) {
        mutex.withLock { freshProofProgress[dealId] = progress }
    }

    override suspend fun clearFreshProofProgress(dealIds: Set<DealId>) {
        mutex.withLock { dealIds.forEach(freshProofProgress::remove) }
    }
}
