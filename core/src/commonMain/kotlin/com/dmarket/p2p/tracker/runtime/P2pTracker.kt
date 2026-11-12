package com.dmarket.p2p.tracker.runtime

import com.dmarket.p2p.tracker.adapter.host.NoOpEventObserver
import com.dmarket.p2p.tracker.adapter.host.NoOpWakeAcknowledger
import com.dmarket.p2p.tracker.engine.TrackerBlock
import com.dmarket.p2p.tracker.loop.TradeTrackerLoop
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.DeviceId
import com.dmarket.p2p.tracker.model.LifecycleEvent
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.TrackerMode
import com.dmarket.p2p.tracker.model.Wake
import com.dmarket.p2p.tracker.port.host.Clock
import com.dmarket.p2p.tracker.port.host.DeviceIdStore
import com.dmarket.p2p.tracker.port.host.EventObserver
import com.dmarket.p2p.tracker.port.host.NetworkConditions
import com.dmarket.p2p.tracker.port.marketplace.WakeAcknowledger
import com.dmarket.p2p.tracker.wire.parsePushEnvelope
import com.dmarket.p2p.tracker.wire.toWakeOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The mobile tracker: one [TradeTrackerLoop] per Steam account, woken by pushes and, while the app is on screen, by
 * its own polling. The app owns the push transport and the UI; it calls [onPush] with each payload, [setForeground]
 * from its lifecycle, and reads [state].
 *
 * **Push model.** A wake is a visible push acknowledged on receipt (DMA-659, provisional). [onPush] acks it at once,
 * before the sync, and then always syncs: a heartbeat (`foreground: false` in the background), so the wrong-account
 * guard and the stale-claim release run on every wake. The heartbeat only says "last seen": the backend's mobile
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
 * with the user's opt-in ([cellularProofs]) or a manual [updateStatus] the user allowed it for (T-13). Otherwise the
 * proof is deferred: [LifecycleEvent.ProofDeferred], [ProofState.DEFERRED], and the decisive report that needs it is
 * not sent, nor claimed unproven, until a proof runs. Sync, reads and reports go on any network.
 *
 * @param loopFor builds the loop for an account, emitting to the observer it is given.
 * @param network what the device's network costs right now.
 * @param cellularProofs the user's "proofs on mobile data" option, read on each cycle; the host holds it and its
 *   default (off, D6).
 * @param eventObserver every lifecycle event, after the tracker's own handling.
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
) {
    private val mutableState = MutableStateFlow(TrackerState())

    /** What the tracker shows the app; see [TrackerState]. */
    val state: StateFlow<TrackerState> = mutableState.asStateFlow()

    /** Guards [session] and [foreground]: start, stop and the mode switch. */
    private val lifecycle = Mutex()
    private var session: Session? = null
    private var foreground = false

    /** Set when a cycle watched its deals; read under the session's cycle lock. */
    private var watchedAt: Instant? = null

    /** What the running cycle ran into, for [updateStatus]; replaced at each cycle's start, under its lock. */
    private var probe = CycleProbe()

    private class CycleProbe {
        var heartbeat = false
        var retryAfter: Duration? = null
        var steamReadFailed = false
        var failed = false
        val proofFailed = mutableSetOf<String>()
        val reportFailed = mutableSetOf<String>()
    }

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

    /** The app came on screen ([foreground] `true`) or left it. Only on screen does the tracker poll. */
    suspend fun setForeground(foreground: Boolean) = lifecycle.withLock {
        this.foreground = foreground
        mutableState.update { it.copy(foreground = foreground) }
        val s = session ?: return@withLock
        s.cycleLock.withLock { s.loop.mode = modeOf(foreground) }
        if (foreground) {
            if (s.driver?.isActive != true) s.driver = s.scope.launch { drive(s) }
        } else {
            s.driver?.cancel()
            s.driver = null
        }
    }

    /**
     * Handles one push payload. A known wake is acknowledged, then synced; returns `true`. An unknown `type` or an
     * unreadable payload is ignored (no ack, no sync); returns `false`. Starts the tracker first when it is not running
     * (a push that woke the process).
     */
    suspend fun onPush(payload: String): Boolean {
        val wake = parsePushEnvelope(payload)?.toWakeOrNull() ?: return false
        val acknowledged = try {
            acknowledger.acknowledge(deviceIds.current(), wake)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        }
        emitQuietly(LifecycleEvent.WakeReceived(wake.type, wake.dealId?.value, wake.proveAfter?.toString(), acknowledged))
        val s = lifecycle.withLock { session ?: startLocked() } ?: return true
        cycle(s, wake)
        return true
    }

    /**
     * The manual "Update status" (T-12): a sync, the deal's Steam status read, its report and, when the deal needs one,
     * its proof — one cycle under the same lock as every other, forced to heartbeat. It watches every tracked deal, this
     * one included. Within the cooldown a 429 left ([DealTrackingState.cooldownUntil]) it sends nothing and answers
     * [UpdateFailure.RATE_LIMITED] at once.
     *
     * @param allowCellularProof the user allowed a proof on mobile data for this update (T-13); the gate stays as it is
     *   for every other cycle.
     */
    suspend fun updateStatus(dealId: DealId, allowCellularProof: Boolean = false): StatusUpdate {
        val now = clock.now()
        state.value.deals[dealId]?.cooldownUntil?.takeIf { it > now }?.let {
            return statusOf(dealId, UpdateFailure.RATE_LIMITED, retryAfter = it - now)
        }
        val s = lifecycle.withLock { session ?: startLocked() } ?: return StatusUpdate(failure = UpdateFailure.NOT_RUNNING)
        val run = cycle(s, wake = null, manual = true, allowCellularProof) ?: return statusOf(dealId, UpdateFailure.NOT_RUNNING)
        val retryAfter = run.probe.retryAfter
        if (retryAfter != null) deal(dealId.value) { it.copy(cooldownUntil = clock.now() + retryAfter) }
        return statusOf(dealId, failureOf(dealId, s.loop, run), retryAfter, s.loop.observedCode(dealId))
    }

    /** The install's device id, for the app's device registry (DMA-540). */
    suspend fun deviceId(): DeviceId = deviceIds.current()

    /** Cancels the loop without waiting for it; the tracker can be started again. */
    fun close() {
        session?.job?.cancel()
        session = null
        mutableState.update { TrackerState(foreground = it.foreground) }
    }

    private suspend fun startLocked(): Session? {
        val account = linkedAccount() ?: run {
            stopLocked()
            return null
        }
        session?.takeIf { it.account == account }?.let { return it }
        // Before the new account's stores open: the old loop's in-memory state must not reach them.
        stopLocked()
        val loop = loopFor(account, observer)
        loop.mode = modeOf(foreground)
        val s = Session(account, loop)
        session = s
        mutableState.value = TrackerState(running = true, foreground = foreground, account = account)
        if (foreground) s.driver = s.scope.launch { drive(s) }
        return s
    }

    private suspend fun stopLocked() {
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
     * One cycle in [s]'s scope, under its lock and within [cycleDeadline]; `null` when the session stopped under it.
     * A [wake] or a [manual] update forces the heartbeat first: both always sync. The proof gate is set for this cycle.
     */
    private suspend fun cycle(s: Session, wake: Wake?, manual: Boolean = false, allowCellularProof: Boolean = false): CycleRun? {
        val run = s.scope.async {
            s.cycleLock.withLock {
                watchedAt = null
                val cycleProbe = CycleProbe()
                probe = cycleProbe
                s.loop.proofsDeferred = !proofsAllowed(allowCellularProof)
                val completed = withTimeoutOrNull(cycleDeadline) {
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
                    true
                } ?: false
                refresh(s)
                CycleRun(s.loop.nextWakeDelay(), completed, cycleProbe)
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

    /** What only the loop knows, read after each cycle; deals the last heartbeat no longer tracks drop out. */
    private fun refresh(s: Session) {
        val tracked = s.loop.trackedDealIds
        val checked = watchedAt
        mutableState.update { state ->
            state.copy(
                block = s.loop.blockingState,
                linkedSteamId = s.loop.linkedSteamId,
                accountMismatch = s.loop.linkedSteamIdMismatch,
                deals = tracked.associateWith { id ->
                    val deal = state.deals[id] ?: DealTrackingState()
                    if (checked != null) deal.copy(checkedAt = checked) else deal
                },
            )
        }
    }

    /** Whether a proof may run now: an unmetered network, the user's option, or this update's own permission. */
    private fun proofsAllowed(allowCellularProof: Boolean): Boolean {
        // No answer from the platform reads as metered, and no answer from the host as the option off.
        val metered = runCatching { network.metered() }.getOrDefault(true)
        return !metered || allowCellularProof || runCatching { cellularProofs() }.getOrDefault(false)
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
            probe.steamReadFailed -> UpdateFailure.STEAM
            dealId.value in probe.reportFailed -> UpdateFailure.DMARKET
            probe.failed -> UpdateFailure.OTHER
            else -> null
        }
    }

    private fun statusOf(dealId: DealId, failure: UpdateFailure?, retryAfter: Duration? = null, observed: Int? = null): StatusUpdate {
        val deal = state.value.deals[dealId] ?: DealTrackingState()
        return StatusUpdate(deal.checkedAt, observed ?: deal.lastCode, deal.proofState, retryAfter, failure)
    }

    private fun track(event: LifecycleEvent) {
        when (event) {
            is LifecycleEvent.HeartbeatSent -> {
                probe.heartbeat = true
                mutableState.update { it.copy(lastSyncAt = clock.now()) }
            }
            is LifecycleEvent.CycleCompleted -> if (event.watching > 0) watchedAt = clock.now()
            is LifecycleEvent.CycleFailed -> probe.failed = true
            is LifecycleEvent.RateLimited -> {
                val wait = (event.retryAfterSeconds?.coerceAtLeast(0) ?: DEFAULT_RATE_LIMIT_WAIT_SECONDS).seconds
                probe.retryAfter = maxOf(probe.retryAfter ?: Duration.ZERO, wait)
            }
            is LifecycleEvent.SteamReadFailed -> if (event.axis == "offer" || event.axis == "history") probe.steamReadFailed = true
            is LifecycleEvent.TradeStatusReported -> deal(event.dealId) {
                it.copy(lastCode = event.steamStatusCode, proofState = ProofState.NONE)
            }
            is LifecycleEvent.TradeStatusReportFailed -> probe.reportFailed += event.dealId
            // The gate's deferral outlasts the withheld report it causes, which is announced after it.
            is LifecycleEvent.TradeStatusReportDeferred -> deal(event.dealId) {
                if (it.proofState == ProofState.DEFERRED) it else it.copy(proofState = ProofState.PENDING)
            }
            is LifecycleEvent.ProofDeferred -> deal(event.dealId) { it.copy(proofState = ProofState.DEFERRED) }
            is LifecycleEvent.ProofSuppressed -> deal(event.dealId) { it.copy(proofState = ProofState.PENDING) }
            is LifecycleEvent.ProofFailed -> {
                probe.proofFailed += event.dealId
                deal(event.dealId) { it.copy(proofState = ProofState.PENDING) }
            }
            is LifecycleEvent.ProofSubmitted -> deal(event.dealId) { it.copy(proofState = ProofState.NONE) }
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

        /** The wait after a 429 that sent no `Retry-After`. */
        const val DEFAULT_RATE_LIMIT_WAIT_SECONDS: Long = 60
    }
}
