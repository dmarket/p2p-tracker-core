package com.dmarket.p2p.tracker.loop

/** What one [TradeTrackerLoop.runOnce] cycle should do, decided from the restored schedule state. */
internal enum class CycleAction {
    /** The heartbeat is due — run the full cycle: heartbeat → directives → watch. */
    HEARTBEAT,

    /** Between heartbeats with a live tracking list — watch the cached deals, no heartbeat. */
    WATCH_ONLY,

    /** Between heartbeats with nothing to watch — do nothing and wait for the due tick. */
    IDLE,
}

/**
 * The single place the "should this cycle heartbeat?" question is answered. The heartbeat runs on its
 * own backend-`ttl_seconds` cadence (persisted across worker respawns via
 * [LoopStateStore.nextHeartbeatAt]), NOT on every deal-watch wake: an expedited wake must speed up the
 * Steam reads without out-pacing the heartbeat floor.
 *
 * The **first start heartbeats by design** — with no persisted due-time there is no schedule to honour
 * (`heartbeatDue` defaults to `true`), and the loop cannot bootstrap without a heartbeat. After that
 * the backend orchestrates: a fresh instance whose tracking-list cache died with the previous worker
 * idles until the due tick rather than self-initiating a heartbeat (or watching from stale local
 * state), so a worker respawn alone never produces marketplace traffic. Failed and *blocked*
 * heartbeats (401 / server error / wrong-account mismatch) never advance the due-time, so after any
 * of those every wake is due again and re-evaluates — IDLE can only follow a recent successful,
 * unblocked heartbeat, which is exactly when reporting an all-clear in-memory state is truthful.
 * An explicit [TradeTrackerLoop.forceHeartbeatNow] (host nudge / debug force-tick) and a push nudge
 * on a cache-less instance ([TradeTrackerLoop.wakeFromPush]) mark the heartbeat due, outranking
 * everything.
 */
internal object CyclePolicy {
    fun decide(heartbeatDue: Boolean, hasTrackingList: Boolean): CycleAction = when {
        heartbeatDue -> CycleAction.HEARTBEAT
        hasTrackingList -> CycleAction.WATCH_ONLY
        else -> CycleAction.IDLE
    }
}
