@file:OptIn(ExperimentalJsExport::class)

package com.dmarket.p2p.tracker.runtime

import com.dmarket.p2p.tracker.adapter.host.NoOpNetworkObserver
import com.dmarket.p2p.tracker.adapter.platformScheduler
import com.dmarket.p2p.tracker.adapter.webext.WebExtAlarmsScheduler
import com.dmarket.p2p.tracker.config.TrackerConfig
import com.dmarket.p2p.tracker.engine.TrackerBlock
import com.dmarket.p2p.tracker.loop.TradeTrackerLoop
import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.DirectiveId
import com.dmarket.p2p.tracker.model.LifecycleEvent
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.TrackerMode
import com.dmarket.p2p.tracker.model.steam.TradeDraft
import com.dmarket.p2p.tracker.model.toWireJson
import com.dmarket.p2p.tracker.port.host.EventObserver
import com.dmarket.p2p.tracker.port.host.NetworkObserver
import com.dmarket.p2p.tracker.port.host.Scheduler
import com.dmarket.p2p.tracker.port.steam.CreateOfferResult
import com.dmarket.p2p.tracker.wire.parsePushEnvelope
import com.dmarket.p2p.tracker.wire.toSignalOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.promise
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlin.js.Promise

/**
 * The [TrackerHandle] returned by [startTracker]. Holds the loop + scope + alarm scheduler so the
 * free functions [stopTracker] / [deliverPush] / [createTrade] / [linkedSteamIdMismatch] can act on a
 * running tracker. JS stops it via [stopTracker] and feeds pushes via [deliverPush] (`@JsExport`
 * cannot surface methods that override a non-exported common interface, so these are free functions).
 */
/** Cycles requested within this window collapse to one (dedupes the MV3 boot + onAlarm double-fire). */
private const val CYCLE_COALESCE_MS: Double = 5_000.0

private class JsTrackerHandle(
    val loop: TradeTrackerLoop,
    val scope: CoroutineScope,
    val scheduler: Scheduler,
    val activeCount: ActiveTrackingCountChannel,
    private val onStop: () -> Unit,
) : TrackerHandle {
    override fun stop() = onStop()
}

/**
 * Adapts a JS `(eventJson) -> Unit` callback to the Kotlin [EventObserver] `suspend fun interface`
 * (which JS cannot implement directly). Each [LifecycleEvent] is serialised via [toWireJson] — a
 * secret-free JSON string (public ids / enum names / counts only). The loop wraps observer calls in
 * `runCatching`, so a throwing JS callback cannot break a cycle.
 */
private class CallbackEventObserver(private val callback: (String) -> Unit) : EventObserver {
    override suspend fun onEvent(event: LifecycleEvent) = callback(event.toWireJson())
}

/** Stops a tracker started with [startTracker] (clears the alarm + detaches listeners). */
@Suppress("NON_EXPORTABLE_TYPE")
@JsExport
fun stopTracker(handle: TrackerHandle) = handle.stop()

/**
 * Deliver a backend push **payload string** to a running tracker. The lib owns no push transport —
 * the host receives the push in its own `push` listener and calls this:
 * `self.addEventListener("push", e => e.waitUntil(deliverPush(handle, e.data.text())))`.
 *
 * Under the C1 contract a push is just a nudge: it parses the payload (ignoring unknown frames),
 * runs one (single-flighted) cycle now, then re-arms the next wake. On a live instance between
 * heartbeats that cycle is a no-heartbeat watch pass; on a FRESH instance (the MV3 norm — the push
 * usually spawns the worker) there is no cached tracking list to watch, so the nudge is honoured
 * with a heartbeat instead of a silent idle ([TradeTrackerLoop.wakeFromPush]). Returns a [Promise]
 * (not a `suspend` fun — Kotlin/JS can't export those) so the host can `await`/`waitUntil` it.
 */
