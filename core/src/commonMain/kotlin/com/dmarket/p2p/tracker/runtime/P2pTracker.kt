package com.dmarket.p2p.tracker.runtime

import com.dmarket.p2p.tracker.adapter.host.NoOpEventObserver
import com.dmarket.p2p.tracker.adapter.host.NoOpWakeAcknowledger
import com.dmarket.p2p.tracker.client.marketplace.RateLimitedException
import com.dmarket.p2p.tracker.engine.ProofFreshness
import com.dmarket.p2p.tracker.engine.ProofSkipReason
import com.dmarket.p2p.tracker.engine.TrackerBlock
import com.dmarket.p2p.tracker.engine.dealsAwaitingProof
import com.dmarket.p2p.tracker.loop.TradeTrackerLoop
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.DeviceId
import com.dmarket.p2p.tracker.model.LifecycleEvent
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.TrackerMode
import com.dmarket.p2p.tracker.model.Wake
import com.dmarket.p2p.tracker.model.WakeCause
import com.dmarket.p2p.tracker.model.WakeOutcome
import com.dmarket.p2p.tracker.model.WakeOutcomeKind
import com.dmarket.p2p.tracker.model.marketplace.ApnsTarget
import com.dmarket.p2p.tracker.model.marketplace.NetworkType
import com.dmarket.p2p.tracker.model.marketplace.PushProvider
import com.dmarket.p2p.tracker.model.steam.SteamCookie
import com.dmarket.p2p.tracker.net.redactedSummary
import com.dmarket.p2p.tracker.port.host.Clock
import com.dmarket.p2p.tracker.port.host.DeviceIdStore
import com.dmarket.p2p.tracker.port.host.EventObserver
import com.dmarket.p2p.tracker.port.host.NetworkConditions
import com.dmarket.p2p.tracker.port.marketplace.DealRefresh
import com.dmarket.p2p.tracker.port.marketplace.DealRefreshClient
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceServerErrorException
import com.dmarket.p2p.tracker.port.marketplace.WakeAcknowledger
import com.dmarket.p2p.tracker.port.steam.CreateOfferResult
import com.dmarket.p2p.tracker.port.steam.SteamWebSessionState
import com.dmarket.p2p.tracker.wire.parsePushEnvelope
import com.dmarket.p2p.tracker.wire.toWakeOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The mobile tracker: one [TradeTrackerLoop] per Steam account, woken by pushes and, while the app is on screen, by
 * its own polling. The app owns the push transport and the UI; it calls [onPush] with each payload, [setForeground]
 * from its lifecycle, and reads [state].
 *
 * **Push model** (contract d455888 §2, §5.1, §6.4). [onPush] acks a wake `done` at once ("the work started"), then
 * always syncs: a heartbeat (`foreground: false` in the background) carrying the wake's id, so the wrong-account
 * guard and the stale-claim release run on every wake; then it acks the final [WakeOutcome] under the same id. The heartbeat only says "last seen": the backend's mobile
 * `ttl_seconds` is at least 24 h, and nothing is woken for it — in the background no wake is scheduled at all.
 *
 * **One cycle at a time.** Every call into the loop — a push's sync, a poll, the mode switch — runs under one lock and
 * within [cycleDeadline]. A cancelled cycle releases the lock. On Android the sync runs in the core's Worker under
 * unique work.
 *
 * **Accounts.** [linkedAccount] names the Steam account the app's user links; [start] serves it. Another account
 * means another loop: the previous one is cancelled and joined before [loopFor] opens the new account's stores, so
 * nothing the old loop holds in memory lands in them. A `null` account stops the tracker.
 *
 * **Proof gate.** Before each cycle the tracker reads [network]: on a metered one a notary proof (~20 MB) runs only
 * with the user's opt-in or a manual [updateStatus] the user allowed it for (T-13). The opt-in is [cellularProofs] for
 * a cycle on screen (no wake, the app in the foreground) and [backgroundProofs] for a wake or a background cycle.
 * Otherwise the proof is deferred: [LifecycleEvent.ProofDeferred], [ProofState.DEFERRED], and the decisive report that needs it is
 * not sent, nor claimed unproven, until a proof runs. Sync, reads and reports go on any network.
 *
 * @param loopFor builds the loop for an account, emitting to the observer it is given.
 * @param network what the device's network costs right now.
 * @param cellularProofs the user's "proofs on mobile data" option for a cycle on screen, read on each cycle; the host
 *   holds it and its default (off, D6).
 * @param eventObserver every lifecycle event, after the tracker's own handling.
 * @param deliveryTimeout how long [startDelivery] waits for the create before it answers
 *   [CreateOfferResult.CreateInFlight]; the create goes on regardless.
 * @param random the draws behind the 429 backoff's jitter.
 * @param clearAccountState what [onLogout] erases: the account's state, the Steam credential and the Steam session's
 *   cookies, never the device id.
 * @param eraseProofs erases the end-of-deal proofs the device keeps ([clearStoredProofs]); also run when
 *   [linkedAccount] answers `null` (the user unlinked Steam).
 * @param importSteamCookies puts a Steam sign-in's cookies in the core's vault and drops the token scraped from the
 *   previous session ([MobileTrackerPlatform.importSteamSession]); `null` when the host keeps the session itself.
 * @param acknowledger the wake ack (`POST …/wake-ack`); the DMarket client on mobile.
 * @param deviceRegistry the push device registry behind [registerDevice] / [unregisterDevice]; `null` refuses both.
 * @param backgroundProofs the user's "background checks on mobile data" option, read on each cycle: the opt-in for a
 *   wake or a background cycle, and what every heartbeat reports as `backgroundProveAllowed`. Defaults to
 *   [cellularProofs], so one option governs both, as before.
 * @param dealRefresh the server permit a manual [updateStatus] takes first (`POST …/deals/{id}/refresh`, DMA-963);
 *   `null` takes none.
 */
