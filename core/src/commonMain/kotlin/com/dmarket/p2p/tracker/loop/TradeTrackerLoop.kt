package com.dmarket.p2p.tracker.loop

import com.dmarket.p2p.tracker.adapter.host.NoOpEventObserver
import com.dmarket.p2p.tracker.adapter.host.NoOpPushChannel
import com.dmarket.p2p.tracker.adapter.notary.NoOpNotaryProver
import com.dmarket.p2p.tracker.adapter.steam.NoOpSteamInventoryReader
import com.dmarket.p2p.tracker.adapter.steam.NoOpSteamNotificationReader
import com.dmarket.p2p.tracker.adapter.steam.NoOpSteamOfferCanceller
import com.dmarket.p2p.tracker.adapter.steam.NoOpSteamOfferCreator
import com.dmarket.p2p.tracker.client.marketplace.RateLimitedException
import com.dmarket.p2p.tracker.credential.steam.SteamCredentialProvider
import com.dmarket.p2p.tracker.engine.AccountBinding
import com.dmarket.p2p.tracker.engine.AccountBindingStatus
import com.dmarket.p2p.tracker.engine.BaselineSeed
import com.dmarket.p2p.tracker.engine.BlockingState
import com.dmarket.p2p.tracker.engine.ClaimVerdict
import com.dmarket.p2p.tracker.engine.DealRoleBinding
import com.dmarket.p2p.tracker.engine.DealWriteGuard
import com.dmarket.p2p.tracker.engine.DirectiveAcknowledgement
import com.dmarket.p2p.tracker.engine.DirectivePlanner
import com.dmarket.p2p.tracker.engine.ExpeditedTransitions
import com.dmarket.p2p.tracker.engine.FreshProofDemand
import com.dmarket.p2p.tracker.engine.FreshProofProgress
import com.dmarket.p2p.tracker.engine.ObservedTrade
import com.dmarket.p2p.tracker.engine.ProofFreshness
import com.dmarket.p2p.tracker.engine.ProofIntent
import com.dmarket.p2p.tracker.engine.ProofMintPolicy
import com.dmarket.p2p.tracker.engine.ProofMintVerdict
import com.dmarket.p2p.tracker.engine.ReportAck
import com.dmarket.p2p.tracker.engine.ReportAcknowledgement
import com.dmarket.p2p.tracker.engine.ReportPlan
import com.dmarket.p2p.tracker.engine.ReportedStatus
import com.dmarket.p2p.tracker.engine.TrackerBlock
import com.dmarket.p2p.tracker.engine.TrackerTick
import com.dmarket.p2p.tracker.engine.TransferCorrelation
import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.ClaimPhase
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.DealWriteClaim
import com.dmarket.p2p.tracker.model.DealWriteKey
import com.dmarket.p2p.tracker.model.DirectiveId
import com.dmarket.p2p.tracker.model.LifecycleEvent
import com.dmarket.p2p.tracker.model.PushSignal
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.TrackerMode
import com.dmarket.p2p.tracker.model.marketplace.Directive
import com.dmarket.p2p.tracker.model.marketplace.DirectiveAction
import com.dmarket.p2p.tracker.model.marketplace.DirectiveOutcome
import com.dmarket.p2p.tracker.model.marketplace.DirectiveStatus
import com.dmarket.p2p.tracker.model.marketplace.HeartbeatRequest
import com.dmarket.p2p.tracker.model.marketplace.HeartbeatResponse
import com.dmarket.p2p.tracker.model.marketplace.InventoryReport
import com.dmarket.p2p.tracker.model.marketplace.ProofResult
import com.dmarket.p2p.tracker.model.marketplace.TrackedDeal
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusReport
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusSource
import com.dmarket.p2p.tracker.model.marketplace.watches
import com.dmarket.p2p.tracker.model.steam.InventoryScan
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.model.steam.SteamOfferSnapshot
import com.dmarket.p2p.tracker.model.steam.SteamTransfer
import com.dmarket.p2p.tracker.model.steam.TradeDraft
import com.dmarket.p2p.tracker.net.redactedRemoteText
import com.dmarket.p2p.tracker.net.redactedSummary
import com.dmarket.p2p.tracker.notary.OnlineBudgetLesson
import com.dmarket.p2p.tracker.notary.ProvenReadBinding
import com.dmarket.p2p.tracker.policy.CadencePolicy
import com.dmarket.p2p.tracker.policy.CreateChain
import com.dmarket.p2p.tracker.policy.CreateChainPlanner
import com.dmarket.p2p.tracker.policy.PollClass
import com.dmarket.p2p.tracker.policy.SteamCreateFailureCause
import com.dmarket.p2p.tracker.policy.SteamWriteThrottle
import com.dmarket.p2p.tracker.policy.WriteGate
import com.dmarket.p2p.tracker.port.host.Clock
import com.dmarket.p2p.tracker.port.host.DeviceIdStore
import com.dmarket.p2p.tracker.port.host.EventObserver
import com.dmarket.p2p.tracker.port.host.PushChannel
import com.dmarket.p2p.tracker.port.host.Scheduler
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceClient
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceCredentialProvider
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceServerErrorException
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceUnauthorizedException
import com.dmarket.p2p.tracker.port.notary.NotaryProver
import com.dmarket.p2p.tracker.port.steam.CreateOfferResult
import com.dmarket.p2p.tracker.port.steam.SteamInventoryReader
import com.dmarket.p2p.tracker.port.steam.SteamNotificationReader
import com.dmarket.p2p.tracker.port.steam.SteamOfferCanceller
import com.dmarket.p2p.tracker.port.steam.SteamOfferCreator
import com.dmarket.p2p.tracker.port.steam.SteamReadClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** A compact, JS-friendly summary of one cycle (the loop decides nothing about settlement). */
data class TickOutcome(val directivesExecuted: Int = 0, val reportsSent: Int = 0, val proofsSubmitted: Int = 0, val watching: Int = 0) {
    companion object {
        val EMPTY: TickOutcome = TickOutcome()
    }
}

/** Reported outcome error for a create blocked by the wrong-account guard (releases the backend lease). */
private const val ACCOUNT_MISMATCH_ERROR =
    "steam account mismatch: the DMarket-linked Steam account differs from the authenticated Steam session"

/** Reported outcome error for a write the deal-keyed claim guard suppressed (defensive; see [DealWriteGuard]). */
private const val DUPLICATE_WRITE_ERROR = "duplicate write suppressed: this device already performed this write for the deal"

/**
 * Reported outcome error for a write refused because this account is the **buyer** on the deal (see
 * [DealRoleBinding]). Reported rather than dropped so the backend's directive lease is released — and so a
 * lease it should never have handed out is visible on its side, not only in our session log.
 */
private const val BUYER_ROLE_WRITE_ERROR =
    "refused: active_tracking reports this account as the buyer on this deal, and only the seller writes to Steam"

/**
 * Reported outcome error for a create the throttle deferred — only ever sent when a host opts in via
 * [SteamWriteConfig.reportThrottledWrites]; by default a deferred create is not reported at all.
 */
private const val THROTTLED_WRITE_ERROR = "deferred: this device is backing off steam's create surface"

/** Defer reason for the creates left in a chain that stopped part-way through. */
private const val PARTNER_PARKED_MID_CHAIN = "counterparty's create chain stopped earlier this cycle"

/**
 * Why a report was not sent. Names the *dependency*, not the mechanism: the backend will refuse this exact
 * code with `P2P_PROOF_REQUIRED` until its proof verifies, so sending it is a guaranteed round trip to a
 * rejection. Withheld, not dropped — the dedup baseline is untouched, so the code is re-detected next cycle.
 */
private const val REPORT_AWAITS_PROOF = "withheld until this transition's proof verifies"

/**
 * `ProofSuppressed.reason` for a demand this build cannot answer because no real prover is selected — every
 * host with no proving context, Firefox today and any caller that passes no prover at all. Said out loud
 * rather than skipped silently, because from the backend's side it is indistinguishable from a client that
 * never received the mark.
 */
private const val NO_PROVER_FOR_DEMAND = "a fresh proof was demanded but no notary prover is configured"

/**
 * `ProofSuppressed.reason` for a transition whose proof this cycle skips because a freshness demand on the
 * same axis is already proving the identical read. Not a failure and not dedup — the report it corroborates
 * waits one cycle rather than buying a second MPC session for the same fact.
 */
private const val SUPERSEDED_BY_DEMAND = "a freshness demand on this axis is proving the same read this cycle"

/** The defer reason a standing cooldown produces, named by the scope it covers. */
private fun CreateOfferResult.Throttled.deferReason(): String = "${scope.name.lowercase()} cooldown standing after a steam refusal"

/**
 * What one attempt at a Steam **write** directive (`create_offer` / `cancel_offer`) left behind: the [outcome]
 * to report in this cycle's single batched `/trade-actions` call (`null` when there is nothing to report),
 * whether it reached Steam ([wroteToSteam] — which is what makes its stored outcome prunable once acked), and,
 * for a create, whether the rest of that counterparty's chain should be abandoned ([stopChain], with [reason]
 * for the event that says so).
 *
 * The stop/report split matters because "this directive is done with" and "this counterparty is done for now"
 * are different facts: a buyer-role refusal is the first without the second, a Steam quota refusal is both.
 */
private data class WriteAttempt(
    val outcome: DirectiveOutcome? = null,
    val wroteToSteam: Boolean = false,
    val stopChain: Boolean = false,
    val reason: String? = null,
    /** Set when the attempt was already reported and tallied on its own, outside the batch (a replayed duplicate). */
    val countedOutOfBand: Boolean = false,
) {
    /**
     * Whether this attempt counts towards the cycle's executed-directive tally: a real Steam write always does,
     * and so does anything the backend acknowledged — the same "it wrote, or the backend took our word for it"
     * rule the per-outcome reporting used, now evaluated after the batch's acks are known.
     */
    fun counted(accepted: Map<DirectiveId, Boolean>): Boolean =
        countedOutOfBand || wroteToSteam || (outcome != null && accepted[outcome.directiveId] == true)

    /** The stored outcome this attempt may prune once the backend acks it — only a real write has one. */
    fun prunableId(accepted: Map<DirectiveId, Boolean>): DirectiveId? =
        outcome?.directiveId?.takeIf { wroteToSteam && accepted[it] == true }

    companion object {
        /** Nothing was written and nothing is wrong with the chain — a per-deal guard declined this one. */
        val SKIPPED: WriteAttempt = WriteAttempt()
    }
}

/**
 * How many heartbeats in a row must fail with a **transient** DMarket server error (5xx) before it is
 * surfaced as [TrackerBlock.DM_CONNECTION_ERROR], so a single server blip doesn't flash the error prompt.
 * A **deterministic** client error (a non-401 4xx such as 404) surfaces on the first failure — retrying
 * it won't help.
 */
private const val SERVER_ERROR_THRESHOLD = 2

/**
 * Steam `ETradeStatus` for a trade-protection rollback — the only history code that carries reversal
 * attribution, and the only one that triggers the notification read.
 */
private const val ROLLBACK_STATUS = 12

/**
 * The IO loop driver for the **C1 trade-tracker** (base `/exchange/v1/p2p/ext/`): the single point
 * that coordinates every port into one cycle.
 *
 * **The cycle:**
 * 1. **heartbeat** — `POST /heartbeat` with `device_id` + `foreground` + `steam_id`; receive
 *    `active_tracking[]` (deals to watch) + `directives[]` (leased commands) + `ttl_seconds`.
 * 2. **execute directives** ([DirectivePlanner]) — `create_offer` / `cancel_offer` (the only Steam
 *    writes; stop at `CreatedNeedsConfirmation`) / `report_inventory`; report each outcome
 *    (`/trade-actions`, `/inventory`). Single-flight by `directive_id`; gated behind [directivesEnabled].
 * 3. **watch + report** ([TrackerTick]) — read both Steam axes for the tracked deals, report **raw**
 *    codes on `/trade-events` (no verdict), and when a deal is `proof_required` submit the decisive
 *    proof on `/notary`.
 *
 * The client decides nothing about settlement or the account — the backend maps the raw codes and reads
 * identity from the Bearer token. There is no `account_id` anywhere in a request.
 *
 * @param deviceId supplies the install-scoped persistent `device_id` (the directive-lease key).
 * @param directivesEnabled the launch gate: keep `false` until the backend `device_id` Redis lease is
 *   live (until then the backend returns no directives anyway; this is belt-and-suspenders).
 * @param notary TLSN proof generator. Defaults to [NoOpNotaryProver] (MVP stub — client-reported).
 * @param progress per-deal reported-code dedup + handled-directive single-flight; defaults to in-memory.
 * @param claims the deal-keyed guard in front of both non-idempotent Steam writes ([DealWriteGuard]), so
 *   one deal can never get two live offers however many times a caller asks. Must be a **single instance
 *   per process** — its lock is what makes concurrent claims safe.
 */