@Suppress("NON_EXPORTABLE_TYPE")
@JsExport
fun deliverPush(handle: TrackerHandle, payloadJson: String): Promise<Unit> {
    val h = handle as JsTrackerHandle
    return h.scope.promise {
        val signal = parsePushEnvelope(payloadJson)?.toSignalOrNull()
        if (signal != null) h.loop.wakeFromPush(signal) else h.loop.runOnceReporting()
        h.scheduler.schedule(h.loop.nextWakeDelay())
    }
}

/**
 * Force a fresh DMarket heartbeat **now**, bypassing the backend-ttl cadence gate. Unlike [deliverPush]
 * (a cadence-respecting nudge: on a live instance between heartbeats it runs a no-heartbeat watch
 * pass — and, on a blocked state such as a Steam wrong-account, short-circuits with no network at
 * all — heartbeating only on a fresh cache-less instance), this ALWAYS marks the heartbeat due
 * ([TradeTrackerLoop.forceHeartbeatNow]) so the immediate [TradeTrackerLoop.runOnce] POSTs
 * `/heartbeat` and re-evaluates the account-binding — which lets a resolved mismatch clear. Only
 * Steam directives / deal-watch stay gated on a mismatch; the heartbeat itself is not.
 *
 * One exception to "always POSTs": the cycle needs a Steam credential *before* the heartbeat, so while
 * there is no Steam web session ([blockingReason] `"STEAM_SESSION_MISSING"`) a forced cycle
 * re-evaluates that session and returns without any DMarket traffic. It is still the right call after a
 * Steam re-login — that is exactly what clears the state.
 *
 * Intended for an explicit user-initiated "run a cycle now" (e.g. the dev debug console's force tick).
 * Runs on the tracker's own scope and is serialised behind any in-flight cycle by the loop's mutex.
 * Returns a [Promise] (Kotlin/JS can't export `suspend` funs) so the host can `await` it.
 */
@Suppress("NON_EXPORTABLE_TYPE")
@JsExport
fun forceHeartbeat(handle: TrackerHandle): Promise<Unit> {
    val h = handle as JsTrackerHandle
    return h.scope.promise {
        h.loop.forceHeartbeatNow()
        h.loop.runOnceReporting()
        h.scheduler.schedule(h.loop.nextWakeDelay())
    }
}

/**
 * FE fast-path "create trade" — creates the Steam offer for a committed deal against live Steam. The
 * **host owns the transport**: the extension's content script relays a validated `window.postMessage`
 * to its service worker, which calls this; mobile clients call it in-process. `@JsExport` cannot
 * surface `suspend` funs, so it bridges to a [Promise].
 *
 * Delegates to [TradeTrackerLoop.createTrade], which runs two guards **before any Steam write** — the
 * wrong-account check and the deal-keyed duplicate claim — and reports the outcome on `/trade-actions`.
 * The device-only Steam credential never crosses this boundary.
 *
 * **Calling this twice for one deal is safe.** The second call never reaches Steam: it replays the first
 * call's offer (`duplicate:true`), so a host that retries — or an FE that fires the request three times —
 * cannot produce more than one live trade offer per deal. Returns an outcome JSON string:
 * - `{ ok:true,  status:"needs_confirmation", steamOfferId }` — POSTed, awaiting the user's mobile confirm.
 * - `{ ok:true,  status:"created",            steamOfferId }` — live without a confirm step (rare).
 * - `{ ok:true,  status:"needs_confirmation", steamOfferId, duplicate:true }` — suppressed duplicate; the
 *   offer this device already created for the deal.
 * - `{ ok:false, status:"create_in_flight",   duplicate:true }` — suppressed duplicate that arrived while
 *   the first create was still running; await that one's result rather than retrying.
 * - `{ ok:false, status:"failed",             error, cause }` — Steam rejected the create.
 * - `{ ok:false, status:"throttled",          scope, retryAfterSeconds }` — deferred by this device's own
 *   back-pressure after an earlier refusal; nothing was sent to Steam, so it is safe to retry after the wait.
 * - `{ ok:false, status:"account_mismatch",   linkedSteamId, tokenSteamId }` — blocked before any write.
 * - `{ ok:false, error }` — a missing required argument (no create attempted).
 *
 * **`cause` is the field to branch on for a failure, never `error`.** `error` is Steam's own free-form text,
 * kept for diagnostics; `cause` is a stable enum name from
 * [com.dmarket.p2p.tracker.policy.SteamCreateFailureCause] — `COUNTERPARTY_OFFER_LIMIT`,
 * `OUTGOING_OFFER_LIMIT`, `REQUEST_RATE_LIMITED`, `TRANSPORT`, `OTHER` — classified inside the lib so every
 * host reads one refusal the same way. Treat an unrecognised value as `OTHER`, so a core that grows a cause
 * cannot be rendered as something it is not.
 */