class P2pTracker(
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val deviceIds: DeviceIdStore,
    private val linkedAccount: suspend () -> SteamId?,
    private val loopFor: suspend (account: SteamId, observer: EventObserver) -> TradeTrackerLoop,
    private val network: NetworkConditions,
    private val cellularProofs: () -> Boolean,
    private val acknowledger: WakeAcknowledger = NoOpWakeAcknowledger,
    private val eventObserver: EventObserver = NoOpEventObserver,
    private val cycleDeadline: Duration = DEFAULT_CYCLE_DEADLINE,
    random: Random = Random.Default,
    private val deliveryTimeout: Duration = DEFAULT_DELIVERY_TIMEOUT,
    private val clearAccountState: suspend () -> Unit = {},
    private val eraseProofs: suspend () -> Unit = {},
    private val importSteamCookies: (suspend (List<SteamCookie>) -> Unit)? = null,
    private val deviceRegistry: DeviceRegistry? = null,
    private val backgroundProofs: () -> Boolean = cellularProofs,
    private val dealRefresh: DealRefreshClient? = null,
) {
    private val mutableState = MutableStateFlow(TrackerState())

    /** What the tracker shows the app; see [TrackerState]. */
    val state: StateFlow<TrackerState> = mutableState.asStateFlow()

    /** Guards [session] and [foreground]: start, stop and the mode switch. [close] only cancels, without it. */
    private val lifecycle = Mutex()

    @Volatile
    private var session: Session? = null
    private var foreground = false

    /** The last start failed on a fault (not on a missing account): coming on screen tries it again. */
    private var startFailed = false

    /** Set when a cycle watched its deals; read under the session's cycle lock. */
    private var watchedAt: Instant? = null

    /** What the running cycle ran into, for [updateStatus]; replaced at each cycle's start, under its lock. */
    private var probe = CycleProbe()

    private class CycleProbe {
        var countsSessions = false
        var heartbeat = false
        var retryAfter: Duration? = null
        var steamRateLimited = false
        var steamReadFailed = false
        var failed = false
        var dmarketRateLimited = false
        var directiveDropped = false

        /** A create Steam did not answer: held, unreported, so the wake did not do its work. */
        var createUnanswered = false
        var proofDeferred = false
        var proofOutOfBudget = false

        /** A proof the cycle could not run for want of a prover: none, parked, or a demand it cannot bind or retry yet. */
        var proofBlocked = false

        /** A second proof the cycle had no time left for ([ProofSkipReason.BUDGET_SPENT]). */
        var proofBudgetSpent = false

        /** Deals whose proof DMarket holds after this cycle: accepted now, or already within its window. */
        val proofLanded = mutableSetOf<String>()
        val proofFailed = mutableSetOf<String>()
        val proofUndelivered = mutableSetOf<String>()
        val reportFailed = mutableSetOf<String>()
        val proofRefused = mutableSetOf<String>()

        /**
         * Deal and axis whose proof is the seller's ([ProofSkipReason.SELLER_PROVES]): this device is the buyer, sends
         * the code once without a proof, and DMarket may refuse that report. Neither a failure nor a pending proof here.
         */
        val sellerProves = mutableSetOf<Pair<String, String>>()
    }

    /**
     * A manual update's server permit for one deal (DMA-963, DMA-1016): it holds [until] the server's `nextAllowedAt`
     * (or a refresh 429's `Retry-After`, then [EXHAUSTED]) and counts the notary sessions run under it. In memory only:
     * after the process dies the next refresh answers 429 and the wait shows.
     */
    private data class Permit(val until: Instant, val sessions: Int)

    private val permits = MutableStateFlow<Map<DealId, Permit>>(emptyMap())

    /**
     * One surface's 429s, whichever cycle or deal ran into them: until when the server's own `Retry-After` asks to wait,
     * and until when the backoff's wait runs. An answer from the surface ends the run of 429s and the backoff's wait;
     * the server's `Retry-After` holds to its end.
     */
    private class SurfaceCooldown(random: Random) {
        private val backoff = RateLimitBackoff(random)

        @Volatile
        private var retryAfterUntil: Instant? = null

        @Volatile
        private var backoffUntil: Instant? = null

        val until: Instant? get() = listOfNotNull(retryAfterUntil, backoffUntil).maxOrNull()

        /** A 429 at [now]; returns the wait it asks for. */
        fun refused(now: Instant, retryAfterSeconds: Long?): Duration {
            val wait = backoff.refused(retryAfterSeconds)
            backoffUntil = now + wait
            retryAfterSeconds?.coerceAtLeast(0)?.let { retryAfterUntil = listOfNotNull(retryAfterUntil, now + it.seconds).max() }
            return wait
        }

        fun answered() {
            backoff.answered()
            backoffUntil = null
        }

        fun reset() {
            answered()
            retryAfterUntil = null
        }
    }

    /** DMarket's run of 429s ends on a heartbeat that came back, Steam's on a cycle that read it. */
    private val dmarketCooldown = SurfaceCooldown(random)
    private val steamCooldown = SurfaceCooldown(random)

    /** Whose DMarket 429s [dmarketCooldown] counts: another account or a logout starts it over; Steam's stays. */
    private var dmarketAccount: SteamId? = null

    private class CycleRun(val next: Duration, val completed: Boolean, val probe: CycleProbe)

    private val observer = EventObserver { event ->
        track(event)
        eventObserver.onEvent(event)
    }

    private inner class Session(val account: SteamId, val loop: TradeTrackerLoop) {
        val job: Job = SupervisorJob(this@P2pTracker.scope.coroutineContext[Job])
        val scope: CoroutineScope = CoroutineScope(this@P2pTracker.scope.coroutineContext + job)
        val cycleLock = Mutex()
        var driver: Job? = null
    }

    /** Serves [linkedAccount]'s account: starts its loop, or keeps the running one. Returns whether one runs. */
    suspend fun start(): Boolean = lifecycle.withLock { startLocked() != null }

    /** Stops the loop and waits for its cycle to end. */
    suspend fun stop() = lifecycle.withLock { stopLocked() }

    /**
     * The user logged out of DMarket: stops the loop, waits for its cycle to end, then erases the account's state and
     * the Steam credential. The device id stays. Under the start lock, so no start opens the stores in between.
     */
    suspend fun onLogout() = lifecycle.withLock {
        stopLocked()
        resetDmarketCooldown(null)
        clearAccountState()
    }

    /** Erases the end-of-deal proofs this device keeps for the current account (D2): the app calls it on logout. */
    suspend fun clearStoredProofs() = eraseProofs()

    /**
     * The app's user signed in to Steam in the app (`steamSignedIn`): [cookies] become the Steam session, in place of
     * whatever the vault held ([MobileTrackerPlatform.importSteamSession]). A host calls
     * [MobileTracker.importSteamSession], which comes here once the tracker is built; see the contract there.
     *
     * With a loop running, the import waits for its cycle, judges the account binding on the new session at once (a
     * create asked for next sees that verdict) and forces the next cycle's heartbeat, which confirms it with DMarket. Returns the session's state, or `null` when no loop runs: the vault is written
     * all the same, and the first cycle after the start reads it.
     */
    suspend fun importSteamSession(cookies: List<SteamCookie>): SteamWebSessionState? {
        val import = importSteamCookies ?: return null
        val s = session?.takeIf { it.job.isActive }
        if (s == null) {
            import(cookies)
            return null
        }
        return s.cycleLock.withLock {
            import(cookies)
            s.loop.judgeImportedSteamSession()
            s.loop.forceHeartbeatNow()
            publishSteamSession(s)
        }
    }

    /**
     * The ON_START check: the Steam session's state from the vault, renewed first when it is due and belongs to the
     * linked account. Imports nothing. Runs under the cycle lock, so it never overlaps a cycle's own refresh. `null`
     * when no loop runs.
     */
    suspend fun checkSteamSession(): SteamWebSessionState? {
        val s = sessionOrStart() ?: return null
        return s.cycleLock.withLock {
            s.loop.checkSteamSession()
            publishSteamSession(s)
        }
    }

    /** The app came on screen ([foreground] `true`) or left it. Only on screen does the tracker poll. */
    suspend fun setForeground(foreground: Boolean) = lifecycle.withLock {
        this.foreground = foreground
        mutableState.update { it.copy(foreground = foreground) }
        val s = session ?: run {
            if (foreground && startFailed) startLocked()
            return@withLock
        }
        s.cycleLock.withLock { s.loop.mode = modeOf(foreground) }
        if (foreground) {
            if (s.driver?.isActive != true) s.driver = s.scope.launch { drive(s) }
        } else {
            s.driver?.cancel()
            s.driver = null
        }
    }

    /**
     * Handles one wake push (contract d455888 §2.2) and returns how it ended, or `null` for a payload that is no wake
     * (an unknown `type` without a `wakeId`, unreadable JSON): no ack, no sync. Starts the tracker first when it is not
     * running (a push that woke the process).
     *
     * A wake past its `deadline` is dropped: one ack `failed`, no sync. Otherwise the wake is acked `done` at once,
     * synced (a full sync whatever deal it names; its `transactionId` is only logged), and its final outcome acked
     * once, under the same `wakeId` (the last ack wins). The outcome never changes what the cycle reports: a create
     * that did not run is left unanswered, never reported `failed` (§4.3).
     *
     * @param deadline how long the host lets the work run from now; the cycle runs within the lesser of it and the
     *   tracker's cycle deadline, and a deadline already spent, or reached, is [WakeCause.BUDGET].
     * @param allowProof whether a proof may run in this wake; `false` (an iOS background push) mints none
     *   ([ProofSkipReason.WAKE_BUDGET]) and the report that needs it waits.
     */
    suspend fun onPush(payload: String, deadline: Duration = cycleDeadline, allowProof: Boolean = true): WakeOutcome? {
        val wake = parsePushEnvelope(payload)?.toWakeOrNull() ?: return null
        val ends = TimeSource.Monotonic.markNow() + deadline
        val expired = wake.deadline?.let { clock.now() >= it } == true
        val early = when {
            expired -> WakeOutcome.failed(WakeCause.OTHER)
            !deadline.isPositive() -> WakeOutcome.failed(WakeCause.BUDGET)
            else -> null
        }
        val received = if (early == null) ack(wake, WakeOutcome.DONE) else false
        emitQuietly(
            LifecycleEvent.WakeReceived(
                wake.type,
                wake.dealId?.value,
                wake.proveAfter?.toString(),
                received,
                wake.wakeId,
                wake.transactionId,
                wake.reason,
                expired,
            ),
        )
        if (early != null) return finish(wake, early)
        val outcome = try {
            val s = sessionOrStart()
            if (s == null) {
                WakeOutcome.failed(if (startFailed) WakeCause.OTHER else WakeCause.STEAM_SESSION)
            } else {
                val run = cycle(s, wake, allowCellularProof = false, allowProof = allowProof, ends = ends)
                if (run == null) WakeOutcome.failed(WakeCause.OTHER) else wakeOutcomeOf(wake, s.loop, run)
            }
        } catch (e: CancellationException) {
            // The host stopped the work (WorkManager's stop, the process going down): say so if there is time.
            withContext(NonCancellable) { withTimeoutOrNull(STOPPED_ACK_TIMEOUT) { finish(wake, WakeOutcome.failed(WakeCause.BUDGET)) } }
            throw e
        }
        return finish(wake, outcome)
    }

    /** Sends the final ack of [wake] and announces it. */
    private suspend fun finish(wake: Wake, outcome: WakeOutcome): WakeOutcome {
        val acknowledged = ack(wake, outcome)
        emitQuietly(LifecycleEvent.WakeFinished(wake.wakeId, outcome.kind.wireName, outcome.cause?.wireName, acknowledged))
        return outcome
    }

    /** One wake ack; `false` when it did not go out (no `wakeId` to key it, or the call failed). */
    private suspend fun ack(wake: Wake, outcome: WakeOutcome): Boolean {
        val wakeId = wake.wakeId ?: return false
        return try {
            acknowledger.acknowledge(deviceIds.current(), wakeId, outcome)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Registers this install's push token (`PUT …/devices/{deviceId}`, contract d455888 §3.2): the core adds the device
     * id, platform, client version and capabilities ([DeviceRegistry.capabilities]). The app decides when: after login,
     * on a new token, on start when anything changed (§3.3). [apns] is required with [PushProvider.APNS] and refused
     * otherwise. Returns `null` when the backend took it, else why not. Needs no running loop, only a DMarket session.
     */
    suspend fun registerDevice(pushToken: String, provider: PushProvider, apns: ApnsTarget? = null): DealCallError? =
        registryCall { it.register(deviceIds.current(), pushToken, provider, apns) }

    /**
     * Removes this install from the registry (`DELETE …/devices/{deviceId}`): on logout while the DMarket session still
     * works, and on a Steam account unlink or switch (§3.3). An unknown id is a success too.
     */
    suspend fun unregisterDevice(): DealCallError? = registryCall { it.unregister(deviceIds.current()) }

    private suspend fun registryCall(call: suspend (DeviceRegistry) -> Unit): DealCallError? {
        val registry = deviceRegistry ?: return DealCallError(DealCallErrorKind.UNAVAILABLE)
        return try {
            call(registry)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            DealCallError(DealCallErrorKind.OTHER)
        } catch (e: Throwable) {
            e.toDealCallError()
        }
    }

    /**
     * The manual "Update status" (T-12): a sync, the deal's Steam status read, its report and, when the deal needs one,
     * its proof — one cycle under the same lock as every other, forced to heartbeat. It watches every tracked deal, this
     * one included. Within the cooldown a 429 left ([DealTrackingState.cooldownUntil], or any cycle's 429 on DMarket or
     * Steam, for every deal) it sends nothing and answers [UpdateFailure.RATE_LIMITED] at once.
     *
     * **The server permit** (DMA-963, DMA-1016). Before the cycle the tracker takes the deal's permit
     * (`POST …/deals/{id}/refresh`). A permit allows `1 + retries` notary sessions for the deal (a cancelled one
     * counts): while one is left, the next run goes without a refresh, against the entry the forced heartbeat serves.
     * Once they are spent it answers [UpdateFailure.RATE_LIMITED] until [StatusUpdate.nextAllowedAt], sending nothing,
     * unless a proof made for the deal was not delivered: those bytes go again within their hour, no session and no
     * Retry spent, and a resend DMarket had already accepted reads as success.
     * The refresh's own 429 waits exactly its `Retry-After`, for this deal only; Kong's 429 takes the DMarket backoff
     * like any other. A 503 (or any other failure of the call) is [UpdateFailure.DMARKET] and charged nothing; a deal
     * with nothing to refresh (no watch entry, or a 404) still runs its cycle, uncounted.
     *
     * @param allowCellularProof the user allowed a proof on mobile data for this update (T-13); the gate stays as it is
     *   for every other cycle.
     * @param retries how many Retries a permit allows after its first session (the app's remote config); below 0 is 0.
     */
    suspend fun updateStatus(dealId: DealId, allowCellularProof: Boolean = false, retries: Int = DEFAULT_RETRIES): StatusUpdate {
        val allowed = retries.coerceAtLeast(0)
        val now = clock.now()
        listOfNotNull(state.value.deals[dealId]?.cooldownUntil, dmarketCooldown.until, steamCooldown.until)
            .maxOrNull()
            ?.takeIf { it > now }
            ?.let { return statusOf(dealId, UpdateFailure.RATE_LIMITED, allowed, retryAfter = it - now) }
        val s = sessionOrStart() ?: return StatusUpdate(failure = UpdateFailure.NOT_RUNNING)
        val permit = activePermit(dealId, now)
        when {
            permit == null -> takePermit(dealId, now, allowed)?.let { return it }
            // Bytes made and not delivered go again under a spent permit: sending them is no notary session.
            // shortcut: the bypass is per deal and the bytes per transition, so a deal with bytes held for one axis and a
            // proof due on the other also mints that one; gate the mint on the permit if both ever come due together.
            permit.sessions > allowed && !s.loop.hasUndeliveredProof(dealId) ->
                return statusOf(dealId, UpdateFailure.RATE_LIMITED, allowed, retryAfter = permit.until - now)
        }
        val run = cycle(s, wake = null, manual = true, allowCellularProof, countsSessions = true)
            ?: return statusOf(dealId, UpdateFailure.NOT_RUNNING, allowed)
        val retryAfter = run.probe.retryAfter
        if (retryAfter != null) deal(dealId.value) { it.copy(cooldownUntil = clock.now() + retryAfter) }
        return statusOf(dealId, failureOf(dealId, s.loop, run), allowed, retryAfter, s.loop.observedCode(dealId))
    }

    /** [dealId]'s permit while it holds. */
    private fun activePermit(dealId: DealId, now: Instant): Permit? = permits.value[dealId]?.takeIf { it.until > now }

    /** Takes [dealId]'s server permit; `null` lets the run go on, else the answer that ends it. */
    private suspend fun takePermit(dealId: DealId, now: Instant, retries: Int): StatusUpdate? {
        val client = dealRefresh ?: return null
        val refresh = try {
            client.refreshDeal(dealId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RateLimitedException) {
            val retryAfter = e.retryAfterSeconds
            val wait = if (e.errorCode == REFRESH_RATE_LIMITED && retryAfter != null) {
                val until = now + retryAfter.coerceAtLeast(0).seconds
                permits.update { it + (dealId to Permit(until, EXHAUSTED)) }
                until - now
            } else {
                dmarketCooldown.refused(now, retryAfter).also { wait -> deal(dealId.value) { it.copy(cooldownUntil = now + wait) } }
            }
            return statusOf(dealId, UpdateFailure.RATE_LIMITED, retries, retryAfter = wait)
        } catch (e: MarketplaceServerErrorException) {
            // A 404 is an old closed deal the refresh no longer reads: nothing to refresh, not an error.
            if (e.statusCode != NOT_FOUND) return statusOf(dealId, UpdateFailure.DMARKET, retries)
            DealRefresh(tracked = false)
        } catch (_: Throwable) {
            return statusOf(dealId, UpdateFailure.DMARKET, retries)
        }
        val until = refresh.nextAllowedAt
        permits.update { if (refresh.tracked && until != null) it + (dealId to Permit(until, 0)) else it - dealId }
        return null
    }

    /**
     * One notary session ran for [dealId]: an update's cycle counts it against the deal's permit.
     * shortcut: counted when the attempt ends, so a re-tap that lands between Cancel and the old cycle's end still sees
     * the session unspent; count at the prove's start if that window ever matters.
     */
    private fun countNotarySession(dealId: DealId) {
        if (!probe.countsSessions) return
        permits.update { all ->
            val permit = all[dealId] ?: return@update all
            if (permit.sessions == EXHAUSTED) all else all + (dealId to permit.copy(sessions = permit.sessions + 1))
        }
    }

    /**
     * The seller's "Create trade" (§A7): the seller's consent to deliver the deal, and the create that follows. The
     * tracker calls `POST /p2p/deals/{id}/create-trade` itself and runs the `create_offer` it answers with through the
     * heartbeat's own directive path, so the same deal leased by a heartbeat, or asked for twice, ends in one Steam
     * offer. This is the only caller of `create-trade` on mobile.
     *
     * Call it only on the seller's own action, never as an automatic retry after a failure: each call consents to the
     * deal's current attempt, and after a failed offer that is a replacement, so a retry would send a new offer.
     *
     * Waits up to `deliveryTimeout`, then answers [CreateOfferResult.CreateInFlight]; a later call answers with the
     * created offer. A run that ends without a directive says why in [DeliveryOutcome.reasonCode] (the backend
     * refused) or [DeliveryOutcome.error] (the last call failed). The work runs as one cycle, under the same lock and
     * proof gate, so the wait includes a cycle already running.
     */
    suspend fun startDelivery(dealId: DealId): DeliveryOutcome {
        val s = sessionOrStart()
            ?: return DeliveryOutcome(CreateOfferResult.Failed(NOT_RUNNING_ERROR), error = NOT_RUNNING_CALL)
        val result = s.loop.awaitDirectiveOutcome(dealId, deliveryTimeout, s.scope) { work -> locked(s) { work() } }
        if (result !is CreateOfferResult.Failed) return DeliveryOutcome(result)
        val call = s.loop.lastDeliveryCall(dealId)
        return DeliveryOutcome(
            result,
            reasonCode = call?.getOrNull()?.reasonCode?.takeIf { it.isNotBlank() },
            error = call?.exceptionOrNull()?.toDealCallError(),
        )
    }

    /**
     * The seller's accept (COMMIT): a sync first (a forced heartbeat and one cycle, as on screen), then
     * `POST /p2p/deals/{id}/accept`. When the sync leaves the tracker blocked (the wrong Steam account, no Steam session,
     * DMarket unreachable), the accept is not sent and the block is the answer.
     */
    suspend fun acceptDeal(dealId: DealId): AcceptOutcome {
        val s = sessionOrStart() ?: return AcceptOutcome.Failed(NOT_RUNNING_CALL)
        cycle(s, wake = null, manual = true, onScreen = true) ?: return AcceptOutcome.Failed(NOT_RUNNING_CALL)
        s.loop.blockingState.takeIf { it != TrackerBlock.NONE }?.let { return AcceptOutcome.Blocked(it) }
        return try {
            AcceptOutcome.Done(s.loop.marketplace.acceptDeal(dealId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AcceptOutcome.Failed(e.toDealCallError())
        }
    }

    /** The deal's detail, `GET /p2p/deals/{id}`; its state reads with or without the `P2P_DEAL_STATE_` prefix. */
    suspend fun getDeal(dealId: DealId): DealRead {
        val s = sessionOrStart() ?: return DealRead.Failed(NOT_RUNNING_CALL)
        return try {
            DealRead.Found(s.loop.marketplace.getDeal(dealId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            DealRead.Failed(e.toDealCallError())
        }
    }

    /** The install's device id, for the app's device registry (DMA-540). */
    suspend fun deviceId(): DeviceId = deviceIds.current()

    /**
     * Cancels the loop without waiting for it; the tracker can be started again. The next start, logout or stop waits
     * for the cancelled loop to end before it goes on, so a new loop never opens the account's stores while it writes.
     */
    fun close() {
        session?.job?.cancel()
        mutableState.update { TrackerState(foreground = it.foreground) }
    }

    /** The running session, or the one [linkedAccount] starts; `null` when the app's user links no account. */
    private suspend fun sessionOrStart(): Session? = lifecycle.withLock { session?.takeIf { it.job.isActive } ?: startLocked() }

    private suspend fun startLocked(): Session? {
        val account = try {
            linkedAccount()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return startFault(e)
        } ?: run {
            stopLocked()
            // An unlink: the account's proofs go with it (D2). A logout and a switch erase the whole account store. A
            // storage fault here must not fail the stop; the next start erases again.
            try {
                eraseProofs()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
            }
            return null
        }
        session?.takeIf { it.account == account && it.job.isActive }?.let { return it }
        // Before the new account's stores open: the old loop's in-memory state must not reach them.
        stopLocked()
        if (account != dmarketAccount) resetDmarketCooldown(account)
        val loop = try {
            loopFor(account, observer)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return startFault(e)
        }
        loop.mode = modeOf(foreground)
        loop.onNotarySession = ::countNotarySession
        val s = Session(account, loop)
        session = s
        mutableState.value = TrackerState(running = true, foreground = foreground, account = account).withSteamSessionFromBlock()
        if (foreground) s.driver = s.scope.launch { drive(s) }
        return s
    }

    /** A start that failed on [e] (the host's account read, a store that does not open): the next call starts again. */
    private suspend fun startFault(e: Throwable): Session? {
        emitQuietly(LifecycleEvent.CycleFailed(e.redactedSummary()))
        startFailed = true
        return null
    }

    private fun resetDmarketCooldown(account: SteamId?) {
        dmarketAccount = account
        dmarketCooldown.reset()
        permits.value = emptyMap()
    }

    private suspend fun stopLocked() {
        startFailed = false
        val s = session ?: return
        session = null
        s.job.cancelAndJoin()
        mutableState.update { TrackerState(foreground = it.foreground) }
    }

    /** Polls while on screen: a cycle, then whatever wait the loop asks for. */
    private suspend fun drive(s: Session) {
        while (true) delay(cycle(s, wake = null)?.next ?: cycleDeadline)
    }

    /**
     * One cycle in [s]'s scope, under its lock and within [cycleDeadline] (and [ends], when the host set one);
     * `null` when the session stopped under it. A [wake] or a [manual] update forces the heartbeat first: both always
     * sync, and a wake's heartbeat carries its id. The proof gate is set for this cycle, [allowProof] `false` holds
     * every proof, and [onScreen] runs it as in the foreground (the user is acting in the app) whatever the app last said.
     * [countsSessions]: its notary sessions count against the deals' update permits ([updateStatus] only).
     */
    private suspend fun cycle(
        s: Session,
        wake: Wake?,
        manual: Boolean = false,
        allowCellularProof: Boolean = false,
        onScreen: Boolean = false,
        allowProof: Boolean = true,
        ends: TimeMark? = null,
        countsSessions: Boolean = false,
    ): CycleRun? {
        val run = s.scope.async {
            locked(s, allowCellularProof, onScreen, wake, allowProof, ends, countsSessions) {
                when {
                    wake != null -> {
                        s.loop.forceHeartbeatNow()
                        s.loop.wakeFromPush(wake.signal)
                    }
                    manual -> {
                        s.loop.forceHeartbeatNow()
                        s.loop.runOnceReporting()
                    }
                    else -> s.loop.runOnceReporting()
                }
            }
        }
        return try {
            run.await()
        } catch (e: CancellationException) {
            // The caller was cancelled (rethrow) or the session stopped under it (nothing to return).
            run.cancel()
            currentCoroutineContext().ensureActive()
            null
        }
    }

    /**
     * [body] as one cycle: under [s]'s lock, with a fresh probe, the proof gate and the heartbeat's device state set,
     * within [cycleDeadline] and what is left to [ends], counted once the lock is held.
     */
    private suspend fun locked(
        s: Session,
        allowCellularProof: Boolean = false,
        onScreen: Boolean = false,
        wake: Wake? = null,
        allowProof: Boolean = true,
        ends: TimeMark? = null,
        countsSessions: Boolean = false,
        body: suspend () -> Unit,
    ): CycleRun = s.cycleLock.withLock {
        watchedAt = null
        val cycleProbe = CycleProbe().also { it.countsSessions = countsSessions }
        probe = cycleProbe
        val metered = runCatching { network.metered() }.getOrNull()
        val background = runCatching { backgroundProofs() }.getOrNull()
        val onScreenCycle = wake == null && (onScreen || foreground)
        val option = if (onScreenCycle) runCatching { cellularProofs() }.getOrNull() else background
        s.loop.proofsDeferred = !(metered == false || allowCellularProof || option == true)
        s.loop.proofsOutOfBudget = !allowProof
        s.loop.heartbeatContext = TradeTrackerLoop.HeartbeatContext(
            wakeId = wake?.wakeId,
            network = metered?.let { if (it) NetworkType.CELLULAR else NetworkType.WIFI },
            backgroundProveAllowed = background,
        )
        if (onScreen) s.loop.mode = TrackerMode.Foreground
        val limit = ends?.let { minOf(cycleDeadline, -it.elapsedNow()) } ?: cycleDeadline
        val completed = try {
            withTimeoutOrNull(limit) {
                body()
                true
            } ?: false
        } finally {
            if (onScreen) s.loop.mode = modeOf(foreground)
        }
        if (watchedAt != null && !cycleProbe.steamRateLimited) steamCooldown.answered()
        refresh(s)
        CycleRun(s.loop.nextWakeDelay(), completed, cycleProbe)
    }

    /** Reads [s]'s Steam session state into [state]; under [s]'s cycle lock. */
    private suspend fun publishSteamSession(s: Session): SteamWebSessionState {
        val steamSession = s.loop.steamSessionState()
        mutableState.update { it.copy(block = s.loop.blockingState, steamSession = steamSession).withSteamSessionFromBlock() }
        return steamSession
    }

    /** What only the loop knows, read after each cycle; deals the last heartbeat no longer tracks drop out. */
    private suspend fun refresh(s: Session) {
        val steamSession = s.loop.steamSessionState()
        val tracked = s.loop.trackedDealIds
        val checked = watchedAt
        // A deal's cooldown is its surfaces' wait: it ends no later than theirs, which an answer may have cut short.
        val surfaces = listOfNotNull(dmarketCooldown.until, steamCooldown.until).maxOrNull()
        mutableState.update { state ->
            state.copy(
                block = s.loop.blockingState,
                linkedSteamId = s.loop.linkedSteamId,
                accountMismatch = s.loop.linkedSteamIdMismatch,
                steamSession = steamSession,
                deals = tracked.associateWith { id ->
                    val deal = (state.deals[id] ?: DealTrackingState()).let {
                        it.copy(cooldownUntil = it.cooldownUntil?.let { until -> surfaces?.let { minOf(until, it) } })
                    }
                    if (checked != null) deal.copy(checkedAt = checked) else deal
                },
            ).withSteamSessionFromBlock()
        }
    }

    /** The first reason the run fell short, by what the user can do about it; `null` when it ran through. */
    private fun failureOf(dealId: DealId, loop: TradeTrackerLoop, run: CycleRun): UpdateFailure? {
        val probe = run.probe
        val block = loop.blockingState
        return when {
            !run.completed -> UpdateFailure.TIMEOUT
            probe.retryAfter != null -> UpdateFailure.RATE_LIMITED
            block == TrackerBlock.STEAM_SESSION_MISSING || block == TrackerBlock.STEAM_ACCOUNT_MISMATCH -> UpdateFailure.STEAM_SESSION
            loop.steamCredentialUnavailable -> if (loop.needsReLogin) UpdateFailure.STEAM_SESSION else UpdateFailure.STEAM
            block != TrackerBlock.NONE || !probe.heartbeat -> UpdateFailure.DMARKET
            dealId.value in probe.proofFailed -> UpdateFailure.NOTARY
            dealId.value in probe.proofUndelivered -> UpdateFailure.DMARKET
            dealId.value in probe.proofRefused -> UpdateFailure.PROOF_REFUSED
            probe.steamReadFailed -> UpdateFailure.STEAM
            dealId.value in probe.reportFailed -> UpdateFailure.DMARKET
            probe.failed -> UpdateFailure.OTHER
            else -> null
        }
    }

    /**
     * The final outcome of [wake]'s cycle (contract d455888 §6.4, §4.3), by the first reason it fell short. A proof
     * held or failed matters only on a wake that is not for a `create_offer`: a `directive` wake is never `deferred`,
     * and its proof is not its work.
     *
     * A proof wake (`prove_offer`, `prove_release`) is `done` only when no deal it names still waits on its proof
     * ([dealsAwaitingProof]) or DMarket holds that proof after the cycle. A proof it could not run is `notary` (no
     * prover, a parked one, a refusal); one still owed with nothing run for it, an offer still awaiting Steam Guard
     * included, is `cellular_not_allowed` behind a closed network gate and `other` otherwise (DMA-1066).
     */
    private fun wakeOutcomeOf(wake: Wake, loop: TradeTrackerLoop, run: CycleRun): WakeOutcome {
        val probe = run.probe
        val block = loop.blockingState
        val proofs = !wake.isDirective
        val proofWake = wake.isProof
        return when {
            !run.completed -> WakeOutcome.failed(WakeCause.BUDGET)
            block == TrackerBlock.STEAM_SESSION_MISSING || block == TrackerBlock.STEAM_ACCOUNT_MISMATCH ->
                WakeOutcome.failed(WakeCause.STEAM_SESSION)
            loop.steamCredentialUnavailable && loop.needsReLogin -> WakeOutcome.failed(WakeCause.STEAM_SESSION)
            block == TrackerBlock.DM_SESSION_MISSING -> WakeOutcome.failed(WakeCause.AUTH)
            block != TrackerBlock.NONE || !probe.heartbeat || probe.dmarketRateLimited -> WakeOutcome.failed(WakeCause.NETWORK)
            // No Steam credential this time (a refresh that failed on the network), or a leased write left undone or
            // left without Steam's answer.
            loop.steamCredentialUnavailable || probe.directiveDropped || probe.createUnanswered -> WakeOutcome.failed(WakeCause.OTHER)
            proofs && probe.proofFailed.isNotEmpty() -> WakeOutcome.failed(WakeCause.NOTARY)
            proofs && probe.proofUndelivered.isNotEmpty() -> WakeOutcome.failed(WakeCause.NETWORK)
            proofs && probe.proofOutOfBudget -> WakeOutcome.failed(WakeCause.BUDGET)
            // Ahead of the network gate, as in the mint policy: a host that cannot prove does not on Wi-Fi either.
            proofWake && (probe.proofBlocked || probe.proofRefused.isNotEmpty()) -> WakeOutcome.failed(WakeCause.NOTARY)
            proofWake && probe.proofBudgetSpent -> WakeOutcome.failed(WakeCause.BUDGET)
            proofs && probe.proofDeferred -> WakeOutcome(WakeOutcomeKind.DEFERRED, WakeCause.CELLULAR_NOT_ALLOWED)
            probe.failed || probe.steamReadFailed || probe.steamRateLimited || probe.reportFailed.isNotEmpty() ->
                WakeOutcome.failed(WakeCause.OTHER)
            proofWake && wake.dealsAwaitingProof(loop.trackedDeals).any { it.value !in probe.proofLanded } ->
                if (loop.proofsDeferred) {
                    WakeOutcome(WakeOutcomeKind.DEFERRED, WakeCause.CELLULAR_NOT_ALLOWED)
                } else {
                    WakeOutcome.failed(WakeCause.OTHER)
                }
            else -> WakeOutcome.DONE
        }
    }

    private fun statusOf(
        dealId: DealId,
        failure: UpdateFailure?,
        retries: Int,
        retryAfter: Duration? = null,
        observed: Int? = null,
    ): StatusUpdate {
        val deal = state.value.deals[dealId] ?: DealTrackingState()
        val permit = activePermit(dealId, clock.now())
        val left = permit?.let { (retries + 1 - it.sessions.coerceAtLeast(1)).coerceAtLeast(0) } ?: retries
        // Bytes made and not delivered go again with no session, so the app's Retry stays open for them.
        val resend = session?.loop?.hasUndeliveredProof(dealId) == true
        return StatusUpdate(
            deal.checkedAt,
            observed ?: deal.lastCode,
            deal.proofState,
            retryAfter,
            failure,
            nextAllowedAt = permit?.until,
            retryLeft = if (resend) left.coerceAtLeast(1) else left,
        )
    }

    private fun track(event: LifecycleEvent) {
        when (event) {
            is LifecycleEvent.HeartbeatSent -> {
                probe.heartbeat = true
                dmarketCooldown.answered()
                mutableState.update { it.copy(lastSyncAt = clock.now()) }
            }
            is LifecycleEvent.CycleCompleted -> if (event.watching > 0) watchedAt = clock.now()
            is LifecycleEvent.CycleFailed -> probe.failed = true
            is LifecycleEvent.DirectiveDropped -> probe.directiveDropped = true
            is LifecycleEvent.CreateUnanswered -> probe.createUnanswered = true
            is LifecycleEvent.RateLimited -> {
                val steam = event.surface == "steam"
                if (steam) probe.steamRateLimited = true else probe.dmarketRateLimited = true
                val wait = (if (steam) steamCooldown else dmarketCooldown).refused(clock.now(), event.retryAfterSeconds)
                probe.retryAfter = maxOf(probe.retryAfter ?: Duration.ZERO, wait)
            }
            is LifecycleEvent.SteamReadFailed -> if (event.axis == "offer" || event.axis == "history") probe.steamReadFailed = true
            is LifecycleEvent.TradeStatusReported -> deal(event.dealId) {
                it.copy(lastCode = event.steamStatusCode, proofState = ProofState.NONE)
            }
            is LifecycleEvent.TradeStatusReportFailed ->
                if ((event.dealId to event.source) !in probe.sellerProves) probe.reportFailed += event.dealId
            // The gate's deferral outlasts the withheld report it causes, which is announced after it.
            is LifecycleEvent.TradeStatusReportDeferred -> if ((event.dealId to event.source) !in probe.sellerProves) {
                deal(event.dealId) { if (it.proofState == ProofState.DEFERRED) it else it.copy(proofState = ProofState.PENDING) }
            }
            is LifecycleEvent.ProofDeferred -> {
                probe.proofDeferred = true
                deal(event.dealId) { it.copy(proofState = ProofState.DEFERRED) }
            }
            // A buyer's skip is announced before the report it lets out unproven, so that report's refusal or deferral can
            // be told apart. No proof of this device's is pending.
            is LifecycleEvent.ProofSuppressed -> if (event.reason == ProofSkipReason.SELLER_PROVES.message) {
                probe.sellerProves += event.dealId to event.source
            } else {
                when (event.reason) {
                    ProofSkipReason.WAKE_BUDGET.message -> probe.proofOutOfBudget = true
                    ProofSkipReason.BUDGET_SPENT.message -> probe.proofBudgetSpent = true
                    ProofSkipReason.ALREADY_ACCEPTED.message -> probe.proofLanded += event.dealId
                    in BLOCKED_PROOF_REASONS -> probe.proofBlocked = true
                }
                // The same bytes' refusal stands: a manual run that meets the latch says so, not success.
                if (event.reason == ProofSkipReason.ALREADY_REFUSED.message) probe.proofRefused += event.dealId
                deal(event.dealId) { it.copy(proofState = ProofState.PENDING) }
            }
            is LifecycleEvent.ProofFailed -> {
                if (event.undelivered) probe.proofUndelivered += event.dealId else probe.proofFailed += event.dealId
                deal(event.dealId) { it.copy(proofState = ProofState.PENDING) }
            }
            is LifecycleEvent.ProofSubmitted -> {
                if (event.verified) probe.proofLanded += event.dealId else probe.proofRefused += event.dealId
                deal(event.dealId) { it.copy(proofState = ProofState.NONE) }
            }
            else -> Unit
        }
    }

    private fun deal(id: String, change: (DealTrackingState) -> DealTrackingState) {
        val key = id.takeIf { it.isNotBlank() }?.let(::DealId) ?: return
        mutableState.update { it.copy(deals = it.deals + (key to change(it.deals[key] ?: DealTrackingState()))) }
    }

    private suspend fun emitQuietly(event: LifecycleEvent) {
        try {
            eventObserver.onEvent(event)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // A host observer's failure is the host's; the wake still syncs.
        }
    }

    private fun modeOf(foreground: Boolean) = if (foreground) TrackerMode.Foreground else TrackerMode.Background

    companion object {
        /** The longest one cycle runs before it is cancelled; WorkManager stops a Worker after 10 minutes. */
        val DEFAULT_CYCLE_DEADLINE: Duration = 3.minutes

        /**
         * [startDelivery]'s wait: enough for a heartbeat on a fresh loop, two `create-trade` calls 2 s apart and the Steam
         * write when each answers in seconds. A slower run answers [CreateOfferResult.CreateInFlight] and still creates.
         */
        val DEFAULT_DELIVERY_TIMEOUT: Duration = 60.seconds

        /** How long a stopped wake waits for its `failed` ack before it lets the stop through. */
        private val STOPPED_ACK_TIMEOUT: Duration = 5.seconds

        /** Retries a permit allows after its first notary session when the app does not say (DMA-1016). */
        const val DEFAULT_RETRIES: Int = 1

        /** The refresh's own 429, as against Kong's, which names no P2P code. */
        private const val REFRESH_RATE_LIMITED = "P2P_REFRESH_RATE_LIMITED"

        /** A permit the refresh's 429 closed: no session is left under it. */
        private const val EXHAUSTED = Int.MAX_VALUE

        private const val NOT_FOUND = 404

        /** A proof wake's proof that could not run here, whatever the network (DMA-1066). */
        private val BLOCKED_PROOF_REASONS = setOf(
            ProofSkipReason.NO_PROVER.message,
            ProofSkipReason.PROVER_PARKED.message,
            ProofSkipReason.FRESHNESS_RETRY_PENDING.message,
            ProofFreshness.UNBINDABLE,
        )

        private val NOT_RUNNING_CALL = DealCallError(DealCallErrorKind.NOT_RUNNING)
        private const val NOT_RUNNING_ERROR = "no tracker runs: the app's user links no Steam account, or its loop did not start"
    }
}