class TradeTrackerLoop(
    val config: LoopConfig,
    private val marketplace: MarketplaceClient,
    private val steamReader: SteamReadClient,
    private val credentials: SteamCredentialProvider,
    private val scheduler: Scheduler,
    private val clock: Clock,
    private val deviceId: DeviceIdStore,
    private val inventoryReader: SteamInventoryReader = NoOpSteamInventoryReader,
    private val cadence: CadencePolicy = CadencePolicy(config.tunables.cadence),
    private val notary: NotaryProver = NoOpNotaryProver,
    private val offerCreator: SteamOfferCreator = NoOpSteamOfferCreator,
    private val offerCanceller: SteamOfferCanceller = NoOpSteamOfferCanceller,
    private val marketplaceCredentials: MarketplaceCredentialProvider? = null,
    private val progress: TrackerProgressStore = InMemoryTrackerProgressStore(),
    private val claims: DealWriteClaimStore = PersistedDealWriteClaimStore(),
    /**
     * The `create_offer` back-pressure ledger. Persisted by default for the same reason the claim store is:
     * the heartbeat TTL is shorter than the MV3 idle timeout, so an in-memory cooldown would be forgotten on
     * nearly every wake and the client would re-hit a partner Steam is still refusing.
     */
    private val throttle: SteamWriteThrottleStore = PersistedSteamWriteThrottleStore(config.tunables.steamWrites),
    private val loopState: LoopStateStore = InMemoryLoopStateStore(),
    private val pushChannel: PushChannel = NoOpPushChannel,
    private val directivesEnabled: Boolean = false,
    private val eventObserver: EventObserver = NoOpEventObserver,
    /**
     * Resolves who reversed a trade, for history rollback reports only. Defaults to the no-op, so
     * attribution is opt-in and an unwired host simply reports rollbacks without an initiator.
     */
    private val notifications: SteamNotificationReader = NoOpSteamNotificationReader,
    /**
     * The proof-generation back-pressure ledger. Persisted by default for the same reason [throttle] is, and
     * the cost it bounds is larger: every attempt is a full MPC session (~30 MB measured), so a cooldown
     * forgotten on each MV3 respawn would be re-spent on every wake.
     */
    private val notaryThrottle: NotaryProofThrottleStore =
        PersistedNotaryProofThrottleStore(config.tunables.notary.breaker),
    /**
     * Jitter for the freshness axis's retry ladder ([ProofFreshness.refused]). Injectable for the same reason
     * the throttle stores take one — the draw is the thing under test — and defaulted so no host changes.
     */
    private val random: Random = Random.Default,
) {
    init {
        // A proven write is a write the PROVER performs — TLSN requires the prover to be the TLS client, so
        // there is no way to witness the POST `SteamOfferCreator` already made. Enabling one therefore has to
        // *replace* this loop's own write, and that routing is not built: leaving it unguarded would mean two
        // creates for one directive, i.e. two live Steam offers against a partner quota of five.
        //
        // So the definitions, the specs and the IO edge all exist and are exercisable through the debug
        // harness, and the one thing that cannot happen quietly is a double write.
        // Read off the resolved `ProvenRead`, never off the kind: `method != "GET"` is the single fact that
        // makes something a write, and a second copy of it on the enum is the one that would drift — leaving
        // this guard, which exists precisely to catch a write, reading the stale answer.
        val notary = config.tunables.notary
        val writes = notary.enabledReads.filter { notary.provenRead(it).isWrite }
        require(writes.isEmpty()) {
            "NotaryConfig.enabledReads names $writes, and a proven write is performed BY the prover — the " +
                "directive loop has no routing to hand its write over, so enabling one here would create " +
                "twice. Inspect write specs with the debug harness's provenReadSpec probe until that " +
                "routing exists."
        }
    }

    /**
     * Create the Steam trade offer for a committed deal — the FE "create trade" trigger, routed here by
     * the debug harness when it receives a validated `window.postMessage`. POSTs `…/tradeoffer/new/send`
     * and stops at NeedsConfirmation (the user confirms on the official Steam app; the plugin never
     * confirms). The device-only credential stays inside the loop and never crosses any boundary. Returns
     * the [CreateOfferResult] so the caller can surface the created offer id.
     *
     * The FE supplies the leased `directive_id` **and** the deal's [dealId] (the DMarket deal key, not
     * the Steam offer id) — the `/trade-actions` outcome report requires `deal_id`; the backend rejects
     * it otherwise and the directive lease stays held until its TTL expires.
     */
    suspend fun createTrade(
        directiveId: DirectiveId,
        dealId: DealId?,
        draft: TradeDraft,
        linkedSteamId: SteamId? = null,
    ): CreateOfferResult {
        val credential = credentials.current()
            ?: return CreateOfferResult.Failed("no Steam credential")
                .also { emit(LifecycleEvent.DirectiveExecuted("create_offer", "FAILED")) }
        // The FE handed us the backend's create_offer directive_id out-of-band (no heartbeat wait), so the
        // extension itself registers the outcome (steam_offer_id + status) with the backend on /trade-actions.
        val directive = Directive(
            directiveId = directiveId,
            action = DirectiveAction.CREATE_OFFER,
            dealId = dealId,
            partnerSteamId = draft.partner,
            assetIds = draft.assetsToGive,
            tradeToken = draft.tradeToken,
        )
        // Wrong-account guard: the FE tells us which Steam id the DMarket account is linked to. If it
        // disagrees with the token we hold — or the browser session the write would ride belongs to
        // another account ([accountAllowsWrite] axis 2) — never write to Steam: notify, report FAILED to
        // release the lease, and return a distinguishable AccountMismatch so the FE shows "log into the
        // right account".
        //
        // Fail CLOSED: the host argument is optional, so a caller that simply omits it must not thereby
        // earn a Steam write on a session the last heartbeat already proved is wrong-account. Fall back
        // to the loop's own verdict ([linkedSteamIdMismatch]) and to the linked id that heartbeat saw,
        // so the guard holds regardless of what the host passes.
        val expected = linkedSteamId ?: lastLinkedSteamId
        // Evaluated before the `||` so its own event always fires; the sticky is the extra fail-closed leg.
        val allowed = accountAllowsWrite(expected, credential.subjectSteamId, DirectiveAction.CREATE_OFFER.wireName)
        if (!allowed || linkedSteamIdMismatch) {
            reportOutcome(directive.outcome(DirectiveStatus.FAILED, error = ACCOUNT_MISMATCH_ERROR))
            emit(LifecycleEvent.DirectiveExecuted("create_offer", DirectiveStatus.FAILED.name))
            return CreateOfferResult.AccountMismatch(expected ?: credential.subjectSteamId, credential.subjectSteamId)
        }
        // Role guard. This path needs it most: the host synthesises the directive itself, so the backend's
        // decision not to lease a write for a deal we are BUYING never reaches it — the last heartbeat's
        // tracking list is the only thing that can say so. Unknown (no heartbeat yet, deal not listed) allows
        // the write: the FE's create trigger must keep working on a cold worker.
        if (!roleAllowsWrite(lastActiveTracking, directive)) return CreateOfferResult.Failed(BUYER_ROLE_WRITE_ERROR)
        // Create-surface back-pressure. Reported to nobody and claimed against nothing: no write is attempted,
        // so the caller is free to try again once the cooldown elapses.
        throttledCreate(draft.partner)?.let { throttled ->
            emitDeferred(directiveId, dealId, throttled.deferReason(), throttled.retryAfterSeconds)
            return throttled
        }
        // Deal-keyed duplicate guard. THE reason it is here and not only on the directive path: this entry
        // point is called straight from the host (an FE relay), outside [cycleMutex], with whatever
        // directive_id the caller happens to hold — so a host that fires the same request three times, or
        // three times under three fresh ids, would otherwise produce three live Steam offers.
        return withDealClaim(
            dealId = dealId,
            action = DirectiveAction.CREATE_OFFER,
            directiveId = directiveId,
            onDuplicate = { verdict -> duplicateCreateResult(verdict, directiveId) },
        ) {
            val result = runCatching { offerCreator.createOffer(credential, draft) }
                .getOrElse { CreateOfferResult.Failed(it.redactedSummary()) }
                .diagnosed()
            val wroteToSteam = result is CreateOfferResult.NeedsConfirmation || result is CreateOfferResult.Created
            val outcome = when (result) {
                is CreateOfferResult.NeedsConfirmation -> directive.outcome(
                    DirectiveStatus.NEEDS_CONFIRMATION,
                    steamOfferId = result.offerId.value,
                )
                is CreateOfferResult.Created -> directive.outcome(DirectiveStatus.SUCCESS, steamOfferId = result.offerId.value)
                is CreateOfferResult.Failed -> directive.outcome(DirectiveStatus.FAILED, error = result.error)
                // offerCreator never returns AccountMismatch or either duplicate verdict (the guards
                // above short-circuit first); handled defensively as FAILED to keep the path exhaustive.
                is CreateOfferResult.AccountMismatch -> directive.outcome(DirectiveStatus.FAILED, error = ACCOUNT_MISMATCH_ERROR)
                is CreateOfferResult.AlreadyCreated, is CreateOfferResult.CreateInFlight ->
                    directive.outcome(DirectiveStatus.FAILED, error = DUPLICATE_WRITE_ERROR)
                // Unreachable: the gate above already answered a throttled create, and a SteamOfferCreator
                // never throttles itself. Defensive, to keep this `when` exhaustive.
                is CreateOfferResult.Throttled -> directive.outcome(DirectiveStatus.FAILED, error = THROTTLED_WRITE_ERROR)
            }
            // Feed the surface's own verdict back before anything else: a Steam refusal here is the same
            // signal as one on the directive path, and the next caller (either path) must see the cooldown.
            recordCreateResult(draft.partner, result)
            // Mark handled the moment the Steam write succeeds so a later heartbeat re-lease of this
            // directive_id can never re-execute the create (duplicate live Steam offer); the persisted
            // outcome lets the heartbeat path re-send the report if the one below fails.
            if (wroteToSteam) markHandled(outcome)
            // A freshly-created offer sits at CreatedNeedsConfirmation waiting for the seller's mobile
            // confirmation — a decisive 9 → 2 transition is imminent. Open the expedited window and re-arm
            // the wake now (this is an out-of-band FE trigger, not inside a cycle), so we don't idle on a
            // stale ~3-min alarm before the first re-check. Mirrors deliverPush's re-arm.
            if (result is CreateOfferResult.NeedsConfirmation) {
                armExpedited()
                runCatching { scheduler.schedule(nextWakeDelay()) }
            }
            val accepted = reportOutcome(outcome)
            if (wroteToSteam && accepted) pruneOutcome(directiveId)
            emit(LifecycleEvent.DirectiveExecuted("create_offer", outcome.status.name, outcome.steamOfferId?.value))
            // Only a real Steam write completes the claim; a FAILED create wrote nothing, so releasing it
            // leaves a genuine retry (a re-lease, or the user pressing the button again) free to proceed.
            result to outcome.takeIf { wroteToSteam }
        }
    }

    /**
     * The [CreateOfferResult] for a create suppressed by the claim guard. A completed claim replays the
     * offer it created — so a duplicate request renders the real offer instead of an error — and re-reports
     * that outcome under the *requesting* [directiveId], which releases the (otherwise dangling) lease the
     * caller was holding. An in-flight claim has nothing to replay yet.
     */
    private suspend fun duplicateCreateResult(verdict: ClaimVerdict.Duplicate, directiveId: DirectiveId): CreateOfferResult {
        val dealId = verdict.claim.dealId
        if (verdict !is ClaimVerdict.AlreadyCompleted) return CreateOfferResult.CreateInFlight(dealId)
        val stored = resendClaimedOutcome(verdict.claim, directiveId)
        // A COMPLETED create claim always carries the offer it created (only a real Steam write completes
        // one), so the fallback is unreachable — kept so a corrupt restored row degrades to "wait for the
        // first create" rather than to a second Steam write.
        return stored?.steamOfferId?.let(CreateOfferResult::AlreadyCreated) ?: CreateOfferResult.CreateInFlight(dealId)
    }

    /**
     * Re-reports a completed claim's stored outcome under the **requesting** [directiveId] and returns it.
     * The duplicate caller is holding a different, still-unreported lease — the backend keeps it (and the
     * deal) parked until its TTL expires unless we answer it, and the honest answer is the offer the first
     * write already produced. Same `steam_offer_id`, so the backend sees a restatement, never a second offer.
     */
    private suspend fun resendClaimedOutcome(claim: DealWriteClaim, directiveId: DirectiveId): DirectiveOutcome? {
        val stored = claim.outcome ?: return null
        val restated = stored.copy(directiveId = directiveId, dealId = claim.dealId)
        val accepted = reportOutcome(restated)
        // Record it as handled either way: the directive_id must never reach the Steam write again. An
        // unaccepted report keeps the stored outcome so the heartbeat's resend path retries it.
        markHandled(restated)
        if (accepted) pruneOutcome(directiveId)
        return restated
    }

    /**
     * Reports a directive outcome on `/trade-actions`, surfacing a rejected or failed report as a
     * [LifecycleEvent.DirectiveReportFailed] (an unaccepted report leaves the backend's device lease
     * held until its TTL expires — invisible in the session log without this). Returns the ack's
     * `accepted`, `false` when the call threw.
     */
    private suspend fun reportOutcome(outcome: DirectiveOutcome): Boolean = reportOutcomes(listOf(outcome))[outcome.directiveId] == true

    /**
     * Reports [outcomes] on `/trade-actions` in **one** call and returns which of them the backend accepted,
     * keyed by `directive_id`. Every unaccepted outcome — rejected, absent from the response, or lost to a
     * throw that failed the whole batch — gets its own [LifecycleEvent.DirectiveReportFailed], because an
     * unaccepted report leaves that directive's lease held until its TTL expires and is invisible in the
     * session log otherwise.
     *
     * Absent is deliberately read as **not** accepted: the caller keeps the stored outcome and resends it when
     * the backend re-leases, which is strictly safer than assuming silence meant success and pruning it.
     */
    private suspend fun reportOutcomes(outcomes: List<DirectiveOutcome>): Map<DirectiveId, Boolean> {
        if (outcomes.isEmpty()) return emptyMap()
        val result = runCatching { marketplace.reportDirectives(outcomes) }
        // Non-null only when the call itself failed, in which case it is the reason for every action in the
        // batch — more informative than the matcher's "no result", which is about a response that did arrive.
        val transportFailure = result.exceptionOrNull()?.redactedSummary()
        val paired = DirectiveAcknowledgement.match(outcomes, result.getOrNull().orEmpty())
        paired.forEach { ack ->
            if (ack.accepted) return@forEach
            emit(
                LifecycleEvent.DirectiveReportFailed(
                    kind = ack.outcome.action.wireName,
                    directiveId = ack.outcome.directiveId.value,
                    // The backend's own string is scrubbed + capped like a failure summary: it is unbounded
                    // and can echo the request it rejected, and this event may be forwarded by the host.
                    reason = transportFailure ?: ack.reason.redactedRemoteText(),
                ),
            )
        }
        return paired.associate { it.outcome.directiveId to it.accepted }
    }

    /**
     * Runs a non-idempotent Steam write for [dealId] behind the deal-keyed claim guard — the single choke
     * point in front of both write surfaces, shared by the host fast path ([createTrade]) and the leased
     * directive path ([runCreate] / [runCancel]).
     *
     * The claim take is atomic in the store, so this is also what serialises **concurrent** callers: they
     * matter on every target (an FE relay firing twice on the web, genuinely parallel threads on mobile),
     * and [cycleMutex] does not cover the host entry points.
     *
     * [write] returns its own result plus the [DirectiveOutcome] of a **real Steam write**, or `null` when
     * nothing was written (a rejected create) — in which case the claim is released immediately so a
     * genuine retry is free to proceed. A throw releases it too: a leaked in-flight claim would block the
     * deal until the TTL backstop.
     */
    private suspend fun <T> withDealClaim(
        dealId: DealId?,
        action: DirectiveAction,
        directiveId: DirectiveId,
        onDuplicate: suspend (ClaimVerdict.Duplicate) -> T,
        write: suspend () -> Pair<T, DirectiveOutcome?>,
    ): T {
        // A deal-less write cannot be claim-guarded. Only report_inventory is deal-less and it never routes
        // here (a re-scan is idempotent), so this is the malformed-directive case: proceed rather than
        // strand it — the planner's own validation is what rejects those.
        if (dealId == null) return write().first
        val key = DealWriteKey(dealId, action)
        val ttl = config.tunables.writeClaims.claimTtl
        val now = clock.now()
        val pending = DealWriteClaim(dealId, action, ClaimPhase.IN_FLIGHT, now, directiveId)
        val verdict = claims.claim(pending, now, ttl)
        if (verdict is ClaimVerdict.Duplicate) {
            emit(
                LifecycleEvent.DuplicateWriteSuppressed(
                    kind = action.wireName,
                    dealId = dealId.value,
                    directiveId = directiveId.value,
                    phase = verdict.claim.phase.name,
                    steamOfferId = verdict.claim.outcome?.steamOfferId?.value,
                ),
            )
            return onDuplicate(verdict)
        }
        val (result, outcome) = try {
            write()
        } catch (t: Throwable) {
            claims.release(setOf(key))
            throw t
        }
        if (outcome != null) claims.complete(key, outcome) else claims.release(setOf(key))
        return result
    }

    /** Emit a lifecycle event to the (optional) observer; never lets a sink failure break the cycle. */
    private suspend fun emit(event: LifecycleEvent) {
        if (eventObserver !== NoOpEventObserver) runCatching { eventObserver.onEvent(event) }
    }

    /**
     * Pre-write wrong-account guard in front of **both** Steam write surfaces, at all three write sites
     * ([runCreate] and [runCancel] on the directive path, [createTrade] on the FE fast path). Returns
     * `true` when the write may proceed; otherwise it emits the matching event and returns `false` so the
     * caller aborts before any Steam request. [kind] names the write for that event.
     *
     * It checks **both** Steam identity axes, because they authenticate differently and either one being
     * the wrong account is enough to make the write land on the wrong account:
     *
     * 1. **The read/DMarket axis** — [expected] (the DMarket-linked Steam id, from the heartbeat or an FE
     *    `postMessage`) against [token], the held credential's `subjectSteamId`. Steam *reads* pass that
     *    credential as an `access_token`, so this is what binds the account we report on.
     * 2. **The write axis** — the browser's `steamLoginSecure` cookie session against [token]. The two
     *    writes POST with the ambient cookie session and never use the credential they are handed, so axis
     *    1 can read MATCH — both of its ids being the *token's* account — while the cookie belongs to
     *    somebody else and the create or cancel goes out from that account. Nothing else covers this
     *    (see [SteamCredentialProvider.sessionBelongsTo]).
     *
     * Axis 2 is checked here, immediately before the write, rather than only where the credential is
     * acquired: [SteamCredentialProvider] already refuses to hand out a credential whose account the
     * cookie has moved away from, which is what converges the whole client (and the host's
     * [TrackerBlock.STEAM_ACCOUNT_MISMATCH] prompt) onto the truth — but a directive-path write happens
     * later in the same cycle than that acquisition, and an account switch inside that window would
     * otherwise be written before the next cycle noticed. One cookie read, no network.
     *
     * Both axes fail **open** on an unknown answer (an absent [expected], an unreadable cookie), so a
     * missing backend field or a cookie-store hiccup never blocks a legitimate write.
     */
    private suspend fun accountAllowsWrite(expected: SteamId?, token: SteamId, kind: String): Boolean {
        if (AccountBinding.evaluate(expected, token) == AccountBindingStatus.MISMATCH) {
            expected?.let { emit(LifecycleEvent.LinkedSteamIdMismatch(it.value, token.value)) }
            return false
        }
        if (!credentials.sessionBelongsTo(token)) {
            emit(LifecycleEvent.SteamSessionAccountMismatch(kind, token.value))
            return false
        }
        return true
    }

    /**
     * The buyer-role write guard shared by all three write entry points ([createTrade], [runCreate],
     * [runCancel]): `true` when [DealRoleBinding] allows the write, otherwise it reports the refusal and
     * returns `false`.
     *
     * The refusal is **reported** (FAILED) rather than silently dropped, on the wrong-account guard's
     * reasoning: an unanswered directive keeps the backend's lease until its TTL and is then re-leased
     * forever, so the deal stalls invisibly on both sides. It is **not** marked handled — nothing reached
     * Steam, so a re-lease is simply re-refused, and a corrected `role` on a later heartbeat lets the same
     * write proceed. [LifecycleEvent.DirectiveDropped] carries the why (the counterpart of
     * [LifecycleEvent.LinkedSteamIdMismatch] on the account axis).
     */
    private suspend fun roleAllowsWrite(activeTracking: List<TrackedDeal>?, directive: Directive): Boolean {
        if (DealRoleBinding.allowsWrite(activeTracking, directive.dealId)) return true
        reportOutcome(directive.outcome(DirectiveStatus.FAILED, error = BUYER_ROLE_WRITE_ERROR))
        emit(LifecycleEvent.DirectiveDropped(directive.action.wireName, directive.directiveId.value, BUYER_ROLE_WRITE_ERROR))
        emit(LifecycleEvent.DirectiveExecuted(directive.action.wireName, DirectiveStatus.FAILED.name))
        return false
    }

    /**
     * The create-surface back-pressure gate, in front of **both** create entry points ([createTrade] and the
     * leased [runCreate]). Returns `null` when the write may proceed, or the [CreateOfferResult.Throttled] to
     * answer with — Steam already refused a create for this partner (or the whole surface) recently, and
     * pushing again before the cooldown elapses is what turns a per-partner quota refusal into Steam
     * refusing the surface outright.
     *
     * The host fast path needs this as much as the directive path does: it runs outside [cycleMutex] with a
     * directive_id the caller happens to hold, so without it an FE relay could walk straight past a standing
     * cooldown. Nothing is reported — no write was attempted, so there is no outcome (see
     * [emitDeferredCreate]).
     */
    private suspend fun throttledCreate(partner: SteamId): CreateOfferResult.Throttled? {
        val now = clock.now()
        val gate = throttle.gate(partner, now)
        if (gate !is WriteGate.Blocked) return null
        return CreateOfferResult.Throttled(gate.scope, SteamWriteThrottle.retryAfterSeconds(gate.until, now))
    }

    /**
     * Folds one finished create for [partner] into the throttle: a real write clears their cooldown, a
     * [CreateOfferResult.Failed] is classified from Steam's own error text and may open one.
     *
     * The results that never reached Steam ([CreateOfferResult.AccountMismatch] and the two duplicate
     * verdicts) are ignored on purpose: they say nothing about Steam's willingness to accept a create, and
     * treating them as failures would park a partner over a client-side guard.
     */
    private suspend fun recordCreateResult(partner: SteamId, result: CreateOfferResult) {
        val kind = when (result) {
            is CreateOfferResult.NeedsConfirmation, is CreateOfferResult.Created -> null
            // Re-derived from the text rather than read off `result.cause`, deliberately. The two agree by
            // construction ([SteamCreateFailureCause.kind]), and the cost is one substring scan on a failure
            // path — but a future write site that forgot [diagnosed] would otherwise hand the throttle a
            // default OTHER and silently stop parking anything, which is how an account earns a trade block.
            // Reading it here instead means such a slip costs the host a cause code, not the back-pressure.
            is CreateOfferResult.Failed -> SteamWriteThrottle.classify(result.error, config.tunables.steamWrites)
            else -> return
        }
        throttle.onResult(partner, kind, clock.now())
    }

    /**
     * Attaches the [SteamCreateFailureCause] to a finished create, so Steam's free-form refusal text is read
     * **once** — here, against the host-suppliable markers this loop holds — and travels onwards as an enum.
     *
     * Without it every consumer re-parses that string: the JS facade's outcome JSON, the extension's page
     * bridge, a mobile UI. Each would drift from the others and from the throttle, and each would have to
     * inspect text that may carry urls, ids or the counterparty's persona. Non-failures pass through.
     */
    private fun CreateOfferResult.diagnosed(): CreateOfferResult = when (this) {
        is CreateOfferResult.Failed -> copy(cause = SteamWriteThrottle.classifyCause(error, config.tunables.steamWrites))
        else -> this
    }

    /**
     * Says out loud that a leased `create_offer` was not attempted, and why.
     *
     * TODO(backend): agree the reporting semantics for a deferred / chain-skipped create. Nothing was written
     *  to Steam, so there is no outcome to report and the directive lease is left to expire and be re-leased.
     *  Reporting `failed` on every heartbeat instead would mean one `/trade-actions` POST per deferred
     *  directive per heartbeat — dozens, for exactly the workload that makes deferring necessary. Flip
     *  [SteamWriteConfig.reportThrottledWrites] once agreed.
     */
    private suspend fun emitDeferredCreate(directive: Directive, reason: String, retryAfterSeconds: Int?) {
        emitDeferred(directive.directiveId, directive.dealId, reason, retryAfterSeconds)
        if (!config.tunables.steamWrites.reportThrottledWrites) return
        reportOutcome(directive.outcome(DirectiveStatus.FAILED, error = "$THROTTLED_WRITE_ERROR: $reason"))
    }

    /**
     * The bare "this create was not attempted" event. Split from [emitDeferredCreate] because the host fast
     * path has no leased [Directive] to report an outcome for — it synthesises one and answers its caller with
     * [CreateOfferResult.Throttled] instead — so it needs the event without the reporting side effect.
     */
    private suspend fun emitDeferred(directiveId: DirectiveId, dealId: DealId?, reason: String, retryAfterSeconds: Int?) {
        emit(
            LifecycleEvent.SteamWriteDeferred(
                kind = DirectiveAction.CREATE_OFFER.wireName,
                directiveId = directiveId.value,
                reason = reason,
                dealId = dealId?.value,
                retryAfterSeconds = retryAfterSeconds,
            ),
        )
    }

    // Scheduling state below (lastRunAt / nextHeartbeatAt / lastActiveTracking / expeditedUntil) is
    // mutated inside cycleMutex by runOnce, but also touched by the loop's own entry points
    // (createTrade → armExpedited, forceHeartbeatNow, deliverPush → nextWakeDelay). This is safe under
    // the library's contract that a single driver thread advances the loop — true on the JS worker (the
    // only live target). A future multithreaded (mobile) driver must serialize these entry points
    // (guard them with cycleMutex or an atomic) before relying on this state across threads.

    /** When [start]'s loop last completed a cycle — the reference point for push floor-honouring. */
    private var lastRunAt: Instant? = null

    /** In-memory cache of when the next heartbeat is due, mirrored to [loopState] for respawn survival. */
    private var nextHeartbeatAt: Instant? = null

    /**
     * The last heartbeat's `active_tracking[]`, so between-heartbeat wakes (the heartbeat runs on its
     * own `ttl_seconds` cadence) can still watch Steam. In-memory only, deliberately not persisted:
     * the backend orchestrates what to monitor, so a fresh instance never watches from stale local
     * state — it idles until the due tick re-fetches the list (see [CyclePolicy]).
     */
    private var lastActiveTracking: List<TrackedDeal>? = null

    /**
     * While set to a future instant, the deal-watch runs at the [PollClass.ExpeditedOffer] cadence. Armed
     * the moment an offer is created (`CreatedNeedsConfirmation`) and re-armed on every tick a watched
     * deal is still in a transient (state-9) offer state; it lapses once no such deal is seen for
     * [CadencePolicy.expeditedWindow], returning the loop to the baseline cadence.
     */
    private var expeditedUntil: Instant? = null

    /** True while the expedited-poll window is open. */
    private fun isExpedited(): Boolean = expeditedUntil?.let { clock.now() < it } ?: false

    /**
     * Open (or extend) the expedited-poll window so upcoming wakes fast-poll the transient deal.
     * Written through to [loopState] (best-effort) so the window survives an MV3 worker respawn.
     */
    private suspend fun armExpedited() {
        val until = clock.now() + cadence.expeditedWindow
        expeditedUntil = until
        runCatching { loopState.setExpeditedUntil(until) }
    }

    /** One-shot restore of the respawn-surviving schedule state on a fresh loop instance. */
    private var loopStateRestored = false

    /**
     * Restores [expeditedUntil], [nextHeartbeatAt], [consecutiveServerErrors],
     * [steamSessionMissingSticky], [steamMintAttemptedSticky], [mismatchTokenSteamId] and
     * [steamMismatchRecheckedSticky] from [loopState] after an MV3 worker
     * respawn (all of them are in-memory and die with the worker). Expired persisted values are ignored; the restored
     * heartbeat-due time keeps the backend `ttl_seconds` cadence (already platform-clamped when it was
     * written) instead of resetting it on every respawn — deliberately also while the Steam-session
     * block is set, so a logged-out user is not polled every wake for the whole outage; the host's
     * session-cookie watch (and, at the latest, the next due tick) is what re-checks it.
     *
     * The restored error streak deliberately does NOT re-derive [marketplaceServerErrorSticky]: the
     * next failing heartbeat re-crosses the threshold and re-emits the entry event (which a respawned
     * event-mirroring host needs anyway — its mirrored state died with the worker too), while a
     * successful one clears everything.
     */
    private suspend fun restoreLoopStateOnce() {
        if (loopStateRestored) return
        loopStateRestored = true
        val now = clock.now()
        runCatching { loopState.expeditedUntil() }.getOrNull()?.let { persisted ->
            if (persisted > now && (expeditedUntil == null || persisted > expeditedUntil!!)) expeditedUntil = persisted
        }
        runCatching { loopState.nextHeartbeatAt() }.getOrNull()?.let { persisted ->
            if (nextHeartbeatAt == null && persisted > now) nextHeartbeatAt = persisted
        }
        runCatching { loopState.serverErrorCount() }.getOrNull()?.let { persisted ->
            if (persisted > consecutiveServerErrors) consecutiveServerErrors = persisted
        }
        // The Steam-session block, unlike the streak above, is the state a host renders — restore it so a
        // respawn (incl. one that goes on to idle without re-checking the session) reports the truth. Only
        // a `true` is adopted: a fresh instance starts unblocked, and the first cycle that acquires a
        // credential clears both memory and store.
        if (runCatching { loopState.steamSessionMissing() }.getOrDefault(false)) steamSessionMissingSticky = true
        if (runCatching { loopState.steamMintAttempted() }.getOrDefault(false)) steamMintAttemptedSticky = true
        // The wrong-account block, for the same reason and with the same "only adopt a set value" rule: it
        // is a state the host renders, and this restore runs BEFORE the first emit precisely so a mirroring
        // host is never handed the fresh instance's all-clear over a correct persisted prompt.
        if (mismatchTokenSteamId == null) {
            runCatching { loopState.steamMismatchTokenId() }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { mismatchTokenSteamId = SteamId(it) }
        }
        if (runCatching { loopState.steamMismatchRechecked() }.getOrDefault(false)) steamMismatchRecheckedSticky = true
    }

    /** Serializes cycles so a scheduled wake and a delivered push never run [runOnce] concurrently. */
    private val cycleMutex = Mutex()

    /**
     * `true` if the last background Steam credential refresh failed because no authenticated session
     * was available. The host shows Steam login UI; the flag clears once the next scrape succeeds.
     */
    val needsReLogin: Boolean get() = credentials.lastRefreshFailedLoggedOut

    /**
     * `true` if the last DMarket marketplace-credential refresh failed because no authenticated
     * `dmarket.com` session was available. Always `false` when no [MarketplaceCredentialProvider] was
     * wired in (e.g. non-web hosts).
     */
    val needsMarketplaceReLogin: Boolean get() = marketplaceCredentials?.lastRefreshFailedLoggedOut ?: false

    /**
     * Backing field for [marketplaceConnectionMissing]: set when a `/heartbeat` fails with
     * [MarketplaceUnauthorizedException] (the DMarket token is invalid and couldn't be refreshed),
     * cleared on the next successful heartbeat. Mutated only inside [runOnce] under [cycleMutex].
     */
    private var marketplaceConnectionMissingSticky = false

    /**
     * Whether the current missing-connection episode has already announced itself with
     * `ReLoginNeeded("marketplace")`. Its own flag rather than a reading of
     * [marketplaceConnectionMissingSticky], because that sticky is now re-derived at the top of every
     * cycle — cleared as soon as a DMarket credential is in hand, so the top-ranked prompt can never be
     * shown from a stale value while a lower-ranked block does the actual blocking (see [blockingState]).
     * Gating the entry event on the sticky alone would therefore re-emit it on every cycle of a
     * persistent 401. Cleared only by a heartbeat that reaches DMarket, which is the proof the episode is
     * over.
     */
    private var marketplaceReLoginAnnounced = false

    /**
     * `true` when there is no usable DMarket connection — the marketplace bearer token is absent, or it
     * is invalid and could not be refreshed. Folds the reactive heartbeat-401 signal
     * ([marketplaceConnectionMissingSticky]) together with the provider's logged-out flag
     * ([needsMarketplaceReLogin]), so it fully subsumes the latter. Nothing this client does works
     * without a DMarket session, so this blocks the whole cycle and is the **highest-priority** block
     * (see [blockingState]) — and, correspondingly, the first thing every non-idle cycle establishes.
     * Recomputed continuously; not persisted.
     */
    val marketplaceConnectionMissing: Boolean
        get() = marketplaceConnectionMissingSticky || needsMarketplaceReLogin

    /**
     * Backing field for [marketplaceServerError]: set when a `/heartbeat` fails with a non-401
     * [MarketplaceServerErrorException] (DMarket returned a 4xx/5xx — the session is fine, the backend
     * isn't) or with a status-less network failure (fetch rejection, debounced the same way as a 5xx);
     * cleared on the next successful heartbeat. Mutated only inside [runOnce] under [cycleMutex].
     */
    private var marketplaceServerErrorSticky = false

    /**
     * Consecutive transient (5xx) heartbeat failures, for the [SERVER_ERROR_THRESHOLD] debounce.
     * Written through to [loopState] (best-effort) so a persistent outage still crosses the threshold
     * when the MV3 worker dies between retries — a fresh instance restarting the streak at 0 on every
     * respawn would otherwise never surface it. The streak only clears on a round-tripped heartbeat,
     * so "blip → long sleep → blip" can surface a false DM_CONNECTION_ERROR; it self-heals on the next
     * successful heartbeat, which is far cheaper than a real outage staying invisible.
     */
    private var consecutiveServerErrors = 0

    /** Resets the server-error streak (memory + store), skipping the storage write when already clear. */
    private suspend fun clearServerErrorStreak() {
        if (consecutiveServerErrors == 0) return
        consecutiveServerErrors = 0
        runCatching { loopState.setServerErrorCount(0) }
    }

    /**
     * `true` when the DMarket backend is erroring — the `/heartbeat` reached the gateway but returned a
     * non-401 status (a deterministic 4xx, or 5xx that has failed [SERVER_ERROR_THRESHOLD] times), or
     * could not be reached at all (status-less fetch failure, debounced the same way) — so the
     * cycle can't complete. Distinct from [marketplaceConnectionMissing] (a login/token problem), and the
     * **lowest-priority** block: it is the only one the user cannot act on, so every sign-in prompt —
     * including a [linkedSteamIdMismatch], which has a heartbeat-free clear site — outranks it (see
     * [blockingState]). Recomputed continuously; not persisted itself — the underlying
     * [consecutiveServerErrors] streak is, so it re-enters after a worker respawn mid-outage.
     */
    val marketplaceServerError: Boolean get() = marketplaceServerErrorSticky

    /**
     * Backing field for [steamSessionMissing]: set at the Steam-credential gate in [runOnce] when the
     * provider reports corroborated proof that there is no Steam web session
     * ([SteamCredentialProvider.sessionMissing]), cleared the moment a credential is acquired again.
     * Mutated only inside [runOnce] under [cycleMutex].
     *
     * Written through to [loopState] (best-effort), because unlike every other blocking input this one
     * is decided *before* the heartbeat: a Steam logout happens asynchronously, typically inside a live
     * `ttl_seconds` window whose due-time was already advanced by a successful heartbeat, so a
     * respawned worker can legitimately decide [CycleAction.IDLE] and never re-check the session (see
     * [CyclePolicy]). An in-memory-only flag would therefore read as "nothing is blocking" on the first
     * wake of every respawn — and MV3 respawns constantly.
     */
    private var steamSessionMissingSticky = false

    /**
     * `true` when there is no authenticated Steam web session, so no Steam credential can be acquired
     * and the cycle stops at the credential gate before the heartbeat. Outranked only by
     * [marketplaceConnectionMissing], which the cycle establishes just above the gate (see
     * [blockingState]); while it lasts, every input below it is frozen at its last value.
     *
     * Deliberately NOT [needsReLogin]: that flag also turns true for a Steam rate-limit / 5xx / HTML
     * drift (any of which make the scrape return `null`), which must not raise a user-facing
     * "sign into Steam" prompt. See [SteamCredentialProvider.sessionMissing].
     */
    val steamSessionMissing: Boolean get() = steamSessionMissingSticky

    /** Records a resolved Steam-session verdict in memory + store, skipping an unchanged write. */
    private suspend fun setSteamSessionMissing(missing: Boolean) {
        if (steamSessionMissingSticky == missing) return
        steamSessionMissingSticky = missing
        runCatching { loopState.setSteamSessionMissing(missing) }
    }

    /**
     * Whether Steam has already been asked to mint a new session during the current missing-session
     * episode. Deliberately its OWN flag rather than a reading of [steamSessionMissingSticky]: gating the
     * attempt on "we have not recorded the block yet" means it can only ever fire on the exact cycle that
     * first notices, so a client that was already blocked — after a respawn, or an upgrade that introduced
     * the mint — would never attempt one at all. Persisted so one attempt stays one attempt.
     */
    private var steamMintAttemptedSticky = false

    /** Records a mint attempt in memory + store, skipping an unchanged write. */
    private suspend fun setSteamMintAttempted(attempted: Boolean) {
        if (steamMintAttemptedSticky == attempted) return
        steamMintAttemptedSticky = attempted
        runCatching { loopState.setSteamMintAttempted(attempted) }
    }

    /**
     * Backing field for [linkedSteamIdMismatch]: the Steam id of the **token** the last heartbeat found
     * bound to a different DMarket account, or `null` when the accounts agree. Mutated only inside
     * [runOnce] under [cycleMutex].
     *
     * The id rather than a boolean, for two reasons that a boolean cannot express:
     * - **It is falsifiable without a heartbeat.** A credential whose subject differs from it proves the
     *   account changed, so the block can be released the moment one is acquired (see
     *   [clearMismatchOnIdentityChange]) instead of waiting for a heartbeat round trip that may not come.
     * - **It bounds the one re-acquisition** the loop is allowed per wrong-account episode: the block
     *   naming the very credential we hold is exactly the state in which that credential is the suspect
     *   half (see [recheckMismatchedCredential]).
     *
     * Written through to [loopState] for the same reason as [steamSessionMissingSticky]: the host mirrors
     * [blockingState] from inside a synchronous lifecycle event, and [LifecycleEvent.CycleStarted] is
     * emitted *before* anything in the cycle could re-derive this — so an in-memory-only value would
     * publish "nothing is blocking" over a correct persisted prompt on the first wake of every respawn.
     */
    private var mismatchTokenSteamId: SteamId? = null

    /** Records a resolved wrong-account verdict in memory + store, skipping an unchanged write. */
    private suspend fun setMismatchTokenSteamId(tokenSteamId: SteamId?) {
        if (mismatchTokenSteamId == tokenSteamId) return
        mismatchTokenSteamId = tokenSteamId
        runCatching { loopState.setSteamMismatchTokenId(tokenSteamId?.value) }
        // A cleared verdict ends the episode, so the re-check it was latching is available again.
        if (tokenSteamId == null) setSteamMismatchRechecked(false)
    }

    /**
     * Whether the credential named by [mismatchTokenSteamId] has already been re-acquired from Steam
     * during the current wrong-account episode.
     *
     * Its own flag rather than a reading of the verdict, by the same argument as [steamMintAttemptedSticky]:
     * the verdict names the credential we hold on *every* wake of a truthful mismatch, so gating on it
     * alone would re-scrape Steam once a minute for as long as the user stays signed into the other
     * account — the shape of a bug that has already been fixed once here. Persisted so one re-acquisition
     * stays one, and cleared by [forceHeartbeatNow] so the host's session-cookie watch (a login, a logout,
     * an account switch) and the debug force-tick are both real retries.
     */
    private var steamMismatchRecheckedSticky = false

    /** Records a wrong-account re-check in memory + store, skipping an unchanged write. */
    private suspend fun setSteamMismatchRechecked(rechecked: Boolean) {
        if (steamMismatchRecheckedSticky == rechecked) return
        steamMismatchRecheckedSticky = rechecked
        runCatching { loopState.setSteamMismatchRechecked(rechecked) }
    }

    /**
     * The `linkedSteamId` the last heartbeat reported, kept so the host-facing [createTrade] fast path can
     * fall back to it when the caller omits its own argument — the guard there must not weaken just
     * because a host passed nothing. `null` until the first heartbeat, or when the backend supplies no
     * binding (treated as unknown, never a mismatch — see [AccountBinding]).
     */
    private var lastLinkedSteamId: SteamId? = null

    /**
     * `true` while the Steam id the backend linked to this DMarket account disagrees with the Steam id of
     * the token the client holds — a wrong-account session. The host shows a "log into the correct Steam
     * account" prompt; **all Steam requests (reads and writes) are blocked** until the accounts agree.
     *
     * Recomputed on every heartbeat, released early by [clearMismatchOnIdentityChange] the moment a
     * credential for a different account is acquired, and persisted across worker respawns (see
     * [mismatchTokenSteamId]).
     */
    val linkedSteamIdMismatch: Boolean get() = mismatchTokenSteamId != null

    /**
     * The single highest-priority reason the tracker is blocked, for the host to render one prompt.
     * Precedence: [TrackerBlock.DM_SESSION_MISSING] > [TrackerBlock.STEAM_SESSION_MISSING] >
     * [TrackerBlock.STEAM_ACCOUNT_MISMATCH] > [TrackerBlock.DM_CONNECTION_ERROR] — most actionable and most
     * upstream first (sign into DMarket → sign into Steam → switch Steam account → the one state the user
     * cannot fix). [runOnce] establishes the inputs in exactly that order, which is what stops a
     * higher-ranked prompt from being served from a frozen value while a lower-ranked block short-circuits
     * the cycle; see [BlockingState.resolve] for the two deliberate exceptions. Derived purely from
     * [marketplaceConnectionMissing] + [steamSessionMissing] + [linkedSteamIdMismatch] +
     * [marketplaceServerError] via [BlockingState.resolve]; the underlying flags are left untouched.
     */
    val blockingState: TrackerBlock
        get() = BlockingState.resolve(
            marketplaceConnectionMissing,
            marketplaceServerError,
            linkedSteamIdMismatch,
            steamSessionMissing,
        )

    /**
     * Runs one full cycle: heartbeat → execute directives → watch + report. Returns a [TickOutcome]
     * summary ([TickOutcome.EMPTY] when idle or logged out).
     */
    suspend fun runOnce(): TickOutcome = cycleMutex.withLock {
        // Restore BEFORE the first emit: a host mirrors [blockingState] synchronously from inside the
        // event handler, so on a fresh (respawned) instance emitting first would hand it the
        // pre-restore, all-clear state — overwriting a correct persisted prompt with "nothing is
        // blocking" until something later in this cycle happened to emit again.
        restoreLoopStateOnce()
        emit(LifecycleEvent.CycleStarted)
        // What this cycle does — full heartbeat, watch-only, or idle — is [CyclePolicy]'s call (the
        // heartbeat runs on its own backend-`ttl_seconds` cadence, not on every deal-watch wake; see
        // the policy's KDoc). Decided BEFORE any credential work so an idle wake stays fully quiet:
        // a worker respawn with nothing due and nothing to watch triggers no Steam session refresh
        // and no marketplace traffic. Directives only arrive with a heartbeat. A between-heartbeat
        // DMarket logout is surfaced by the host's cookie watch (a forced heartbeat marks the
        // schedule due) or, at the latest, by the next due heartbeat.
        val cached = lastActiveTracking
        val heartbeatDue = nextHeartbeatAt?.let { clock.now() >= it } ?: true
        val action = CyclePolicy.decide(heartbeatDue, hasTrackingList = cached != null)
        if (action == CycleAction.IDLE) return@withLock TickOutcome.EMPTY
        // Proactive missing-connection guard (web), and the FIRST thing a non-idle cycle establishes: if
        // the DMarket provider already knows there is no session (no token, or one that could not be
        // refreshed), don't fire an unauthenticated `/heartbeat` that the gateway would only 401 — set the
        // missing-connection state and bail. `current()` is the same call the heartbeat would make anyway
        // (via the authenticator), so it adds no network on the happy path; on a fresh cached token it
        // returns immediately, and on a signed-out one the provider's own latch keeps it network-free.
        // Skipped when no provider is wired (mobile hosts authenticate at the transport layer). The
        // reactive MarketplaceUnauthorizedException path below stays the backstop for a token that is
        // present but rejected. Because the heartbeat is retried on every due cycle (nextHeartbeatAt is
        // never advanced on this early return), the loop self-heals: the next cycle after the user logs
        // back into DMarket sees a token and heartbeats normally.
        //
        // Ahead of the Steam credential gate ON PURPOSE — this is what makes DM_SESSION_MISSING's top
        // precedence honest (see [BlockingState]): whichever state the cycle establishes first is the one
        // that can short-circuit the others, so the ranking and the evaluation order have to agree, or the
        // top-ranked prompt would be shown from a frozen value while a lower-ranked block did the actual
        // blocking. It also means a between-heartbeat DMarket logout now surfaces on a watch-only wake
        // instead of waiting for the next due heartbeat.
        if (marketplaceCredentials != null) {
            if (marketplaceCredentials.current() == null) {
                marketplaceConnectionMissingSticky = true
                if (!marketplaceReLoginAnnounced) {
                    marketplaceReLoginAnnounced = true
                    emit(LifecycleEvent.ReLoginNeeded("marketplace"))
                }
                return@withLock TickOutcome.EMPTY
            }
            // A usable DMarket credential is in hand, so drop the reactive-401 memory NOW rather than
            // waiting for the heartbeat to clear it: the cycle can still be short-circuited below (no
            // Steam session, a wrong account), and a stale sticky would keep the top-ranked "sign into
            // DMarket" prompt on screen for the whole of a block the user cannot fix that way. The
            // knowledge is not lost — this cycle's own heartbeat re-derives it a few lines down, and the
            // provider's own logged-out latch ([needsMarketplaceReLogin]) is folded in independently.
            // Announcing is latched separately so re-deriving the state per cycle can't re-emit the event.
            marketplaceConnectionMissingSticky = false
        }
        var acquired = credentials.current()
        // First cycle of an episode that finds the session gone: ask Steam to mint a new one from the
        // durable "remember me" credential the platform still holds. That is the only way back — a session
        // past its expiry cannot be renewed — and it is why the user's own "log in" click on Steam completes
        // instantly with no password: the same handshake, run by Steam's page.
        //
        // Exactly ONE attempt per episode, gated on the persisted flag still being clear: a refused mint
        // must never become per-wake traffic (that was the shape of an earlier bug). A refusal therefore
        // falls through to the host prompt, and the next attempt only comes after a session has existed
        // again — which is the only thing that can change the answer.
        //
        // NOT attempted while the wrong-account block is set: the durable credential the mint redeems is
        // whichever account the browser last remembered, so on this axis a mint is as likely to hand back
        // the account the user is trying to leave as the linked one — and it would do so seconds after the
        // "Switch account" logout that the host's cookie watch turns into this very cycle. The linked id is
        // passed so a session minted for somebody else is not mistaken for recovery either.
        if (acquired == null && credentials.sessionMissing && !steamMintAttemptedSticky && !linkedSteamIdMismatch) {
            setSteamMintAttempted(true)
            if (credentials.mintSession(lastLinkedSteamId)) acquired = credentials.current()
        }
        acquired ?: run {
            // Steam-session guard. The sticky is set (and persisted) BEFORE the event is emitted, per the
            // same discipline as the marketplace paths below: delivery is synchronous and a host reads
            // blockingState from inside the handler. Only the provider's corroborated verdict raises the
            // block — a bare failed scrape (Steam 429/5xx, HTML/regex drift) stays the signal-only
            // ReLoginNeeded hint it has always been, so a Steam hiccup never tells a logged-in user to
            // sign in. Unlike the marketplace emits this one is deliberately NOT entry-gated: this path
            // emits nothing else, so repeating it every blocked cycle is what lets an event-mirroring
            // host re-converge (its read-compare-write mirror suppresses the churn). No schedule is
            // advanced here (that happens only after a successful heartbeat), so every due wake retries.
            setSteamSessionMissing(credentials.sessionMissing)
            if (credentials.lastRefreshFailedLoggedOut) emit(LifecycleEvent.ReLoginNeeded("steam"))
            return@withLock TickOutcome.EMPTY
        }
        // A credential in hand proves the Steam session is live — clear the block before anything else can
        // emit, so a host reading blockingState inside a later event in this cycle sees the cleared value.
        // The episode is over, so the mint latch clears with it: the next one gets its own attempt.
        setSteamSessionMissing(false)
        setSteamMintAttempted(false)
        // Wrong-account recovery, deliberately BEFORE the heartbeat that would otherwise re-derive the same
        // verdict from the same suspect credential — and before the write paths below can act on it.
        val credential = recheckMismatchedCredential(acquired)
        clearMismatchOnIdentityChange(credential)
        if (action == CycleAction.WATCH_ONLY) {
            // Skip the deal-watch reads while blocked and wait for the next *due* heartbeat to re-evaluate
            // (don't force one now): a wrong-account session must not touch Steam, and a missing/erroring
            // DMarket connection means the trade-status reports have nowhere to land anyway.
            if (marketplaceConnectionMissing || marketplaceServerError || linkedSteamIdMismatch) {
                return@withLock TickOutcome.EMPTY
            }
            val tracking = checkNotNull(cached) // WATCH_ONLY is only decided on a non-null list
            val (reportsSent, proofsSubmitted) = watchAndReport(tracking, credential)
            val outcome = TickOutcome(reportsSent = reportsSent, proofsSubmitted = proofsSubmitted, watching = tracking.size)
            emit(LifecycleEvent.CycleCompleted(0, reportsSent, proofsSubmitted, outcome.watching))
            return@withLock outcome
        }
        // action == HEARTBEAT: the full cycle below.
        // Heartbeat-failure guards, in priority order:
        //  - MarketplaceUnauthorizedException (401, token absent/un-refreshable) → missing-connection.
        //  - MarketplaceServerErrorException (non-401 4xx/5xx: DMarket reached but erroring) →
        //    connection-error. The token is fine, so this is NOT missing-connection; it surfaces as a
        //    distinct "can't reach DMarket" state so the host never claims tracking is live while the
        //    heartbeat is failing. A deterministic 4xx surfaces immediately; a transient 5xx must fail
        //    SERVER_ERROR_THRESHOLD cycles in a row first (no error prompt for a single blip).
        //  - RateLimitedException (429, explicit backend backpressure) → idle tick, state untouched.
        //  - Any other Throwable (a status-less failure: fetch rejection — network down, DNS, CORS /
        //    missing host permission — or a decode error) → the heartbeat did not round-trip, so
        //    tracking is NOT live: surfaces as connection-error under the same SERVER_ERROR_THRESHOLD
        //    debounce as a transient 5xx, with statusCode 0 marking the status-less failure. Unlike
        //    the server-error catch it leaves the missing-connection sticky untouched — no HTTP reply
        //    proves nothing about the session.
        // Both sticky states emit their lifecycle event only on ENTRY (avoids per-cycle log spam) and
        // are cleared by the next successful heartbeat below. The sticky is always set BEFORE its event
        // is emitted: delivery is synchronous (a JS host reads blockingState from inside the handler —
        // see CallbackEventObserver), so emitting first would expose the pre-transition state and the
        // host would mirror a stale NONE it is never poked to correct (entry-only events don't repeat).
        val heartbeat = try {
            sendHeartbeat(credential)
        } catch (_: MarketplaceUnauthorizedException) {
            marketplaceConnectionMissingSticky = true
            clearServerErrorStreak()
            if (!marketplaceReLoginAnnounced) {
                marketplaceReLoginAnnounced = true
                emit(LifecycleEvent.ReLoginNeeded("marketplace"))
            }
            return@withLock TickOutcome.EMPTY
        } catch (e: MarketplaceServerErrorException) {
            // A non-401 HTTP reply came back WITH our token, so the DMarket connection is live — this is a
            // server-side error, NOT a logout. Clear any stale missing-connection state (e.g. left over
            // from a prior logout): otherwise it would outrank DM_CONNECTION_ERROR (see [blockingState]) and,
            // on an endpoint that always errors (e.g. a not-yet-deployed route returning 404), never clear
            // — because the successful heartbeat that clears it never comes. A deterministic 4xx surfaces
            // immediately; a transient 5xx must fail SERVER_ERROR_THRESHOLD cycles first.
            val before = blockingState
            marketplaceConnectionMissingSticky = false
            marketplaceReLoginAnnounced = false
            consecutiveServerErrors += 1
            runCatching { loopState.setServerErrorCount(consecutiveServerErrors) }
            val transient = e.statusCode in 500..599
            if (!transient || consecutiveServerErrors >= SERVER_ERROR_THRESHOLD) marketplaceServerErrorSticky = true
            // Gate the entry event on the RESOLVED-state transition, not the sticky flip alone: the
            // missing-connection clear above can re-expose an already-set server-error sticky (a re-login
            // while the endpoint keeps erroring — the 401/no-token paths never clear it), and a
            // sticky-gated emit would stay silent, leaving an event-mirroring host stuck showing
            // DM_SESSION_MISSING. Still entry-only while DM_CONNECTION_ERROR itself persists.
            if (marketplaceServerErrorSticky && blockingState != before) {
                emit(LifecycleEvent.MarketplaceServerError("heartbeat", e.statusCode))
            }
            return@withLock TickOutcome.EMPTY
        } catch (_: RateLimitedException) {
            return@withLock TickOutcome.EMPTY
        } catch (_: CancellationException) {
            // Teardown, not a network failure: swallow without touching the error counter (pre-existing
            // behavior for a cancelled cycle), so a Tracker.stop mid-heartbeat can't flash an error.
            return@withLock TickOutcome.EMPTY
        } catch (_: Throwable) {
            val before = blockingState
            consecutiveServerErrors += 1
            // Persist the streak like the server-error catch above: a network outage is exactly when
            // nothing keeps the MV3 worker alive between retries, so an in-memory-only counter would
            // restart at 0 on every respawn and the threshold would never be crossed.
            runCatching { loopState.setServerErrorCount(consecutiveServerErrors) }
            if (consecutiveServerErrors >= SERVER_ERROR_THRESHOLD) marketplaceServerErrorSticky = true
            if (marketplaceServerErrorSticky && blockingState != before) {
                emit(LifecycleEvent.MarketplaceServerError("heartbeat", 0))
            }
            return@withLock TickOutcome.EMPTY
        }
        // The heartbeat round-tripped, so the DMarket connection is live — clear the error states. The
        // episode is over, so the next logout announces itself again.
        marketplaceConnectionMissingSticky = false
        marketplaceReLoginAnnounced = false
        marketplaceServerErrorSticky = false
        clearServerErrorStreak()

        // Wrong-account guard: the backend reports which Steam id it linked to this DMarket account. If it
        // disagrees with the token we hold, this session would act on the wrong Steam account — block ALL
        // Steam activity (directives + deal-watch reads) until a later heartbeat sees the ids agree. The
        // heartbeat itself keeps running (that is how the mismatch, and its resolution, are detected).
        // Evaluated BEFORE HeartbeatSent is emitted (same set-state-before-emit discipline as the failure
        // paths above) so a host reading blockingState inside that handler sees THIS heartbeat's verdict,
        // not the previous one. Evaluated BEFORE the schedule advances too: like the failure paths, a
        // blocked heartbeat must never advance the persisted due-time. Leaving the schedule due means every
        // wake re-heartbeats and re-evaluates the binding, so the mismatch clears fast once the right
        // account is back — and the verdict is persisted (see [mismatchTokenSteamId]) so a respawn between
        // wakes reports it instead of the fresh instance's all-clear.
        val linkedSteamId = heartbeat.linkedSteamId
        lastLinkedSteamId = linkedSteamId
        val mismatched = AccountBinding.evaluate(linkedSteamId, credential.subjectSteamId) == AccountBindingStatus.MISMATCH
        setMismatchTokenSteamId(if (mismatched) credential.subjectSteamId else null)
        if (!mismatched) scheduleNextHeartbeat(heartbeat.ttlSeconds)
        lastActiveTracking = heartbeat.activeTracking
        releaseStaleClaims(heartbeat)
        emit(LifecycleEvent.HeartbeatSent(heartbeat.ttlSeconds, heartbeat.activeTracking.size, heartbeat.directives.size))
        if (mismatched) {
            // MISMATCH implies linkedSteamId != null (AccountBinding maps a null expected id to UNKNOWN).
            linkedSteamId?.let { emit(LifecycleEvent.LinkedSteamIdMismatch(it.value, credential.subjectSteamId.value)) }
            return@withLock TickOutcome.EMPTY
        }

        val directivesExecuted = if (directivesEnabled) executeDirectives(heartbeat, credential) else announceDirectiveGate(heartbeat)
        val (reportsSent, proofsSubmitted) = watchAndReport(heartbeat.activeTracking, credential)
        val outcome = TickOutcome(
            directivesExecuted = directivesExecuted,
            reportsSent = reportsSent,
            proofsSubmitted = proofsSubmitted,
            watching = heartbeat.activeTracking.size,
        )
        emit(LifecycleEvent.CycleCompleted(directivesExecuted, reportsSent, proofsSubmitted, outcome.watching))
        outcome
    }

    /**
     * The wrong-account recovery step, run once per episode on the cycle's freshly acquired credential.
     *
     * A `MISMATCH` says the backend's `linkedSteamId` and the token we hold name different accounts. Two
     * things can produce that, and they need opposite responses: the browser really is signed into another
     * account (block, and keep blocking), or the token is simply **stale** — a scraped Steam JWT stays fresh
     * by its own clock for ~24h no matter who signs in afterwards, and nothing about it looks wrong. This
     * re-acquires it so the two cases can be told apart, because while the block is up every Steam read is
     * refused, which is also what makes the reactive 401 re-scrape unreachable: without this, a stale token
     * is only ever caught by [SteamSessionRefresher.sessionState]'s zero-network cookie comparison, and that
     * check is fail-open by contract (an unreadable cookie store, an unparseable value, or a stale duplicate
     * cookie row all read as "the cache is fine") — so a single missed read pinned the prompt for the rest
     * of the token's life with no other way out.
     *
     * Bounded to ONE re-acquisition per episode by [steamMismatchRecheckedSticky] (a truthful mismatch must
     * not re-scrape Steam every wake), and re-armed by [forceHeartbeatNow] — which the host calls on every
     * session-cookie change, so an actual re-login is retried immediately rather than on that bound.
     *
     * Returns the credential the rest of the cycle must use: the re-acquired one when there is one,
     * otherwise the one passed in (a failed re-acquisition leaves the verdict exactly as it was).
     */
    private suspend fun recheckMismatchedCredential(credential: SteamCredential): SteamCredential {
        // Only when the block names THIS credential: a verdict about some other token is either already
        // stale (cleared just below by [clearMismatchOnIdentityChange]) or not about us.
        if (mismatchTokenSteamId != credential.subjectSteamId) return credential
        if (steamMismatchRecheckedSticky) return credential
        setSteamMismatchRechecked(true)
        // forceRefresh re-scrapes regardless of freshness and rewrites the vault, so a stale entry cannot
        // survive it. Never throws; `null` (logged out / Steam hiccup) leaves us with what we had.
        return credentials.forceRefresh() ?: credential
    }

    /**
     * Releases the wrong-account block as soon as a credential for a *different* account is in hand.
     *
     * The verdict is only ever computed against a specific token ([mismatchTokenSteamId]), so a credential
     * that is not that token is not evidence for it — and this is the one clear site that does not need a
     * heartbeat, which matters because the heartbeat is exactly what can fail (offline, 5xx, or a prod
     * endpoint that 404s by design) while the user waits for the prompt to go away.
     *
     * Deliberately NOT extended to "no credential at all": an absent credential proves nothing about which
     * account the browser is on, so the block must stay up (fail closed) until something positive replaces it.
     */
    private suspend fun clearMismatchOnIdentityChange(credential: SteamCredential) {
        val blocked = mismatchTokenSteamId ?: return
        if (blocked != credential.subjectSteamId) setMismatchTokenSteamId(null)
    }

    /**
     * Force the next [runOnce] to POST a fresh `/heartbeat` regardless of the backend `ttl_seconds`
     * cadence, so directives + `active_tracking` are re-fetched immediately instead of on the next
     * scheduled wake. Backs a host/debug "refresh now" / "force tick" trigger.
     *
     * Marks the heartbeat due *now* (rather than clearing to `null`) so a worker respawn between this
     * call and the forced cycle can't have [restoreLoopStateOnce] re-restore the old future due-time:
     * a due-time of "now" is never `> now` on the subsequent restore check, so it stays due.
     */
    suspend fun forceHeartbeatNow() {
        val now = clock.now()
        nextHeartbeatAt = now
        runCatching { loopState.setNextHeartbeatAt(now) }
        // An explicit "run a cycle now" is also a request to retry the session mint, which is otherwise
        // once-per-episode. That is what makes the host's force-tick a usable retry button for a user
        // staring at the re-login prompt, instead of a heartbeat that cannot even be reached.
        setSteamMintAttempted(false)
        // Same for the wrong-account re-check, and this is the load-bearing half of it: a host calls this on
        // every Steam session-cookie change, so a genuine re-login re-arms the one re-acquisition per episode
        // and the block clears on the cycle that login triggered — rather than being latched out by the
        // re-check the previous, still-wrong account already spent.
        setSteamMismatchRechecked(false)
    }

    /** The delay until the next cycle: the sooner of the deal-watch poll cadence and the next heartbeat. */
    fun nextWakeDelay(): Duration {
        val floor = cadence.pollFloor(config.surface, config.mode)
        val untilHeartbeat = nextHeartbeatAt?.let { (it - clock.now()).coerceAtLeast(floor) }
        // A fresh instance with nothing to watch can only idle until the heartbeat is due — don't burn
        // poll/expedited-cadence wakes on cycles that would do nothing (a restored expedited window
        // matters only to a worker that has a tracking list to fast-poll).
        if (lastActiveTracking == null && untilHeartbeat != null) return untilHeartbeat
        val pollClass = if (isExpedited()) PollClass.ExpeditedOffer else PollClass.ActiveOffer
        val poll = cadence.nextPollDelay(config.surface, config.mode, pollClass)
        return if (untilHeartbeat == null) poll else minOf(poll, untilHeartbeat)
    }

    /**
     * Launches a continuous loop in [scope]. Reacts to scheduled wakes and (floor-gated) pushes from a
     * single consumer. Each cycle is wrapped in `runCatching` so one error never stops the loop.
     */
    fun start(scope: CoroutineScope): Job = scope.launch {
        val scheduledTicks = Channel<Unit>(Channel.CONFLATED)
        val pushDoorbell = Channel<Unit>(Channel.CONFLATED)
        val forwarders = listOf(
            launch { scheduler.ticks.collect { scheduledTicks.trySend(Unit) } },
            launch { pushChannel.signals.collect { pushDoorbell.trySend(Unit) } },
        )
        try {
            runCycle()
            while (isActive) {
                val fromPush = select {
                    scheduledTicks.onReceive { false }
                    pushDoorbell.onReceive { true }
                }
                if (fromPush) {
                    val wait = cadence.pushCoalesceDelay(clock.now(), lastRunAt, config.surface, config.mode)
                    if (wait > Duration.ZERO) delay(wait)
                    while (pushDoorbell.tryReceive().isSuccess) { /* coalesce the burst */ }
                }
                runCycle()
            }
        } finally {
            forwarders.forEach { it.cancel() }
            scheduler.cancel()
        }
    }

    /**
     * Direct entry for a push nudge (OS push callback, or the web FE's request-cycle bridge). A push is
     * cadence-respecting: between heartbeats it runs the watch pass on the cached tracking list. A fresh
     * instance has no cache — nothing cadence-respecting to do — so the nudge is honoured with a
     * heartbeat (marked due) instead of a silent idle: the pusher is telling us something changed, and
     * the pre-idle behavior of a fresh worker was to heartbeat on any cycle anyway.
     */
    suspend fun wakeFromPush(@Suppress("UNUSED_PARAMETER") signal: PushSignal): TickOutcome? {
        if (lastActiveTracking == null) forceHeartbeatNow()
        // Reporting, not raw: a push arrives on a host-owned promise, and a throw there used to be both
        // invisible and enough to skip the caller's re-arm. `null` = the cycle failed (see [runOnceReporting]).
        return runOnceReporting()
    }

    // ---- private -----------------------------------------------------------------------------------

    private suspend fun runCycle() {
        runCatching { runOnce() }.onFailure { failure ->
            // A swallowed throw used to be indistinguishable from a quiet cycle. Cancellation is teardown,
            // not a failure — reporting it would flash an error on every `stopTracker`.
            if (failure !is CancellationException) emit(LifecycleEvent.CycleFailed(failure.redactedSummary()))
        }
        lastRunAt = clock.now()
        scheduler.schedule(nextWakeDelay())
    }

    /**
     * One cycle with the same failure discipline as [runCycle] but **without** rescheduling — for a driver
     * that owns its own re-arm (the web `chrome.alarms` path). A genuine throw is reported and swallowed so
     * the caller always reaches its own re-arm: an aborted cycle must not also cost the next wake, which is
     * what used to happen on web (the throw skipped the re-arm and surfaced only as a console error).
     * Cancellation is rethrown — teardown must stay teardown, and must NOT re-arm anything.
     */
    suspend fun runOnceReporting(): TickOutcome? = try {
        runOnce()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        emit(LifecycleEvent.CycleFailed(e.redactedSummary()))
        null
    }

    private suspend fun sendHeartbeat(credential: SteamCredential): HeartbeatResponse {
        val request = HeartbeatRequest(
            clientVersion = config.clientVersion,
            platform = config.surface.platformWireName,
            foreground = config.mode == TrackerMode.Foreground,
            steamId = credential.subjectSteamId,
            deviceId = deviceId.current(),
        )
        return marketplace.heartbeat(request)
    }

    private suspend fun scheduleNextHeartbeat(ttlSeconds: Int) {
        val at = clock.now() + cadence.nextHeartbeatDelay(ttlSeconds, config.surface, config.mode)
        nextHeartbeatAt = at
        loopState.setNextHeartbeatAt(at)
    }

    /**
     * Reconciles the write-claim ledger against what the backend says it is watching, so a claim guards the
     * deal for exactly as long as a duplicate is still possible: a completed create stops guarding once its
     * deal leaves `active_tracking` (done or gone) or comes back **without** a `steam_offer_id` (the backend
     * says no offer exists, so a re-create is legitimate rather than a duplicate). This is also what keeps
     * the stored set bounded to live deals on every platform.
     *
     * Only ever called right after a **successful** heartbeat: [DealWriteGuard.staleClaims] reads an empty
     * tracking list as "watching nothing", which is only true then.
     */
    private suspend fun releaseStaleClaims(heartbeat: HeartbeatResponse) {
        // The writes this heartbeat is leasing keep their claims: that is the re-leased-under-a-fresh-id case
        // the guard exists for, and releasing here would hand it a second Steam write.
        val leased = heartbeat.directives.mapNotNull { directive ->
            directive.dealId?.takeIf {
                directive.action == DirectiveAction.CREATE_OFFER || directive.action == DirectiveAction.CANCEL_OFFER
            }?.let { DealWriteKey(it, directive.action) }
        }.toSet()
        val stale = DealWriteGuard.staleClaims(
            claims = claims.all(),
            activeTracking = heartbeat.activeTracking,
            leasedWrites = leased,
            now = clock.now(),
            ttl = config.tunables.writeClaims.claimTtl,
        )
        if (stale.isNotEmpty()) claims.release(stale)
        // Same reconciliation point, same reason: drop create cooldowns whose deadline has passed so the
        // stored set stays bounded to counterparties actually being held back.
        throttle.prune(clock.now())
    }

    /**
     * Says out loud that a directive arrived and this client will not execute it, once per directive.
     * Always returns 0 (nothing was executed) so it can stand in for the [executeDirectives] call.
     *
     * A loop built with [directivesEnabled] off never answers `/trade-actions`, so the backend re-leases
     * every directive on every heartbeat and the deal parks — for the whole life of the client. That is
     * indistinguishable from a healthy idle client unless it is reported, and it went undiagnosed on the
     * web path for exactly that reason. Reuses [LifecycleEvent.DirectiveDropped], the same event the
     * planner's malformed drops use, on the same reasoning: a directive nobody will act on is a drop.
     */
    private suspend fun announceDirectiveGate(heartbeat: HeartbeatResponse): Int {
        heartbeat.directives.forEach {
            emit(
                LifecycleEvent.DirectiveDropped(
                    it.action.wireName,
                    it.directiveId.value,
                    "directive execution is disabled on this client",
                ),
            )
        }
        return 0
    }

    /** Execute each leased directive once (single-flight), reporting the outcome. Returns the count run. */
    private suspend fun executeDirectives(heartbeat: HeartbeatResponse, credential: SteamCredential): Int {
        val handled = progress.loadHandledDirectives()
        val plan = DirectivePlanner.plan(heartbeat, handled)
        resendHandledOutcomes(plan.alreadyHandled)
        // Surface directives the planner dropped as malformed: the backend re-leases them every
        // heartbeat, so a silent drop stalls the deal invisibly. (UNKNOWN actions are not dropped.)
        plan.dropped.forEach {
            emit(LifecycleEvent.DirectiveDropped(it.directive.action.wireName, it.directive.directiveId.value, it.reason))
        }
        if (plan.isEmpty) return 0

        var done = 0
        // This heartbeat's own tracking list backs the role guard, not the cached one: it is the freshest
        // statement of which side we are on for each deal, and it is what leased these very directives.
        val tracking = heartbeat.activeTracking
        // BOTH Steam write surfaces buffer their outcomes here and are reported in ONE /trade-actions call: a
        // heartbeat can lease many creates AND many cancels, and answering each with its own POST made the
        // report volume scale with the write volume. `report_inventory` is not here — it answers on its own
        // endpoint (/inventory), which is already one call per directive by contract.
        val attempts = mutableListOf<WriteAttempt>()
        attempts += executeCreateChains(plan.creates, credential, heartbeat.linkedSteamId, tracking)
        plan.cancels.forEach { attempts += runCancel(it, credential, heartbeat.linkedSteamId, tracking) }
        done += flushWriteOutcomes(attempts)
        plan.inventoryScans.forEach { if (runInventory(it, credential)) done++ }
        return done
    }

    /**
     * Reports every Steam write this cycle performed in **one** `/trade-actions` call, then prunes the stored
     * outcomes the backend accepted. Returns how many directives to count as executed.
     *
     * Reporting is deferred to here rather than done inside each write because a cycle can perform a create per
     * counterparty chain step plus a cancel per leased cancel directive, and a POST per outcome made the report
     * volume scale with the write volume. Deferring is safe on the axis that matters: each outcome was already
     * `markHandled` + persisted at the moment its Steam write succeeded, so a worker that dies before this flush
     * cannot re-execute the write — the backend re-leases the directive and [resendHandledOutcomes] re-sends the
     * stored outcome instead.
     */
    private suspend fun flushWriteOutcomes(attempts: List<WriteAttempt>): Int {
        if (attempts.isEmpty()) return 0
        val accepted = reportOutcomes(attempts.mapNotNull { it.outcome })
        // One store write for the whole set: each prune is a full read-modify-write of the persisted outcome
        // map on every platform, so pruning per id would cost one of those per accepted write.
        pruneOutcomes(attempts.mapNotNullTo(mutableSetOf()) { it.prunableId(accepted) })
        return attempts.count { it.counted(accepted) }
    }

    /**
     * A re-served handled directive means our earlier `/trade-actions` report never landed (the
     * backend's lease expired and it re-leased) — re-send the stored outcome, **never** re-execute the
     * Steam write, and prune it once the backend accepts. One attempt per heartbeat: the retry cadence
     * stays backend-controlled. A handled directive with no stored outcome (pre-persistence legacy, or
     * already pruned) is skipped, visibly.
     */
    private suspend fun resendHandledOutcomes(reServed: List<Directive>) {
        if (reServed.isEmpty()) return
        val stored = runCatching { progress.loadDirectiveOutcomes() }.getOrElse { emptyMap() }
        val resend = mutableListOf<DirectiveOutcome>()
        for (directive in reServed) {
            val outcome = stored[directive.directiveId]
            if (outcome == null) {
                emit(LifecycleEvent.HandledDirectiveSkipped(directive.action.wireName, directive.directiveId.value))
            } else {
                resend += outcome
            }
        }
        // One call for the whole re-served set: the backend can re-lease many directives in a single heartbeat,
        // and this path used to answer each of them with its own POST.
        val accepted = reportOutcomes(resend)
        pruneOutcomes(resend.mapNotNullTo(mutableSetOf()) { it.directiveId.takeIf { id -> accepted[id] == true } })
        for (outcome in resend) {
            emit(
                LifecycleEvent.DirectiveOutcomeResent(
                    kind = outcome.action.wireName,
                    directiveId = outcome.directiveId.value,
                    status = outcome.status.name,
                    accepted = accepted[outcome.directiveId] == true,
                ),
            )
        }
    }

    /**
     * Persists one handled `directive_id` immediately. A Steam **write** must be marked handled the
     * moment it succeeds — before its `/trade-actions` report — because a failed report makes the
     * backend re-lease the directive on a later heartbeat, and re-executing a completed write means a
     * duplicate live Steam offer (which the backend cannot dedupe).
     */
    private suspend fun markHandled(id: DirectiveId) {
        runCatching { progress.recordHandledDirectives(setOf(id)) }
    }

    /**
     * [markHandled] + persist the outcome so it can be re-sent on a re-lease ([resendHandledOutcomes]).
     * Handled id first, outcome second: a crash between the two degrades to the (safe) skip-event
     * behaviour, whereas the reverse order could re-execute a completed Steam write.
     */
    private suspend fun markHandled(outcome: DirectiveOutcome) {
        markHandled(outcome.directiveId)
        runCatching { progress.recordDirectiveOutcome(outcome) }
    }

    /** Drops a stored outcome once its report was accepted — nothing left to re-send. */
    private suspend fun pruneOutcome(id: DirectiveId) = pruneOutcomes(setOf(id))

    /**
     * Drops [ids]' stored outcomes in one store write. Worth batching rather than looping [pruneOutcome]: every
     * implementation of [TrackerProgressStore.clearDirectiveOutcomes] is a read-modify-write of the whole
     * persisted map (on web, one mutex-serialised `storage.local` round trip), so per-id pruning costs one of
     * those per accepted write.
     */
    private suspend fun pruneOutcomes(ids: Set<DirectiveId>) {
        if (ids.isEmpty()) return
        runCatching { progress.clearDirectiveOutcomes(ids) }
    }

    /**
     * Runs this cycle's leased `create_offer` directives as **one chain per counterparty**: sequential inside
     * a chain, chains concurrent with each other, and a chain abandoned at its first failure. Returns how
     * many directives to count as executed.
     *
     * Why grouped rather than flat: Steam counts **outstanding offers per partner** and refuses creates past
     * that cap, while the backend leases every create it wants done — in the session that motivated this, up
     * to 30 per heartbeat, all for one partner. Run flat, that meant ~20 doomed POSTs per heartbeat and
     * eventually Steam refusing the create surface outright. Per-partner chains fix both halves: within a
     * partner one create at a time means a refusal is learned **once** instead of once per leased directive,
     * and across partners the isolation means one counterparty over quota cannot starve the rest.
     *
     * Concurrency: `coroutineScope` + `async` per chain, so a failure in one chain cannot cancel a sibling
     * (each chain converts its own failures into results; nothing throws out of a chain) and the whole set is
     * awaited before the cycle moves on. The shared state a chain touches — the claim store, the progress
     * store, the throttle — is each guarded by its own mutex.
     *
     * On the **web** target the POSTs themselves still serialize: `FetchSteamOfferCreator` holds a single
     * create mutex because its anti-CSRF `Referer` rewrite installs one fixed `declarativeNetRequest` rule
     * for the fixed create URL, and two overlapping creates would clobber each other's rule. So there the
     * chains are *logically* concurrent — independent progress, independent failure, fair ordering — while
     * the transport is one-at-a-time. Every property this method exists for survives that; only the wall-clock
     * overlap waits for a target without the rule constraint.
     */
    private suspend fun executeCreateChains(
        creates: List<Directive>,
        credential: SteamCredential,
        linkedSteamId: SteamId?,
        tracking: List<TrackedDeal>,
    ): List<WriteAttempt> {
        if (creates.isEmpty()) return emptyList()
        val chainPlan = CreateChainPlanner.plan(
            creates = creates,
            throttle = throttle.snapshot(),
            now = clock.now(),
            limits = config.tunables.steamWrites,
        )
        chainPlan.deferred.forEach { emitDeferredCreate(it.directive, it.reason, it.retryAfterSeconds) }
        if (chainPlan.isEmpty) return emptyList()
        return coroutineScope {
            chainPlan.chains
                .map { chain -> async { runCreateChain(chain, credential, linkedSteamId, tracking) } }
                .awaitAll()
                .flatten()
        }
    }

    /**
     * One counterparty's creates, strictly one at a time. Stops at the first failure — or as soon as the
     * throttle parks this partner or the whole surface, which a **sibling** chain's failure can do mid-flight,
     * hence the re-check on every step rather than only in the planner.
     */
    private suspend fun runCreateChain(
        chain: CreateChain,
        credential: SteamCredential,
        linkedSteamId: SteamId?,
        tracking: List<TrackedDeal>,
    ): List<WriteAttempt> {
        val attempts = mutableListOf<WriteAttempt>()
        chain.directives.forEachIndexed { index, directive ->
            throttledCreate(chain.partner)?.let { throttled ->
                // The gate fired before this create ran, so it is abandoned along with everything after it.
                abandonChain(chain, directive, throttled.deferReason(), index, throttled.retryAfterSeconds)
                return attempts
            }
            val attempt = runCreate(directive, credential, linkedSteamId, tracking)
            attempts += attempt
            if (attempt.stopChain) {
                // This create did run (and failed), so the abandoned tail starts after it.
                abandonChain(chain, directive, attempt.reason ?: "create failed", index + 1, null)
                return attempts
            }
        }
        return attempts
    }

    /**
     * Gives up on the rest of [chain] from [tailFrom] onwards: one summary event naming the chain, the
     * directive it stopped at and how many creates were dropped, then a per-directive deferral for each one so
     * none of them vanishes from the log silently. A chain that stopped on its own last directive has an empty
     * tail and says nothing — there is nothing left to abandon.
     */
    private suspend fun abandonChain(chain: CreateChain, at: Directive, reason: String, tailFrom: Int, retryAfterSeconds: Int?) {
        val tail = chain.directives.drop(tailFrom)
        if (tail.isEmpty()) return
        emit(
            LifecycleEvent.CreateChainStopped(
                partnerSteamId = chain.partner.value,
                directiveId = at.directiveId.value,
                reason = reason,
                skipped = tail.size,
            ),
        )
        tail.forEach { emitDeferredCreate(it, PARTNER_PARKED_MID_CHAIN, retryAfterSeconds) }
    }

    /**
     * `create_offer`: POST the Steam trade, stopping at NeedsConfirmation.
     *
     * Does **not** report: it returns its [DirectiveOutcome] inside a [WriteAttempt] and
     * [flushCreateOutcomes] reports the whole cycle's worth in one call. It also tells its chain whether to
     * stop, which is a different question from whether this directive is done with. See [executeCreateChains].
     */
    private suspend fun runCreate(
        directive: Directive,
        credential: SteamCredential,
        linkedSteamId: SteamId?,
        activeTracking: List<TrackedDeal>,
    ): WriteAttempt {
        val partner = directive.partnerSteamId ?: return WriteAttempt.SKIPPED
        // Wrong-account guard (defense-in-depth: runOnce already skips executeDirectives on a mismatch).
        // Never write to Steam for a wrong-account session; report FAILED to release the lease, but do NOT
        // markHandled — nothing was written, so a re-lease is simply re-blocked (safely idempotent).
        // It also ends the chain: the session is wrong for every deal, not just this one.
        if (!accountAllowsWrite(linkedSteamId, credential.subjectSteamId, directive.action.wireName)) {
            emit(LifecycleEvent.DirectiveExecuted("create_offer", DirectiveStatus.FAILED.name))
            return WriteAttempt(
                outcome = directive.outcome(DirectiveStatus.FAILED, error = ACCOUNT_MISMATCH_ERROR),
                stopChain = true,
                reason = ACCOUNT_MISMATCH_ERROR,
            )
        }
        // Role guard, on the same terms: writes belong to the seller alone, so a deal this heartbeat reports
        // us as the BUYER on can never earn one, whatever the backend leased. Unlike the account guard this is
        // per-deal, so the chain continues — the next deal for this partner may well be ours to write.
        if (!roleAllowsWrite(activeTracking, directive)) return WriteAttempt.SKIPPED
        // Deal-keyed duplicate guard: directive-id single-flight cannot see a *fresh* directive_id leased
        // for a deal whose offer this device already created (a lost /trade-actions report, or a backend
        // retry), and Steam would happily create a second live offer for it.
        return withDealClaim(
            dealId = directive.dealId,
            action = DirectiveAction.CREATE_OFFER,
            directiveId = directive.directiveId,
            onDuplicate = { verdict ->
                // A suppressed duplicate wrote nothing and says nothing about Steam's appetite — carry on. Its
                // replay reports itself (it restates an *earlier* write's outcome, not this cycle's), so it is
                // already tallied and must not join the batch.
                WriteAttempt(countedOutOfBand = resendClaimedOutcome(verdict.claim, directive.directiveId) != null)
            },
        ) {
            // A throwing creator (a rejected fetch — network down, missing host permission, CORS — or
            // body/regex drift) is a FAILED create, exactly as it is on the cancel surface and on the host
            // fast path: fold it into the outcome below so it is reported and surfaced. Returning early
            // instead left the lease held with no /trade-actions answer and no lifecycle event, so the
            // backend re-leased the directive forever and the deal stalled invisibly.
            val result = runCatching {
                offerCreator.createOffer(credential, TradeDraft(partner, directive.assetIds, directive.tradeToken))
            }.getOrElse { CreateOfferResult.Failed(it.redactedSummary()) }.diagnosed()
            val wroteToSteam = result is CreateOfferResult.NeedsConfirmation || result is CreateOfferResult.Created
            val outcome = when (result) {
                is CreateOfferResult.NeedsConfirmation ->
                    directive.outcome(DirectiveStatus.NEEDS_CONFIRMATION, steamOfferId = result.offerId.value)
                is CreateOfferResult.Created ->
                    directive.outcome(DirectiveStatus.SUCCESS, steamOfferId = result.offerId.value)
                is CreateOfferResult.Failed ->
                    directive.outcome(DirectiveStatus.FAILED, error = result.error)
                // A SteamOfferCreator never returns AccountMismatch or either duplicate verdict (the
                // guards above short-circuit first); handled defensively as a FAILED outcome to keep
                // the write path exhaustive and safe.
                is CreateOfferResult.AccountMismatch ->
                    directive.outcome(DirectiveStatus.FAILED, error = ACCOUNT_MISMATCH_ERROR)
                is CreateOfferResult.AlreadyCreated, is CreateOfferResult.CreateInFlight ->
                    directive.outcome(DirectiveStatus.FAILED, error = DUPLICATE_WRITE_ERROR)
                // Unreachable: the chain gates a throttled create before calling this, and a creator never
                // throttles itself. Defensive, to keep this `when` exhaustive.
                is CreateOfferResult.Throttled -> directive.outcome(DirectiveStatus.FAILED, error = THROTTLED_WRITE_ERROR)
            }
            // Feed the surface's verdict back FIRST, before anything that awaits: the sibling chains are running
            // concurrently and re-gate on every step, so the sooner a refusal lands in the throttle the fewer
            // doomed creates they attempt.
            recordCreateResult(partner, result)
            // Marked handled the moment the write succeeds, BEFORE its report leaves — which is exactly what
            // makes deferring the report to the end of the cycle safe: a worker that dies in between cannot
            // re-execute the write, it re-sends the stored outcome on the backend's next re-lease.
            //
            // A FAILED create wrote nothing to Steam, so it stays unhandled (and unclaimed) and is re-executed
            // on the next re-lease regardless of whether its FAILED report was accepted — an accepted report
            // means "received", not "stop retrying". The backend ends the retries by no longer re-serving the
            // directive; until it does, the throttle above is what keeps those re-leases off Steam.
            if (wroteToSteam) markHandled(outcome)
            // Expedite the deal-watch while the new offer awaits the seller's mobile confirmation (state
            // 9). This runs inside the cycle, so runCycle()'s trailing reschedule picks up the window.
            if (result is CreateOfferResult.NeedsConfirmation) armExpedited()
            emit(LifecycleEvent.DirectiveExecuted("create_offer", outcome.status.name, outcome.steamOfferId?.value))
            val attempt = WriteAttempt(
                outcome = outcome,
                wroteToSteam = wroteToSteam,
                // Any Steam-side refusal ends this partner's chain for the cycle: the next create for them
                // would learn nothing new, and pushing on is what escalates a quota refusal into a blocked
                // surface. A rate-limit/transport failure additionally opened a cooldown above.
                stopChain = result is CreateOfferResult.Failed,
                reason = (result as? CreateOfferResult.Failed)?.error,
            )
            attempt to outcome.takeIf { wroteToSteam }
        }
    }

    /**
     * `cancel_offer`: cancel the dangling Steam offer.
     *
     * Like [runCreate], it does **not** report — it returns its [DirectiveOutcome] inside a [WriteAttempt] and
     * [flushWriteOutcomes] reports the cycle's creates and cancels together in one call.
     */
    private suspend fun runCancel(
        directive: Directive,
        credential: SteamCredential,
        linkedSteamId: SteamId?,
        activeTracking: List<TrackedDeal>,
    ): WriteAttempt {
        val offerId = directive.steamOfferId ?: return WriteAttempt.SKIPPED
        // Wrong-account guard, on the same terms as the create ([accountAllowsWrite]) — and it matters here
        // for its own reason: a cancel POSTed from another account's cookie session cannot cancel OUR offer,
        // yet the loop reads Steam's answer as the whole outcome, so a cancel that changed nothing could be
        // reported SUCCESS while the offer is still live (and its create claim released for a re-create).
        // Report FAILED to release the lease; nothing was written, so no markHandled — a re-lease is simply
        // re-blocked.
        if (!accountAllowsWrite(linkedSteamId, credential.subjectSteamId, directive.action.wireName)) {
            emit(LifecycleEvent.DirectiveExecuted("cancel_offer", DirectiveStatus.FAILED.name))
            return WriteAttempt(outcome = directive.outcome(DirectiveStatus.FAILED, error = ACCOUNT_MISMATCH_ERROR))
        }
        // Role guard: a buyer-role deal earns no write on either surface. Cancel matters as much as create —
        // the buyer cancelling the seller's offer would abort a live delivery from the wrong side of it.
        if (!roleAllowsWrite(activeTracking, directive)) return WriteAttempt.SKIPPED
        // Guarded like the create: cancelling twice is not as damaging as creating twice, but it is still a
        // non-idempotent write whose second attempt can only fail (the offer is already gone) and be
        // reported as a failure the backend then retries.
        return withDealClaim(
            dealId = directive.dealId,
            action = DirectiveAction.CANCEL_OFFER,
            directiveId = directive.directiveId,
            onDuplicate = { verdict ->
                // The replay reports itself (it restates an earlier write's outcome), so it is already tallied
                // and must not join this cycle's batch.
                WriteAttempt(countedOutOfBand = resendClaimedOutcome(verdict.claim, directive.directiveId) != null)
            },
        ) {
            val status = runCatching { offerCanceller.cancelOffer(credential, offerId) }
                .fold(onSuccess = { DirectiveStatus.SUCCESS }, onFailure = { DirectiveStatus.FAILED })
            val cancelled = status == DirectiveStatus.SUCCESS
            val outcome = directive.outcome(status, error = if (cancelled) null else "cancel failed")
            // Marked handled before the report leaves, which is what makes the deferred (batched) report safe:
            // a worker that dies in between re-sends the stored outcome instead of re-cancelling.
            //
            // A failed cancel changed nothing on Steam (the offer may still dangle), so it stays unhandled (and
            // unclaimed) and is re-executed on the backend's next re-lease, whether or not the FAILED report was
            // accepted. The backend ends the retries by no longer re-serving it.
            if (cancelled) markHandled(outcome)
            // A cancelled offer is gone, so the create claim for this deal has nothing left to protect — and
            // a re-create is now the expected next step. Release it causally rather than making the deal wait
            // out the TTL: this, not the tracking heuristic, is what unblocks a legitimate cancel-then-recreate.
            if (cancelled) directive.dealId?.let { claims.release(setOf(DealWriteKey(it, DirectiveAction.CREATE_OFFER))) }
            emit(LifecycleEvent.DirectiveExecuted("cancel_offer", outcome.status.name, outcome.steamOfferId?.value))
            WriteAttempt(outcome = outcome, wroteToSteam = cancelled) to outcome.takeIf { cancelled }
        }
    }

    /**
     * `report_inventory`: scan the seller's Steam inventory and report the **present** asset ids of the
     * directive's on-sale set; the backend computes the stale diff. `scan_complete=false` on a failed
     * scan so the backend skips cancelling (mass-cancel guard).
     */
    private suspend fun runInventory(directive: Directive, credential: SteamCredential): Boolean {
        // Completeness now rides the port's own return value (a truncated page, an unusable body, an
        // exhausted page budget or a throw all yield complete=false), so there is no reader-identity
        // special case here any more — including for NoOpSteamInventoryReader, which reports INCOMPLETE.
        val scan = runCatching { inventoryReader.scanOwnInventory(credential) }.getOrElse { InventoryScan.INCOMPLETE }
        // Still only the directive's on-sale set, never the whole inventory. On an incomplete scan this
        // is a PARTIAL intersection, which is fine: the backend ignores the payload when scanComplete is
        // false, and if it ever didn't, a partial list is strictly safer than an empty one.
        val present = directive.assetIds.filter { it in scan.assetIds }
        val report = InventoryReport(
            directiveId = directive.directiveId,
            steamId = credential.subjectSteamId,
            deviceId = deviceId.current(),
            scanComplete = scan.complete,
            presentAssetIds = present,
            contextId = directive.contextId,
        )
        val inventoryAck = runCatching { marketplace.reportInventory(report) }
        val accepted = inventoryAck.getOrNull()?.accepted == true
        if (!accepted) {
            emit(
                LifecycleEvent.DirectiveReportFailed(
                    kind = directive.action.wireName,
                    directiveId = directive.directiveId.value,
                    reason = inventoryAck.getOrNull()?.reason.redactedRemoteText()
                        ?: inventoryAck.exceptionOrNull()?.redactedSummary(),
                ),
            )
        }
        // The report *is* the execution here (a re-scan is a harmless read), so acceptance-gating
        // the handled mark is correct: an unreported scan should be retried on re-lease. Completeness
        // gates it too — a truncated scan the backend accepted told it nothing actionable, so marking it
        // handled would single-flight the directive out and the seller's inventory would never be
        // re-scanned. Leaving it unhandled means the next re-lease tries again.
        if (accepted && scan.complete) markHandled(directive.directiveId)
        emit(LifecycleEvent.DirectiveExecuted("report_inventory", if (report.scanComplete) "SUCCESS" else "FAILED"))
        return accepted
    }

    /** Watch the tracked deals, report changed raw codes, and submit decisive proofs. Returns (reports, proofs). */
    private suspend fun watchAndReport(tracking: List<TrackedDeal>, credential: SteamCredential): Pair<Int, Int> {
        if (tracking.isEmpty()) return 0 to 0
        var proofsSubmitted = 0
        // Whether this pass has already spent a proof. The deadline bounds the CHAIN, so it cannot refuse the
        // first one — a cycle that woke milliseconds before the heartbeat was due would otherwise arrive here
        // already over budget and mint nothing at all. See [ProofMintPolicy.decide].
        var mintedThisCycle = false
        // The cycle's proving budget: stop minting once the next heartbeat is due.
        //
        // Proofs are minted one at a time, inline, under `cycleMutex` — so N due proofs serialize into
        // N × the prover's own timeout of held mutex, and the heartbeat cannot run for any of it. Measured on
        // dev: six `proofRequired` deals and a prover that wedged on every attempt held one cycle for ~16 min,
        // and the loop went 412 s between heartbeats against a 90 s advertised cadence. Presence lapsed, the
        // FE told the seller their extension was offline, and the deals it was about could not progress.
        //
        // The deadline is the heartbeat's own due time because that is the thing being starved. It is always
        // in the future here: a HEARTBEAT cycle just advanced it, and a WATCH_ONLY cycle only exists because
        // it has not arrived yet. So the FIRST proof of a cycle is never blocked — this bounds the chain, not
        // the individual proof. A single proof that outlives the whole cadence is the host's timeout to cap
        // (`notaryProofTimeoutMs` on web); nothing here can preempt a suspend that is already running.
        //
        // Null (no heartbeat yet) means no deadline to protect, so the pre-existing behaviour stands.
        val proofDeadline = nextHeartbeatAt
        // DMA-280's freshness demands, resolved FIRST and deliberately above everything below.
        //
        // A demand needs neither the dedup baseline nor a Steam observation — the backend names the trade and
        // the proven read is what discovers the code — so it must not be lost to a store blip on a read it
        // never uses, and it must not sit behind the empty-plan return, which is *precisely* the state a
        // demand exists in. It runs before the transition intents for the same reason: a mark is a payout on a
        // ~2-minute clock, while a transition's report is re-detected next cycle.
        val freshProgress = loadFreshProofProgress(tracking)
        val freshness = ProofFreshness.due(tracking, freshProgress)
        // Read only when something is actually going to mint — see the note at the intent-path read below for
        // why that gate exists. Loaded here when a demand is due, topped up there when only intents are.
        val onlineBudgets: MutableMap<DealId, Int> =
            if (freshness.demands.isEmpty()) mutableMapOf() else loadOnlineBudgets(tracking)
        for (dealId in freshness.unbindable) {
            // A mark naming no trade cannot be served by anyone. It is also invisible everywhere else — the
            // deal reports nothing and the backend's own view is "asked, unanswered" — so it is said here.
            emit(LifecycleEvent.ProofSuppressed(dealId.value, FreshProofDemand.AXIS.wireName, ProofFreshness.UNBINDABLE))
        }
        for (demand in freshness.demands) {
            emit(LifecycleEvent.FreshProofDemanded(demand.dealId.value, demand.tradeId.value, demand.proveAfter.toString()))
            // Every host with no proving context — Firefox today, and any caller that passes no prover at
            // all: `NoOpNotaryProver` answers with an EMPTY payload, which is delivered and refused, so
            // without this gate the ladder would engage on a proof that could never have worked and every
            // marked deal would POST `/notary` for nothing.
            // Keyed on the prover's own id rather than on the config, because the id is what the prover
            // asserts about itself and is already the field `ProofSubmitted` carries for this distinction.
            if (notary.id == NoOpNotaryProver.id) {
                emit(LifecycleEvent.ProofSuppressed(demand.dealId.value, FreshProofDemand.AXIS.wireName, NO_PROVER_FOR_DEMAND))
                continue
            }
            val now = clock.now()
            val standing = freshProgress[demand.dealId]
            val verdict =
                ProofMintPolicy.decideFreshness(standing, now, notaryThrottle.parkedUntil(now), mintedThisCycle, proofDeadline)
            if (verdict is ProofMintVerdict.Skip) {
                emit(
                    LifecycleEvent.ProofSuppressed(
                        demand.dealId.value,
                        FreshProofDemand.AXIS.wireName,
                        verdict.reason.message,
                        retryAfterSeconds = verdict.retryAfterSeconds,
                    ),
                )
                continue
            }
            mintedThisCycle = true
            // The wire's trade id and nothing else: no `steamOfferId` (the history read's template fills only
            // `{tradeId}` and the token) and no fallback to a local correlation, which can match a *different*
            // trade of the same asset after a rollback — the scenario the mark exists for.
            val binding = ProvenReadBinding(
                dealId = demand.dealId,
                tradeId = demand.tradeId,
                minOnlineBudget = onlineBudgets[demand.dealId],
            )
            val result =
                mintAndSubmit(binding, FreshProofDemand.AXIS, credential, now, onlineBudgets, demanded = true) ?: continue
            if (result.verified) {
                proofsSubmitted++
                recordFreshProofProgress(demand.dealId, ProofFreshness.satisfied(standing, demand))
            } else {
                // Never `proofRejected` and never `clearAcceptedProofs` — neither is expressible for a demand,
                // which is the point of it not being a ProofIntent. The ladder is the whole bound, and it is
                // armed for EVERY refusal reason rather than a parsed one: see [ProofFreshness.refused].
                val breaker = config.tunables.notary.breaker
                val laddered =
                    ProofFreshness.refused(standing, demand, clock.now(), breaker.cooldownBaseMs, breaker.cooldownMaxMs, random)
                recordFreshProofProgress(demand.dealId, laddered)
            }
            emit(
                LifecycleEvent.ProofSubmitted(
                    demand.dealId.value,
                    FreshProofDemand.AXIS.wireName,
                    verified = result.verified,
                    reason = result.reason?.redactedRemoteText(),
                    prover = notary.id,
                    demanded = true,
                ),
            )
        }
        // The dedup baseline is loaded ONCE for the whole pass and threaded into [observeTracked], which needs
        // the same snapshot to decide whether a rollback still lacks attribution. It used to be read twice
        // with *opposite* failure policies — fail-open there, fail-fast here — so one flaky store read either
        // silently re-armed attribution or unwound the entire cycle after the Steam reads had already run,
        // leaving no artifact anywhere. Now: one guarded read, and a failure skips the pass out loud.
        val reportedBaseline = runCatching { progress.loadReported() }.getOrElse {
            emit(LifecycleEvent.ProgressStoreFailed("loadReported", it.redactedSummary()))
            // Whatever the demand half above already submitted survives: it never used this read.
            return 0 to proofsSubmitted
        }
        // Fold in what the BACKEND already holds before deciding what changed. [reportedBaseline] is this
        // device's own record, so a fresh install or a second device would otherwise re-detect — and re-prove —
        // transitions the backend closed long ago. Merged in memory only, never persisted: the heartbeat
        // re-supplies it every cycle, and writing backend-derived state into our own ledger would blur which
        // of the two we are looking at when one of them is wrong. Hence two names that stay distinct for the
        // rest of the pass — everything that *decides* reads the seeded map, and the one thing that *persists*
        // reads the earned one.
        val seededBaseline = BaselineSeed.merge(tracking, reportedBaseline)
        val attributionRetriable = mutableSetOf<DealId>()
        val uncorrelated = mutableSetOf<DealId>()
        val observed = observeTracked(tracking, credential, seededBaseline, attributionRetriable, uncorrelated)
        // Keep the expedited window open while any watched deal is still in a transient (state-9) offer
        // state. Must run before the empty-plan return: a persisting state 9 is deduped to no report, but
        // we still want to keep fast-polling for the imminent confirmation.
        if (ExpeditedTransitions.anyExpedited(observed.values)) armExpedited()
        val plan: ReportPlan = TrackerTick.reduce(clock.now(), tracking, observed, seededBaseline)
        // The cycle's verdict, emitted BEFORE the empty-plan return: a watch pass that reports nothing must
        // still say why. Without this, "every code matched the baseline" and "we never managed to see the
        // axis at all" were the same silent cycle — which is exactly how an unreported rollback hid.
        emit(
            LifecycleEvent.WatchSummary(
                watched = tracking.size,
                observed = observed.size,
                historyObserved = observed.values.count { it.historyStatus != null },
                uncorrelated = uncorrelated.size,
                planned = plan.reports.size,
                suppressed = plan.suppressed,
                demanded = freshness.demands.size,
            ),
        )
        // Whatever the demand half submitted survives this return too: the change detector having nothing to
        // say is the NORMAL state of a cycle that answered a mark.
        if (plan.isEmpty) return 0 to proofsSubmitted

        // Which transitions are now corroborated. Drives the report gate below, so it must be the
        // intent itself and not a (deal, axis) pair: a later decisive code on the same axis is a
        // different transition and must not inherit this one's verdict.
        val proofVerified = mutableSetOf<ProofIntent>()
        val proofFailed = mutableSetOf<Pair<DealId, TradeStatusSource>>()
        val acceptedProofs = loadAcceptedProofs(tracking)
        // What earlier refusals taught about each deal's online-decryption budget.
        //
        // Read only when there is a proof to spend it on, unlike the ledger above — that read is unconditional
        // because its stale-row prune rides it, and this one prunes on its own line below. While v1 runs
        // client-reported (`proofRequired` off everywhere) `proofIntents` is always empty and no mark is
        // stamped, so this is a storage round-trip per wake that nothing would ever consult.
        //
        // Topped up rather than assigned: the demand half above may already have loaded it, and the two mint
        // loops must share one map. `learnOnlineBudget` writes into it as it goes, because two axes of one
        // deal can both fail in a single pass and a lesson written against a pass-start snapshot would not see
        // what the first one just stored — costing a redundant identical write.
        if (plan.proofIntents.isNotEmpty() && freshness.demands.isEmpty()) onlineBudgets.putAll(loadOnlineBudgets(tracking))
        // A demand on the same (deal, axis) proves the SAME read — `GetTradeStatus?tradeid=…` — and
        // `SubmitProofRequestDto` is only `{dealId, proofPayload}`, so the two submissions are byte-equivalent
        // and the backend cannot tell them apart: two ~30 MB MPC sessions for one fact. Reachable whenever a
        // history transition is still live through a protection hold, which is exactly when a mark is stamped.
        //
        // Deliberately NOT cross-corroborated into `proofVerified`: if Steam now says 12 the demand's proof
        // attests 12, and letting that release a report of 3 is the money bug in reverse. The report simply
        // waits one cycle, by which point either the mark is satisfied or a real change is detected.
        val demandedAxes = freshness.demands.mapTo(HashSet()) { it.dealId to FreshProofDemand.AXIS }
        for (intent in plan.proofIntents) {
            if ((intent.dealId to intent.source) in demandedAxes) {
                emit(LifecycleEvent.ProofSuppressed(intent.dealId.value, intent.source.wireName, SUPERSEDED_BY_DEMAND))
                continue
            }
            // Whether this proof is spent at all is [ProofMintPolicy]'s call, not this loop's: four reasons it
            // might not be, in one ordered decision. The ordering is the part that has to be somewhere it can
            // be tested — a spending gate evaluated before a settled backend answer withholds a report for an
            // unrelated reason, which is exactly what shipped once.
            //
            // The park is read per intent rather than once per pass on purpose: a failure inside this very loop
            // arms it, and the deals behind that failure should then be given up rather than each spending
            // their own ~30 MB to prove the same prover is still broken. That is the shape of the dev incident
            // — six tracked deals, every attempt wedged.
            val now = clock.now()
            val verdict = ProofMintPolicy.decide(
                intent = intent,
                now = now,
                refused = proofRejected,
                accepted = acceptedProofs,
                acceptedTtlMs = config.tunables.notary.acceptedProofTtlMs,
                proverParkedUntil = notaryThrottle.parkedUntil(now),
                mintedThisCycle = mintedThisCycle,
                cycleDeadline = proofDeadline,
            )
            if (verdict is ProofMintVerdict.Skip) {
                // An ALREADY_ACCEPTED skip still counts as verified, so the report it corroborates goes out.
                // Read off the reason rather than re-derived here, so the two cannot disagree.
                if (verdict.reason.corroborated) proofVerified += intent
                emit(
                    LifecycleEvent.ProofSuppressed(
                        intent.dealId.value,
                        intent.source.wireName,
                        verdict.reason.message,
                        retryAfterSeconds = verdict.retryAfterSeconds,
                    ),
                )
                continue
            }
            val deal = tracking.firstOrNull { it.dealId == intent.dealId }
            val binding = ProvenReadBinding(
                dealId = intent.dealId,
                steamOfferId = deal?.steamOfferId,
                // The history axis's proven read addresses a single trade by this id, so a history proof cannot
                // be built without it. Absent means Steam has not set it yet (the offer is pre-acceptance),
                // which is also a state that has no history row to prove — the mapper's `requireNotNull` turns
                // any residual case into a loud ProofFailed rather than a malformed read.
                tradeId = observed[intent.dealId]?.tradeId,
                minOnlineBudget = onlineBudgets[intent.dealId],
            )
            // Marked before the outcome is known: a FAILED attempt spends the same wall clock and the same MPC
            // session as a successful one, and the budget is about what the cycle can still afford.
            mintedThisCycle = true
            // Null means the backend never saw the proof — generation or delivery failed, and the attempt has
            // already had its say (ProofFailed, the breaker fold, any budget lesson). Retry next tick.
            val result = mintAndSubmit(binding, intent.source, credential, now, onlineBudgets) ?: run {
                proofFailed += intent.dealId to intent.source
                continue
            }
            // Delivered but verified=false is terminal (the MVP mock verify always says false); resubmitting
            // the identical proof can't change the verdict, so latch it off. Either way it is emitted, because
            // "rejected once, forever" is the outcome least visible from the counters: it moves neither
            // proofsSubmitted nor the retry path.
            if (result.verified) {
                proofsSubmitted++
                proofVerified += intent
                // Remember the verdict so a report the backend refuses anyway does not buy another MPC
                // session on the next wake. Persisted, because an MV3 worker respawns between most cycles.
                recordAcceptedProof(intent)
            } else {
                proofRejected += intent
                // A fresh proof for this transition was just refused, so any earlier acceptance of it is no
                // longer the backend's position — drop it rather than let a respawn (which clears the
                // in-memory latch above) reuse a verdict that has since been overturned.
                clearAcceptedProofs(setOf(intent))
            }
            emit(
                LifecycleEvent.ProofSubmitted(
                    intent.dealId.value,
                    intent.source.wireName,
                    verified = result.verified,
                    // The backend's own string, so it is scrubbed and capped here exactly as the report
                    // rejection above is — it is unbounded and not ours to vouch for.
                    reason = result.reason?.redactedRemoteText(),
                    prover = notary.id,
                ),
            )
        }

        // PROOFS FIRST, then the reports they corroborate.
        //
        // The backend refuses a `proofRequired` deal's report with `P2P_PROOF_REQUIRED` until the proof for
        // that exact transition has verified, so reporting first bought one guaranteed-refused POST per cycle
        // per deal — and printed the refusal ABOVE the proof that would have satisfied it, which is a large
        // part of why this path read as a client defect for three rounds of debugging.
        //
        // Gated per (deal, axis, code), by VALUE: [ProofIntent] and [TradeStatusReport] carry exactly those
        // three fields, so a report finds its intent without any positional pairing (the same discipline the
        // acknowledgement matching below exists for). A report with NO intent — `proofRequired` false, or a
        // non-decisive code — is untouched and still goes out unconditionally: there is no proof for it to
        // wait for, and gating it would mean a broken prover stopped the backend learning anything at all.
        val dueProofs = plan.proofIntents.toSet()
        val (sendable, withheld) = plan.reports.partition { report ->
            val intent = ProofIntent(report.dealId, report.source, report.steamStatusCode)
            intent !in dueProofs || intent in proofVerified
        }
        // A withheld report sends nothing, so it has to SAY so. Silence would make it indistinguishable from
        // a cycle that observed nothing — the exact failure mode every other event on this path exists to
        // break, and the one that cost a live debugging session when the proof latch skipped in silence.
        for (report in withheld) {
            emit(
                LifecycleEvent.TradeStatusReportDeferred(
                    report.dealId.value,
                    report.source.wireName,
                    report.steamStatusCode,
                    REPORT_AWAITS_PROOF,
                ),
            )
        }

        // Pair each report with ITS result — never positionally. A batch carries both axes of the same deal
        // and the response may be short, reordered or collapsed, so a zip could mark one axis accepted off
        // another's acknowledgement; an accepted code enters the dedup baseline and is never re-sent again.
        // Matched-or-not-accepted, and every non-acceptance is emitted (a rejected report was previously
        // filtered out in silence, and a thrown batch vanished entirely).
        var accepted = emptyList<TradeStatusReport>()
        if (sendable.isNotEmpty()) {
            val acks = runCatching { marketplace.reportTradeStatus(sendable) }.fold(
                onSuccess = { ReportAcknowledgement.match(sendable, it) },
                onFailure = { failure ->
                    val reason = failure.redactedSummary()
                    sendable.map { ReportAck(it, accepted = false, reason = reason) }
                },
            )
            accepted = acks.filter { it.accepted }.map { it.report }
            for (ack in acks) {
                val report = ack.report
                if (ack.accepted) {
                    emit(LifecycleEvent.TradeStatusReported(report.dealId.value, report.source.wireName, report.steamStatusCode))
                } else {
                    emit(
                        LifecycleEvent.TradeStatusReportFailed(
                            report.dealId.value,
                            report.source.wireName,
                            report.steamStatusCode,
                            // Either our own summary (already scrubbed) or the backend's rejection string,
                            // which is scrubbed + capped here — it is unbounded and not ours to vouch for.
                            ack.reason.redactedRemoteText(),
                        ),
                    )
                }
            }
        }

        // A rollback we reported without an initiator is not finished: the backend reads a missing actor as
        // "undecided" and parks the deal. Steam signs out whoever reversed the trade, so the attribution
        // read usually fails on exactly the tick 12 first appears — persisting the baseline now would dedup
        // the code away and the deal would park permanently, with no back-fill path. Withhold it instead
        // (same mechanism as a failed proof) so the 12 is re-detected until attribution resolves.
        //
        // Only for deals where attribution is actually RETRIABLE. When Steam gave us no correlation inputs
        // at all, no number of retries can resolve an actor, and withholding would re-report that rollback
        // on every tick forever — so those are reported once, as undecided, and deduped normally.
        val attributionPending = plan.reports
            .filter { it.source == TradeStatusSource.HISTORY && it.dealId in attributionRetriable }
            .map { it.dealId to it.source }
            .toSet()

        // Persist the dedup baseline only for codes the backend accepted AND whose decisive proof (if
        // one was due) was submitted — anything else must be re-detected and retried next tick.
        //
        // Against the EARNED baseline, not the seeded one: what we persist is this device's own record of
        // accepted reports, and a seeded offer code is the backend's claim, not ours. Passing the merged map
        // would write that claim into our ledger the first time some *other* axis of the same deal was
        // accepted — quietly making a value we chose not to trust indistinguishable from one we earned.
        persistReported(
            accepted.filterNot { (it.dealId to it.source) in proofFailed || (it.dealId to it.source) in attributionPending },
            reportedBaseline,
        )
        // An accepted report is the end of that transition: its code is now in the dedup baseline, so no
        // further intent will be planned for it and the stored verdict has nothing left to corroborate.
        // Pruned here rather than left to expire so the ledger tracks live work only.
        clearAcceptedProofs(accepted.map { ProofIntent(it.dealId, it.source, it.steamStatusCode) }.toSet())
        return accepted.size to proofsSubmitted
    }

    /**
     * Mint one proof for [binding] on [source] and submit it, returning the backend's [ProofResult] — or
     * `null` when the backend never saw it, in which case the attempt has already had its say.
     *
     * **Shared by both proof axes because everything in here is a property of the PROVER, not of what is
     * being proved:** the `countedFailureMinMs` exclusion, the two `CancellationException` carve-outs, the
     * breaker fold and the online-budget lesson. Each of those was arrived at from a specific incident, and a
     * second hand-maintained copy for the freshness axis is exactly how this codebase's other duplicated
     * vocabularies (`LIFECYCLE_PROBLEMS`, the curated block union) came to disagree with the thing they
     * mirrored.
     *
     * **What it deliberately does NOT do is the verdict bookkeeping or the `ProofSubmitted` emit.** Both are
     * per-axis: a transition keys a persisted acceptance ledger and an in-memory refusal latch on its
     * [ProofIntent], a demand advances its own persisted ladder, and their events differ. Keeping the emit at
     * the call site also preserves this file's ordering rule — the state a host might read is set *before* the
     * event that announces it — which an emit from in here would silently invert.
     *
     * [now] is the caller's pre-attempt clock reading, and it is used only for the success fold. The failure
     * fold re-reads the clock on purpose: it has to *measure* the attempt, and [now] predates it.
     */
    private suspend fun mintAndSubmit(
        binding: ProvenReadBinding,
        source: TradeStatusSource,
        credential: SteamCredential,
        now: Instant,
        onlineBudgets: MutableMap<DealId, Int>,
        demanded: Boolean = false,
    ): ProofResult? {
        val attemptStartedAt = clock.now()
        // The whole ProofResult, not just its Boolean: the backend's `reason` is the only text that says WHY a
        // proof was refused, and projecting it away here is what made `"empty proof_payload"` — the backend
        // naming the exact defect — invisible to every consumer of the event stream.
        val outcome = runCatching {
            val proof = notary.proveTransition(binding, source, credential)
            marketplace.submitProof(proof)
        }
        // Generation or delivery failed — the backend never saw the proof: retry next tick.
        val result = outcome.getOrElse { failure ->
            // Fold it into the parked-prover ladder BEFORE returning, so a second failure in this same pass
            // parks the surface and the deals behind it are skipped rather than each spending an MPC session.
            // Cancellation is teardown, not a prover failure — same carve-out as the event below.
            //
            // A failure that returned faster than `countedFailureMinMs` is excluded: it never engaged the
            // prover, so it spent none of the cost the breaker exists to bound and says nothing about the
            // prover's health. The case this is for is a stale proving context right after a host reload,
            // which failed two proofs in 7 ms and 15 ms and parked nine healthy deals for it. The clock is
            // re-read rather than reusing `now`: `now` predates the attempt, and the fold must measure it.
            val spentEnough = clock.now() - attemptStartedAt >= config.tunables.notary.breaker.countedFailureMinMs.milliseconds
            if (failure !is CancellationException && spentEnough) {
                notaryThrottle.onResult(generated = false, now = clock.now())
            }
            // A refusal that names the online budget it needed is the one failure on this path that teaches
            // something. Learned per deal and persisted, so the MPC session it cost is spent once rather than
            // on every wake. Deliberately no event of its own: the value it sets is printed on the next
            // attempt's issuance line (`maxRecvOnline=`), so an event would only restate it.
            learnOnlineBudget(binding.dealId, failure.message, onlineBudgets)
            // A cancelled cycle is teardown, not a notary problem — same carve-out as CycleFailed. Everything
            // else gets a voice: the withheld baseline in the caller makes the deal re-report its code every
            // tick, and without the event that looks like broken dedup rather than a proof that cannot be
            // produced.
            if (failure !is CancellationException) {
                emit(LifecycleEvent.ProofFailed(binding.dealId.value, source.wireName, failure.redactedSummary(), demanded))
            }
            return null
        }
        // The prover produced a proof and it reached the backend — whatever the verdict, this prover works, so
        // the failure ladder clears. Deliberately not keyed on `verified`: a backend that starts refusing
        // proofs must not park the client's ability to make them.
        notaryThrottle.onResult(generated = true, now = now)
        return result
    }

    /**
     * The accepted-proof ledger, narrowed to the deals still being tracked.
     *
     * **Fail-open, and deliberately quiet about the consequence:** an unreadable ledger yields an empty map,
     * which means every due proof is minted — the behaviour before the ledger existed. The opposite policy
     * (abort the pass, as the dedup-baseline read does) would trade a cost regression for a correctness one,
     * and this read gates nothing the backend depends on. The failure is still emitted, because a store that has
     * started failing is worth knowing about before the notary bill says so.
     *
     * Pruning by the tracked set bounds the ledger exactly as [assetIdCache] and the latches beside it are
     * bounded: a terminated deal must not leave a row behind forever. A deal merely *absent* from one
     * heartbeat therefore loses its record and re-proves once — a cost, not a correctness problem, and the
     * same trade every cache here already makes.
     *
     * Called once per pass that has a plan, and deliberately NOT narrowed further to passes that have proof
     * intents: the prune rides this read, so gating it on intents would leave a terminated deal's row behind
     * until some other deal happened to need a proof. One `storage.local` read on a cycle that turns out not
     * to need the ledger is the cheaper half of that trade, and on the healthy path the map is empty anyway
     * (an accepted report prunes its own row in the same cycle that wrote it).
     */
    private suspend fun loadAcceptedProofs(tracking: List<TrackedDeal>): Map<ProofIntent, Instant> {
        val stored = runCatching { progress.loadAcceptedProofs() }.getOrElse {
            emit(LifecycleEvent.ProgressStoreFailed("loadAcceptedProofs", it.redactedSummary()))
            return emptyMap()
        }
        if (stored.isEmpty()) return stored
        val trackedIds = tracking.mapTo(HashSet()) { it.dealId }
        val stale = stored.keys.filterTo(HashSet()) { it.dealId !in trackedIds }
        if (stale.isNotEmpty()) clearAcceptedProofs(stale)
        return stored - stale
    }

    /**
     * This device's freshness standing, narrowed to the deals still being tracked.
     *
     * **Fail-open, and silently**, like [loadOnlineBudgets] and unlike the dedup-baseline read: an unreadable
     * map reads as "no mark satisfied", so a demand already answered is answered once more. That costs one MPC
     * session; the opposite policy would strand a payout on a transient `storage.local` failure, and the whole
     * point of this axis is that a payout does not get stranded. No event of its own, because the demand it
     * affects emits `FreshProofDemanded` and its outcome anyway — one would only restate the other.
     *
     * The prune matches the ledgers beside it: a permanent per-deal row must not outlive its deal, and a deal
     * merely absent from one heartbeat re-answers a mark it had already satisfied at the price of one proof.
     * Called unconditionally, above the empty-plan return, because a demand exists precisely when the plan is
     * empty — so gating the read on a plan would be gating it on the state it is for.
     */
    private suspend fun loadFreshProofProgress(tracking: List<TrackedDeal>): Map<DealId, FreshProofProgress> {
        val stored = runCatching { progress.loadFreshProofProgress() }.getOrElse { return emptyMap() }
        if (stored.isEmpty()) return stored
        val trackedIds = tracking.mapTo(HashSet()) { it.dealId }
        val stale = stored.keys.filterTo(HashSet()) { it !in trackedIds }
        if (stale.isNotEmpty()) runCatching { progress.clearFreshProofProgress(stale) }
        return stored - stale
    }

    /**
     * Persist [standing] for [dealId]. Best-effort, like the ledgers beside it: losing the write costs one
     * re-answered mark on a later wake, which is strictly better than failing a cycle that has just delivered
     * a proof the backend accepted.
     */
    private suspend fun recordFreshProofProgress(dealId: DealId, standing: FreshProofProgress) {
        runCatching { progress.recordFreshProofProgress(dealId, standing) }
    }

    /**
     * The learned online-budget map, narrowed to the deals still being tracked.
     *
     * Fail-open and silent, unlike [loadAcceptedProofs]: an unreadable map costs one refused MPC session and
     * nothing else, and the refusal it leads to is emitted on its own. The prune matches that ledger's — this
     * is a permanent per-deal row, so a terminated deal must not leave one behind forever, and a deal merely
     * absent from one heartbeat re-learns at the price of one refusal.
     */
    private suspend fun loadOnlineBudgets(tracking: List<TrackedDeal>): MutableMap<DealId, Int> {
        val stored = runCatching { progress.loadOnlineBudgets() }.getOrElse { return mutableMapOf() }
        if (stored.isEmpty()) return mutableMapOf()
        val trackedIds = tracking.mapTo(HashSet()) { it.dealId }
        val stale = stored.keys.filterTo(HashSet()) { it !in trackedIds }
        if (stale.isNotEmpty()) runCatching { progress.clearOnlineBudgets(stale) }
        return (stored - stale).toMutableMap()
    }

    /**
     * Reads an online-budget requirement out of a refused proof and records it for the deal.
     *
     * Silent when the failure says nothing about the budget, which is every other failure on this path — a
     * wedge, a dead realm, an unreachable notary; [OnlineBudgetLesson.learn] also answers `null` when the
     * lesson is not new, so there is no is-this-a-change check here. Best-effort persistence: losing the
     * lesson costs one more refusal, never a stuck deal. [budgets] is updated alongside the store so a second
     * failing axis in the same pass sees what the first one learned.
     */
    private suspend fun learnOnlineBudget(dealId: DealId, error: String?, budgets: MutableMap<DealId, Int>) {
        val learned = OnlineBudgetLesson.learn(
            error = error,
            previous = budgets[dealId],
            floor = config.tunables.notary.maxRecvDataOnline,
            marginPercent = config.tunables.notary.onlineBudgetMarginPercent,
        ) ?: return
        budgets[dealId] = learned
        runCatching { progress.recordOnlineBudget(dealId, learned) }
    }

    private suspend fun recordAcceptedProof(intent: ProofIntent) {
        runCatching { progress.recordAcceptedProof(intent, clock.now()) }
            .onFailure { emit(LifecycleEvent.ProgressStoreFailed("recordAcceptedProof", it.redactedSummary())) }
    }

    private suspend fun clearAcceptedProofs(intents: Set<ProofIntent>) {
        if (intents.isEmpty()) return
        runCatching { progress.clearAcceptedProofs(intents) }
            .onFailure { emit(LifecycleEvent.ProgressStoreFailed("clearAcceptedProofs", it.redactedSummary())) }
    }

    /**
     * A deal's `assetId` is immutable for its lifetime, so it is fetched once and cached — steady-state
     * cycles then issue zero `/p2p/deals/{id}` reads on the watch path (was an N+1 per history-watched
     * deal, every tick). In-memory: a respawned worker simply re-fetches on the next history read.
     */
    private val assetIdCache = mutableMapOf<DealId, AssetId>()

    /**
     * Deals whose cached asset id has already been discarded and re-read once because it correlated to
     * nothing. Bounds the re-key attempt to **one per deal per loop instance**: a genuinely wrong join key
     * is worth one extra `/p2p/deals/{id}` read, but a deal Steam simply has no row for must not turn into a
     * per-tick read for the life of the worker. Pruned alongside [assetIdCache].
     */
    private val assetIdRefetched = mutableSetOf<DealId>()

    /**
     * Deals whose already-reported rollback has had its reversal actor re-asserted once. Bounds the
     * back-fill attempt for a `12` that is **already in the dedup baseline** — see the reasoning at its use
     * site. Pruned alongside [assetIdCache].
     */
    private val initiatorReasserted = mutableSetOf<DealId>()

    /**
     * Deals whose already-reported history code has had its settlement window re-asserted once. Same bound,
     * same reason as [initiatorReasserted] — a re-assert cannot be bounded by acceptance, because only an
     * accepted report sets the sticky flag and a refused duplicate would otherwise buy a `/trade-events`
     * POST every tick. Pruned alongside [assetIdCache].
     */
    private val settlementReasserted = mutableSetOf<DealId>()

    /**
     * Transitions whose proof the backend has already **refused** (`verified = false`).
     *
     * Keyed by the [ProofIntent] itself — the axis *and* the exact code it covered — so a later decisive code
     * on the same deal still proves. That is the plan's own key type, held right here in the loop, and it is a
     * data class, so its value equality is already what a set needs.
     *
     * This is what makes the "don't retry a rejected proof" rule in [watchAndReport] actually hold. That rule
     * was only ever enforced through the dedup baseline — and the baseline is persisted solely for reports the
     * backend ACCEPTED, while a proof-enforcing backend withholds acceptance until a proof verifies. So the
     * one regime the rule exists for was the one regime it could not reach: the plan re-minted the identical
     * report and the identical proof on every single tick, and the observed cost was a futile POST pair per
     * minute until the deal's deadline cancelled it. Cheap with the stub; one full MPC session per minute per
     * stuck deal once the real prover is on.
     *
     * The report is deliberately NOT suppressed — only the proof. A refused report is worth retrying (the
     * backend can change its mind, and the operator can fix the client's config); resubmitting identical
     * proof bytes cannot change their verdict.
     *
     * In-memory and pruned alongside [assetIdCache], exactly like [assetIdRefetched] and its siblings: a
     * respawned worker granting one more attempt is correct here, because a respawn is also when a newly
     * published notary URL takes effect — the one thing that CAN change the answer. A debug endpoint switch
     * restarts the loop, so it clears this too.
     *
     * Deliberately **not** re-armed by [forceHeartbeatNow], unlike the two Steam-session latches it does clear.
     * That is called on every Steam session-cookie change, and a Steam session rotating says nothing about a
     * proof the backend has already refused — re-arming there would resurrect exactly the per-cycle futile
     * submission this latch exists to stop.
     */
    private val proofRejected = mutableSetOf<ProofIntent>()

    /**
     * Whether to spend a `GetTradeHistory` read this cycle.
     *
     * The revert watch is the sparse axis by contract — reverts are rare and non-urgent, and
     * `docs/P2P_Client-Contract_Spec.md` says poll it ~hourly — but nothing enforced that:
     * [PollClass.RevertWatch] is selected by no production caller and there was no interval gate inside a
     * cycle, so the read fired on **every** wake for as long as any deal watched history. Measured on a real
     * session: 47 reads × 14.6 KB in 72 minutes, ~83 s apart, against a config pinning the interval at 1 h.
     * That is a live Steam rate-limit exposure and a silent no-op knob.
     *
     * **It is not a plain interval gate**, because two things are only learnable promptly:
     *
     *  1. *The protection window.* Steam **clears** `time_settlement` on the row it flips to `12`, so a
     *     rollback structurally carries none — the only chance to capture one is a read taken while the row
     *     still had it. A flat hourly gate would lose it for any deal that rolled back inside its first hour.
     *     So a deal whose history code is not yet in the dedup baseline is read promptly.
     *  2. *The reversal actor.* Steam signs out whoever reversed a trade, so attribution usually fails on the
     *     very tick a `12` appears — and the backend reads a missing actor as "undecided" and **parks the
     *     deal**. Going sparse there would park it for an hour. So a baselined rollback whose actor has not
     *     been reported yet also stays prompt. (Found by a pre-existing regression test, not by reasoning.)
     *
     * Only once every watched deal is past both does the axis go sparse — which is exactly the regime the
     * sparse cadence was written for: watching an already-resolved row for a change.
     *
     * **A DMA-280 freshness demand deliberately does NOT promote this gate**, and that is the whole reason
     * the demand path is built the way it is. At hold expiry a watched deal's history code is already
     * baselined and attributed, so this returns the sparse answer — an hour, against the backend's ~2-minute
     * release grace. Promoting it would also buy nothing: the read is account-wide and bounded to
     * `historyMaxTrades`, and a days-old trade is very likely outside that window. So the demand is answered
     * from the trade id the BACKEND supplies and spends no poll at all. Do not "fix" this by making a marked
     * deal prompt — it would re-arm the measured 47-reads-in-72-minutes regression this gate exists to close,
     * and still not see the row. Pinned by `answering_a_mark_costs_no_steam_history_read`.
     *
     * The settlement *re-assert* path ([settlementReasserted]) is deliberately NOT prompt: it recovers a
     * window that became readable only after the code was baselined, which the next sparse read still does —
     * an hour's delay on a 7-day window is immaterial, and making it prompt would hold the axis at
     * every-cycle cadence forever for any trade Steam never gives a window at all.
     */
    private suspend fun historyDue(tracking: List<TrackedDeal>, reportedBaseline: Map<DealId, ReportedStatus>): Boolean {
        val watched = tracking.filter { it.watchesHistory }
        if (watched.isEmpty()) return false
        // Never observed, or a rollback still missing its actor — see (1) and (2) above.
        val unresolved = watched.any { deal ->
            val seen = reportedBaseline[deal.dealId]
            seen?.lastHistoryCode == null ||
                (seen.lastHistoryCode == ROLLBACK_STATUS && !seen.historyInitiatorReported)
        }
        if (unresolved) return true
        val last = runCatching { loopState.revertWatchAt() }.getOrNull() ?: return true
        return clock.now() - last >= cadence.targetInterval(PollClass.RevertWatch)
    }

    /** Both Steam axes per tracked deal: offer by `steam_offer_id`; history by asset id (cached). */
    private suspend fun observeTracked(
        tracking: List<TrackedDeal>,
        credential: SteamCredential,
        /** The dedup baseline for this pass, loaded once by the caller. */
        reportedBaseline: Map<DealId, ReportedStatus>,
        /** Filled with deals whose rollback attribution failed but **could** succeed on a later tick. */
        attributionRetriable: MutableSet<DealId>,
        /** Filled with history-watched deals whose transfer is due but correlated to no row. */
        uncorrelated: MutableSet<DealId>,
    ): Map<DealId, ObservedTrade> {
        // Bound the assetId cache to the currently-tracked deals so a long-lived worker never
        // accumulates entries for deals that have since terminated.
        val trackedIds = tracking.mapTo(HashSet()) { it.dealId }
        assetIdCache.keys.retainAll(trackedIds)
        assetIdRefetched.retainAll(trackedIds)
        initiatorReasserted.retainAll(trackedIds)
        settlementReasserted.retainAll(trackedIds)
        proofRejected.retainAll { it.dealId in trackedIds }
        val needHistory = historyDue(tracking, reportedBaseline)
        val watchedOfferIds = tracking.mapNotNull { it.steamOfferId }.toSet()
        // The two Steam axes are independent reads; fetch them concurrently (both browser-side GETs).
        val (offerResult, historyResult) = coroutineScope {
            val offerJob = async { runCatching { steamReader.offerSnapshots(credential, watchedOfferIds) } }
            val historyJob = async {
                if (needHistory) {
                    runCatching {
                        steamReader.recentTransfers(credential, config.tunables.steamEndpoints.historyMaxTrades)
                    }
                } else {
                    null
                }
            }
            offerJob.await() to historyJob.await()
        }
        // A persistent (non-auth) read failure would otherwise be an invisible no-op tick — surface it.
        val offerSnapshots = offerResult.getOrElse {
            emit(LifecycleEvent.SteamReadFailed("offer", it.redactedSummary()))
            emptyMap()
        }
        val transfers = historyResult?.getOrElse {
            emit(LifecycleEvent.SteamReadFailed("history", it.redactedSummary()))
            emptyList()
        } ?: emptyList()
        // Stamp only a read that actually happened AND succeeded. A failure must not start the sparse
        // interval — that would turn one Steam blip into an hour of not watching for a rollback.
        if (historyResult?.isSuccess == true) runCatching { loopState.setRevertWatchAt(clock.now()) }
        val result = mutableMapOf<DealId, ObservedTrade>()
        for (deal in tracking) {
            val offer = deal.steamOfferId?.let { offerSnapshots[it] }
            val offerState = offer?.state
            // Keep the matched transfer, not just its status int: reversal attribution needs the row's
            // `time_mod` and counterparty. Correlation is delegated to the pure selector because a
            // rollback puts TWO rows carrying this asset in the payload and only one of them is the deal's.
            val matched = if (deal.watchesHistory && transfers.isNotEmpty()) {
                correlateTransfer(deal, transfers, offer, uncorrelated)
            } else {
                null
            }
            val historyStatus = matched?.status
            // Who reversed it. Gated on the rollback code and on there being no actor on record yet — NOT on
            // the code being fresh. Gating on freshness is what deadlocked this: the same baseline field
            // dedups the report, so a `12` reported while attribution was unresolved silenced both the report
            // and the read that could still resolve it, and the actor rides only a report. So the deal parked
            // with escrow untouched and the client went quiet about it, permanently. A normal (non-rollback)
            // cycle still never touches the notification endpoint.
            //
            // Attribution is only *possible* when Steam gave us both correlation inputs. Recording that
            // separately matters: a rollback we could never attribute must not be retried forever (see
            // the withholding in watchAndReport) — it is reported once as undecided and deduped.
            val attributable = matched?.partnerSteamId != null && matched.modifiedAt != null
            val initiatorOnRecord = reportedBaseline[deal.dealId]?.historyInitiatorReported == true
            val needsAttribution = historyStatus == ROLLBACK_STATUS && !initiatorOnRecord && attributable
            // A code already in the baseline makes an actor a RE-ASSERT of something the backend already has,
            // and that **send** is bounded to one per deal per loop instance. It cannot be bounded by
            // acceptance: the backend may well refuse a duplicate terminal code (LWW admits forward
            // transitions), and since only an accepted report sets `historyInitiatorReported`, one refusal
            // would otherwise buy a `/trade-events` POST on every tick for as long as the deal is tracked.
            // In-memory, like [assetIdRefetched] — a respawned worker gets one more attempt, which is the
            // self-healing direction rather than a per-tick cost.
            val alreadyBaselined = reportedBaseline[deal.dealId]?.lastHistoryCode == ROLLBACK_STATUS
            val reversalInitiator = if (needsAttribution && !(alreadyBaselined && deal.dealId in initiatorReasserted)) {
                notifications.reversalInitiator(credential, matched.partnerSteamId, matched.modifiedAt)
                    ?.also {
                        attributionRetriable -= deal.dealId
                        // Spend the one-shot only on an actor we actually got: a *failed* read resolves nothing
                        // and must not cost the attempt, or an actor Steam publishes a tick later is lost.
                        if (alreadyBaselined) initiatorReasserted += deal.dealId
                    }
                    ?: run {
                        attributionRetriable += deal.dealId
                        null
                    }
            } else {
                null
            }
            // The Trade-Protection window, on exactly the same terms as attribution above. It needs no extra
            // read — it is already on the correlated row — but it is gated the same way, because it too can
            // re-assert a code the baseline already has: populating it unconditionally would re-report an
            // unchanged history code on every single tick for the life of the deal.
            //
            // Steam CLEARS `time_settlement` on the row it flips to 12, so a rollback carries none and there
            // is no later read that could recover it. That is the whole reason the window rides every history
            // report rather than only the decisive ones.
            val settlementOnRecord = reportedBaseline[deal.dealId]?.historySettlementReported == true
            val settlementBaselined = matched != null && reportedBaseline[deal.dealId]?.lastHistoryCode == matched.status
            val settlementAt = matched?.settlementAt
                ?.takeIf { !settlementOnRecord }
                ?.takeIf { !(settlementBaselined && deal.dealId in settlementReasserted) }
                ?.also { if (settlementBaselined) settlementReasserted += deal.dealId }
            if (offerState != null || historyStatus != null) {
                result[deal.dealId] = ObservedTrade(
                    offerState = offerState,
                    historyStatus = historyStatus,
                    reversalInitiator = reversalInitiator,
                    settlementAt = settlementAt,
                    // Carried for the proof binding only — the history axis proves `GetTradeStatus?tradeid=…`,
                    // and this is where that id is known. Prefer the correlated history row's own id and fall
                    // back to the offer's: they are the same value (the row is selected BY the offer's id), but
                    // the row is the axis being proven, so it is the more direct source.
                    tradeId = matched?.tradeId ?: offer?.tradeId,
                )
            }
        }
        return result
    }

    /**
     * The deal's own transfer row out of the account-wide history read.
     *
     * **Steam's `tradeid` first.** It is the row's identity and the offer axis already read it — Steam
     * attaches it to the offer on acceptance — so the join is exact and costs nothing. When it is known and
     * still finds no row, the row is simply outside the `max_trades` window; falling back to the asset ref
     * then would be actively wrong, because that key can match a *different* trade of the same asset (an item
     * returns under its original id after a rollback and may be sold again).
     *
     * The asset ref is the fallback for a deal with **no** trade id: an offer Steam no longer lists, or one
     * this build never saw accepted. It is weaker and it costs a `/p2p/deals/{id}` read to learn the ref, so it
     * is only reached when a transfer can exist at all — an offer known to be pre-acceptance
     * ([TransferCorrelation.isTransferDue]) provably has no row, and must not spend a deal read every tick.
     * A wrong or rotated ref used to silence the axis for the life of the worker with nothing to show for it,
     * so the ref is re-read once (bounded by [assetIdRefetched]) before the miss is reported.
     *
     * The re-read **replaces** the memoised key only on success. Discarding it first would mean a transient
     * `/p2p/deals/{id}` failure left the deal with no key at all, and since its one re-key attempt is already
     * spent, every later tick would re-issue that read — reinstating exactly the per-tick N+1 the cache
     * exists to remove.
     */
    private suspend fun correlateTransfer(
        deal: TrackedDeal,
        transfers: List<SteamTransfer>,
        offer: SteamOfferSnapshot?,
        uncorrelated: MutableSet<DealId>,
    ): SteamTransfer? {
        offer?.tradeId?.let { tradeId ->
            TransferCorrelation.selectByTradeId(transfers, tradeId)?.let { return it }
            uncorrelated += deal.dealId
            emit(LifecycleEvent.HistoryCorrelationMiss(deal.dealId.value, transfers.size, refetched = false))
            return null
        }
        // No trade id. A transfer can only exist if Steam either accepted the offer or no longer tells us
        // about it at all — anything else is provably pre-transfer and must not cost a deal read.
        if (offer != null && !TransferCorrelation.isTransferDue(offer.state)) return null
        val cached = assetIdCache[deal.dealId] ?: lookupAssetId(deal.dealId)
        cached?.let { key -> TransferCorrelation.select(transfers, key)?.let { return it } }
        // Only re-key a value we actually had: a lookup that just failed is already retried next tick.
        val refetched = cached != null && assetIdRefetched.add(deal.dealId)
        if (refetched) {
            lookupAssetId(deal.dealId, force = true)
                ?.takeIf { it != cached }
                ?.let { key -> TransferCorrelation.select(transfers, key)?.let { return it } }
        }
        uncorrelated += deal.dealId
        emit(LifecycleEvent.HistoryCorrelationMiss(deal.dealId.value, transfers.size, refetched))
        return null
    }

    /**
     * The deal's history join key, memoised for the loop instance. The failure branch is **named**: it used
     * to be swallowed whole (`runCatching { … }.getOrNull()`), and since this one read feeds every
     * history-watched deal, a renamed or absent field on the deal snapshot took the whole axis dark at once
     * with nothing anywhere to say so.
     *
     * [force] re-reads a key that is already memoised (the re-key path). The cache is only ever *written* on
     * success, so a failed re-read leaves the previous key in place rather than emptying the cache.
     */
    private suspend fun lookupAssetId(dealId: DealId, force: Boolean = false): AssetId? {
        if (!force) assetIdCache[dealId]?.let { return it }
        return runCatching { marketplace.getDeal(dealId).assetId }
            .onFailure { emit(LifecycleEvent.DealLookupFailed(dealId.value, it.redactedSummary())) }
            .getOrNull()
            ?.also { assetIdCache[dealId] = it }
    }

    private suspend fun persistReported(reports: List<TradeStatusReport>, base: Map<DealId, ReportedStatus>) {
        if (reports.isEmpty()) return
        val updates = mutableMapOf<DealId, ReportedStatus>()
        for (report in reports) {
            val current = updates[report.dealId] ?: base[report.dealId] ?: ReportedStatus()
            updates[report.dealId] = when (report.source) {
                TradeStatusSource.OFFER -> current.copy(lastOfferCode = report.steamStatusCode)
                TradeStatusSource.HISTORY -> current.copy(
                    lastHistoryCode = report.steamStatusCode,
                    // Sticky, and only ever set by an ACCEPTED report: this is what ends the attribution
                    // retry loop, so it must mean "the backend has the actor", not "we once found one".
                    historyInitiatorReported = current.historyInitiatorReported || report.reversalInitiatorSteamId != null,
                    // Same contract for the settlement window: sticky, and set only by an accepted report,
                    // because it is what stops the loop re-asserting a window the backend already holds.
                    historySettlementReported = current.historySettlementReported || report.settlementTime != null,
                )
            }
        }
        if (updates.isNotEmpty()) progress.recordReported(updates)
    }

    /**
     * The polling path's transfer-axis gate, now delegating to the domain's [watches] so the axis-to-`watch`
     * mapping is stated once. `TrackerTick` needs the same mapping for the proof gate, and two copies of it
     * is how the offer axis came to be polled for every deal while only the proof side knew about `watch`.
     */
    private val TrackedDeal.watchesHistory: Boolean
        get() = watches(TradeStatusSource.HISTORY)

    private fun Directive.outcome(status: DirectiveStatus, steamOfferId: String? = null, error: String? = null): DirectiveOutcome =
        DirectiveOutcome(
            directiveId = directiveId,
            action = action,
            status = status,
            dealId = dealId,
            steamOfferId = steamOfferId?.let { com.dmarket.p2p.tracker.model.OfferId(it) },
            error = error,
        )
}