@Suppress("NON_EXPORTABLE_TYPE")
@JsExport
fun createTrade(
    handle: TrackerHandle,
    directiveId: String,
    dealId: String,
    partnerSteamId: String,
    assetIds: Array<String>,
    tradeToken: String? = null,
    linkedSteamId: String? = null,
): Promise<String> {
    val h = handle as JsTrackerHandle
    return h.scope.promise {
        if (directiveId.isBlank()) {
            return@promise buildJsonObject {
                put("ok", false)
                put("error", "missing directive_id (FE must supply the backend's create_offer directive id)")
            }.toString()
        }
        if (dealId.isBlank()) {
            return@promise buildJsonObject {
                put("ok", false)
                put("error", "missing deal_id (the /trade-actions outcome report requires it — the backend rejects without it)")
            }.toString()
        }
        val draft = TradeDraft(
            partner = SteamId(partnerSteamId),
            assetsToGive = assetIds.filter { it.isNotBlank() }.map(::AssetId),
            tradeToken = tradeToken?.takeIf { it.isNotBlank() },
        )
        val result = h.loop.createTrade(
            DirectiveId(directiveId),
            DealId(dealId),
            draft,
            linkedSteamId = linkedSteamId?.takeIf { it.isNotBlank() }?.let(::SteamId),
        )
        // Re-arm so the loop picks up the new offer's active_tracking on its next wake (loop.createTrade
        // already opens the expedited window on NeedsConfirmation; this mirrors deliverPush's re-arm).
        h.scheduler.schedule(h.loop.nextWakeDelay())
        buildJsonObject {
            // A replayed duplicate is `ok` too: the offer it names really exists (this device created it),
            // so a caller that asked twice must not be shown a failure for its own retry.
            put(
                "ok",
                result is CreateOfferResult.NeedsConfirmation ||
                    result is CreateOfferResult.Created ||
                    result is CreateOfferResult.AlreadyCreated,
            )
            when (result) {
                is CreateOfferResult.NeedsConfirmation -> {
                    put("status", "needs_confirmation")
                    put("steamOfferId", result.offerId.value)
                }
                is CreateOfferResult.Created -> {
                    put("status", "created")
                    put("steamOfferId", result.offerId.value)
                }
                is CreateOfferResult.Failed -> {
                    put("status", "failed")
                    put("error", result.error)
                    // The coded cause, classified inside the lib against Steam's own refusal wording. This
                    // is the field a host should branch on and show: `error` is free-form third-party text
                    // that may name urls, ids or the counterparty's persona, so a host relaying it to an
                    // untrusted page (the extension's FE bridge does) must send only this.
                    put("cause", result.cause.name)
                }
                // Blocked before any Steam write: the DMarket-linked Steam account ≠ the logged-in session.
                is CreateOfferResult.AccountMismatch -> {
                    put("status", "account_mismatch")
                    put("linkedSteamId", result.linkedSteamId.value)
                    put("tokenSteamId", result.tokenSteamId.value)
                }
                // Duplicate request for a deal this device already created the offer for: answer with the
                // FIRST result (so a caller that asked twice renders the real offer, not an error) and flag
                // it, rather than writing a second live offer to Steam.
                is CreateOfferResult.AlreadyCreated -> {
                    put("status", "needs_confirmation")
                    put("steamOfferId", result.offerId.value)
                    put("duplicate", true)
                }
                // Duplicate arriving while the first create is still in flight — no offer id to replay yet.
                is CreateOfferResult.CreateInFlight -> {
                    put("status", "create_in_flight")
                    put("duplicate", true)
                }
                // Deferred before any Steam write: Steam refused a create for this partner (or the whole
                // surface) recently, so the FE can offer "try again in N seconds" instead of a dead error.
                is CreateOfferResult.Throttled -> {
                    put("status", "throttled")
                    put("scope", result.scope.name.lowercase())
                    put("retryAfterSeconds", result.retryAfterSeconds)
                }
            }
        }.toString()
    }
}

/**
 * `true` while the Steam id the backend linked to this DMarket account disagrees with the Steam id of
 * the token the client holds — a wrong-account session, with all Steam activity blocked. Recomputed on
 * every heartbeat (host polls it to answer a presence request or render a "log into the correct Steam
 * account" prompt). The held token's Steam id itself never crosses this boundary — only the boolean.
 * Fail-open: `false` until the first heartbeat, or whenever the backend reports no linked id.
 */
@Suppress("NON_EXPORTABLE_TYPE")
@JsExport
fun linkedSteamIdMismatch(handle: TrackerHandle): Boolean = (handle as JsTrackerHandle).loop.linkedSteamIdMismatch

/**
 * The single highest-priority reason the tracker is blocked, as a stable enum name the host polls to
 * render exactly one prompt, in descending precedence — most actionable and most upstream first, which
 * is also the order in which a cycle establishes them:
 * - `"DM_SESSION_MISSING"` — no usable DMarket session (token absent, or invalid and un-refreshable).
 *   Highest priority: nothing this client does works without a DMarket session, and it is the first
 *   thing every cycle checks, so it is never reported stale.
 * - `"STEAM_SESSION_MISSING"` — no authenticated Steam web session (the session cookie is gone), so no
 *   Steam credential can be acquired and the cycle stops at the credential gate.
 * - `"STEAM_ACCOUNT_MISMATCH"` — the browser is signed into a Steam account other than the linked one.
 *   Released as soon as a credential for a different account is acquired (which is why a re-login clears
 *   it without waiting on a heartbeat, and why it safely outranks `"DM_CONNECTION_ERROR"`), and persisted
 *   in between, so a respawned worker reports it rather than its own not-yet-derived all-clear.
 * - `"DM_CONNECTION_ERROR"` — the `/heartbeat` reached DMarket but failed with a non-401 status (a
 *   deterministic 4xx, or a repeated 5xx / status-less network failure). The session is fine. Lowest
 *   priority: the user cannot act on it, and the prod route answers 404 by design, so anything ranked
 *   below it would never be displayed at all.
 * - `"NONE"` — nothing is blocking.
 *
 * Hosts should treat an unrecognised value as blocked, so a core that gains a state cannot be rendered
 * as "everything is fine". This is the authoritative, precedence-aware successor to
 * [linkedSteamIdMismatch] (kept for compatibility). No credential ever crosses this boundary — only the
 * enum name.
 */
@Suppress("NON_EXPORTABLE_TYPE")
@JsExport
fun blockingReason(handle: TrackerHandle): String = (handle as JsTrackerHandle).loop.blockingState.name

/**
 * `true` when the tracker is fully operational — nothing is blocking it: the DMarket session is usable
 * and the browser is signed into the linked Steam account (i.e. [blockingReason] is `"NONE"`). The
 * positive counterpart to [blockingReason], for a host that just wants a single "is tracking live"
 * boolean (e.g. the presence pong's `is_tracking_active`) without string-matching the enum. Independent
 * of whether the *user* has activated the extension UI — that flag is host-owned. Recomputed each
 * heartbeat; fail-open `true`-vs-`false`: it reflects [blockingReason], which is `"NONE"` until the
 * first heartbeat. No credential ever crosses this boundary — only the boolean.
 */
@Suppress("NON_EXPORTABLE_TYPE")
@JsExport
fun isTrackingActive(handle: TrackerHandle): Boolean = (handle as JsTrackerHandle).loop.blockingState == TrackerBlock.NONE

/**
 * Web `actual` of [startTracker] — the MV3 self-driving driver. See [startInternal] for the driver
 * mechanics. Push delivery is the host's job via [deliverPush].
 */
@Suppress("NON_EXPORTABLE_TYPE")
@JsExport
actual fun startTracker(
    baseUrl: String,
    config: TrackerConfig,
    networkObserver: NetworkObserver,
    eventObserver: EventObserver,
): TrackerHandle = startInternal(baseUrl, config, networkObserver, eventObserver, notaryProofDelegate = null)

/**
 * Start the self-driving tracker with a JS lifecycle-event callback — the transport-owning host (the
 * web extension's service worker) subscribes here to react to loop events (e.g.
 * `LinkedSteamIdMismatch` → push an `account_mismatch` to the FE). [onEvent] receives each event as a
 * secret-free JSON string (see [toWireJson]). Otherwise identical to [startTracker]; the returned
 * handle is used with [deliverPush] / [createTrade] / [linkedSteamIdMismatch] / [stopTracker].
 *
 * The cross-platform `expect/actual startTracker` is left untouched (mobile hosts implement
 * [EventObserver] directly); this is a JS-only convenience so TS — which cannot implement a Kotlin
 * `suspend fun interface` — still gets events.
 */
@Suppress("NON_EXPORTABLE_TYPE")
@JsExport
fun startTrackerWithEvents(
    baseUrl: String = TrackerConfig.DEFAULT_DMARKET_BASE_URL,
    config: TrackerConfig = TrackerConfig.defaults(),
    onEvent: (String) -> Unit,
    notaryProofDelegate: ((String, String, String) -> Promise<String>)? = null,
): TrackerHandle = startInternal(baseUrl, config, NoOpNetworkObserver, CallbackEventObserver(onEvent), notaryProofDelegate)

/**
 * Subscribe to the **active-tracking count** of a running tracker: the live number of trades the
 * tracker is currently watching (the size of the backend's `active_tracking[]`). [onCount] is invoked
 * immediately with the current value, then again whenever it changes (identical counts are conflated).
 * Returns an unsubscribe function; call it to stop receiving updates (idempotent). Every handle carries
 * the channel, so this works regardless of whether the tracker was started via [startTracker] or
 * [startTrackerWithEvents].
 */
@Suppress("NON_EXPORTABLE_TYPE")
@JsExport
fun subscribeActiveTrackingCount(handle: TrackerHandle, onCount: (Int) -> Unit): () -> Unit {
    val h = handle as JsTrackerHandle
    val job = h.scope.launch { h.activeCount.count.collect { onCount(it) } }
    return { job.cancel() }
}

/**
 * The current number of actively-tracked trades on [handle], read synchronously — useful to render the
 * badge the instant a UI mounts, without waiting for the next [subscribeActiveTrackingCount] callback.
 * `0` before the first cycle completes.
 */
@Suppress("NON_EXPORTABLE_TYPE")
@JsExport
fun activeTrackingCount(handle: TrackerHandle): Int = (handle as JsTrackerHandle).activeCount.count.value

/**
 * The MV3 self-driving driver shared by [startTracker] and [startTrackerWithEvents].
 *
 * **Call this synchronously at the service-worker top level on every worker boot.** When an alarm
 * respawns a torn-down worker, only listeners registered during that fresh top-level evaluation
 * receive the event, so the `onAlarm` listener is attached here directly and each fire runs one
 * self-contained cycle that re-arms the **single** `chrome.alarms` entry (re-arm replaces → no
 * duplicate scheduled wakes).
 */
private fun startInternal(
    baseUrl: String,
    config: TrackerConfig,
    networkObserver: NetworkObserver,
    eventObserver: EventObserver,
    notaryProofDelegate: ((String, String, String) -> Promise<String>)?,
): TrackerHandle {
    // Decorate the host's observer with the active-count channel so subscribeActiveTrackingCount /
    // activeTrackingCount work on any handle; the host's observer still receives every lifecycle event.
    val activeCount = ActiveTrackingCountChannel(delegate = eventObserver)
    val loop = createBrowserLoop(baseUrl, config, networkObserver, activeCount, notaryProofDelegate)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val scheduler = platformScheduler(scope, TrackerMode.Background)

    val alarmName = WebExtAlarmsScheduler.DEFAULT_ALARM_NAME
    val chrome: dynamic = js("typeof chrome !== 'undefined' ? chrome : browser")

    // Run one cycle and re-arm the next wake (deal-watch cadence or next heartbeat, whichever is sooner).
    // MV3 double-cycle guard: when a repeating alarm respawns a dead worker, both this synchronous boot
    // cycle() and the freshly-registered onAlarm listener fire for the same wake (~ms apart). The
    // synchronous time-gate collapses them into one; legitimate cycles (≥ the 60s alarm floor) pass.
    var lastCycleMs = 0.0
    fun cycle() {
        val now = kotlin.js.Date.now()
        if (now - lastCycleMs < CYCLE_COALESCE_MS) return
        lastCycleMs = now
        // Fire-and-forget, so `launch` — NOT `scope.promise`. Nobody holds this result, and an unheld
        // promise turns a `scope.cancel()` ([stopTracker], i.e. the host restarting the tracker on an
        // endpoint switch or a remote-config override change) into an *unhandled* rejection: the
        // "Uncaught (in promise) Job was cancelled" the host sees in its worker console for a teardown
        // that is entirely normal. For a launched job cancellation IS normal completion.
        //
        // [TradeTrackerLoop.runOnceReporting], not `runOnce`: nobody holds this result, so a genuine failure
        // used to reach only the console's final-resort handler AND take the re-arm below down with it — one
        // throw and the tracker went quiet until the next `onStartup`/`onInstalled`. Now the failure is a
        // lifecycle event and the next wake is always armed. Cancellation still propagates (teardown must
        // not re-arm).
        scope.launch {
            loop.runOnceReporting()
            scheduler.schedule(loop.nextWakeDelay())
        }
    }

    val onAlarm: dynamic = { alarm: dynamic ->
        if (alarm.name == alarmName) {
            cycle()
        }
        Unit
    }
    val onRearm: dynamic = {
        scheduler.schedule(loop.nextWakeDelay())
        Unit
    }

    chrome.alarms.onAlarm.addListener(onAlarm)
    chrome.runtime.onStartup.addListener(onRearm)
    chrome.runtime.onInstalled.addListener(onRearm)

    // Ensure an alarm exists, but arm-if-absent only: a pending (possibly expedited) alarm from
    // before a worker death must not be clobbered — re-creating it resets its fire time to now + a
    // full (fresh-state, 3-min) period. cycle() below re-arms unconditionally after each run anyway.
    chrome.alarms.get(alarmName) { existing: dynamic ->
        if (existing == null) scheduler.schedule(loop.nextWakeDelay())
    }
    // Boot cycle: don't wait (up to the 60s alarm floor) for the first alarm. What the boot cycle
    // actually DOES is decided in-loop (see CyclePolicy): it heartbeats only when the restored
    // schedule says the heartbeat is due — a first start (no persisted schedule), a respawn past the
    // due tick, or after a failed/blocked heartbeat (those never advance the schedule). A respawn
    // INSIDE a live backend-ttl window idles, so a worker spawn alone produces no marketplace traffic.
    cycle()

    return JsTrackerHandle(loop, scope, scheduler, activeCount) {
        chrome.alarms.onAlarm.removeListener(onAlarm)
        chrome.runtime.onStartup.removeListener(onRearm)
        chrome.runtime.onInstalled.removeListener(onRearm)
        scheduler.cancel()
        scope.cancel()
    }
}
