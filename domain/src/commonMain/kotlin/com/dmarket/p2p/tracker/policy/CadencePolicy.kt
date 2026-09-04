package com.dmarket.p2p.tracker.policy

import com.dmarket.p2p.tracker.config.CadenceConfig
import com.dmarket.p2p.tracker.model.RuntimeSurface
import com.dmarket.p2p.tracker.model.TrackerMode
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * The two poll classes the loop recognises. The axis is *which Steam endpoint*, not which trade —
 * Steam's calls are account-wide batch reads, so cadence is per-endpoint, not per-trade.
 */
enum class PollClass {
    /** `GetTradeOffers` — watched deals' trade offers, deadline-driven; polled often. */
    ActiveOffer,

    /**
     * `GetTradeOffers` for a deal in a **transient** state (Steam offer state 9,
     * `CreatedNeedsConfirmation`) — a seller confirmation is expected imminently, so poll as fast as
     * the platform floor allows. Same endpoint as [ActiveOffer], just a shorter target interval.
     */
    ExpeditedOffer,

    /** `GetTradeHistory` — the transfer/revert watch; reverts are rare + non-urgent, polled sparse. */
    RevertWatch,
}

/**
 * Client-owned cadence (cadence is FE-decides). The deal-watch poll classes have FE-chosen target
 * intervals clamped up to the per-platform floor the OS can honour. The **heartbeat** runs on its own
 * cadence driven by the backend `ttl_seconds` (presence TTL = N×interval, N≥3) — the only
 * backend-dictated timing — less one poll floor of wake-grid margin, then clamped to
 * `[heartbeatFloor, maxActionDelay]`.
 *
 * All values are sourced from [cadence] (a [CadenceConfig]); use [defaults] for the in-code baseline.
 */
class CadencePolicy(private val cadence: CadenceConfig) {
    /**
     * The FE-chosen target interval per poll class: active offers polled often (~3 min, a flat
     * alarm), the revert watch polled sparse (~hourly).
     */
    fun targetInterval(pollClass: PollClass): Duration = when (pollClass) {
        PollClass.ActiveOffer -> cadence.activeOfferInterval
        PollClass.ExpeditedOffer -> cadence.expeditedOfferInterval
        PollClass.RevertWatch -> cadence.revertWatchInterval
    }

    /** Lowest poll interval the platform can sustain in this mode (OS alarm/background-wake limit). */
    fun pollFloor(surface: RuntimeSurface, mode: TrackerMode): Duration = cadence.pollFloor(surface, mode)

    /** Lowest heartbeat interval the platform can sustain in this mode. */
    fun heartbeatFloor(surface: RuntimeSurface, mode: TrackerMode): Duration = cadence.heartbeatFloor(surface, mode)

    /** The next poll delay for a class: the FE target, but never below the platform floor. */
    fun nextPollDelay(surface: RuntimeSurface, mode: TrackerMode, pollClass: PollClass): Duration =
        maxOf(pollFloor(surface, mode), targetInterval(pollClass))

    /**
     * Safety ceiling for a backend-dictated delay (the heartbeat `ttl_seconds`), so a bad or huge value
     * can never silence the client — it still checks in at least this often.
     */
    val maxActionDelay: Duration get() = cadence.maxActionDelay

    /**
     * How long to keep polling at the [PollClass.ExpeditedOffer] cadence after the window is armed — at
     * offer creation, and re-armed on every tick a transient (state-9) deal is still observed.
     */
    val expeditedWindow: Duration get() = cadence.expeditedWindow

    /**
     * The next heartbeat delay from the backend's `ttl_seconds`, less one [pollFloor] of margin and
     * clamped to `[heartbeatFloor, maxActionDelay]`; when [ttlSeconds] is `0` (unset) the client falls
     * back to its own [CadenceConfig.fallbackHeartbeatIntervalMs] (clamped the same way, but **without**
     * the margin — that interval is our own choice, not a cadence somebody else advertised) or, when that
     * is unset too, the [heartbeatFloor].
     *
     * **Why a margin, and why exactly one poll floor.** The loop cannot wake on an arbitrary instant: it
     * re-arms each cycle to `min(poll, remaining)` with the remaining time coerced up to the poll floor
     * (`TradeTrackerLoop.nextWakeDelay`), and on web `chrome.alarms` cannot do better than a minute either
     * way. So the beat does not land on this delay — it lands on the first wake at or after it, i.e. the
     * next multiple of the wake grid. Aim at exactly the advertised cadence and any grid that does not
     * divide it puts the beat *past* it: measured on dev at `ttl_seconds` 85 with deals in flight (the
     * expedited window pulls the poll grid down to 60s), beats came 109-130s apart, every period, with
     * nothing else wrong.
     *
     * One poll floor is the smallest margin that fixes it for every ttl the floor can serve, because
     * `ceil(x / g) * g < x + g` — so a target of `ttl - g` always lands strictly inside `ttl`, and a target
     * clamped back up to the heartbeat floor lands on the floor itself, which is `<= ttl` whenever the ttl
     * is servable at all. See `CadencePolicyTest` — it models the grid and asserts the invariant directly.
     *
     * That matters even though presence is `cadence×N` (N>=3) rather than the cadence itself: the margin is
     * what keeps the missed-beat allowance intact for real failures — a network blip, a respawn — instead of
     * spending one of the N on arithmetic, every single period.
     *
     * A no-op wherever the heartbeat floor already dominates: mobile background clamps to 15 min either way.
     */
    fun nextHeartbeatDelay(ttlSeconds: Int, surface: RuntimeSurface, mode: TrackerMode): Duration {
        val floor = heartbeatFloor(surface, mode)
        if (ttlSeconds <= 0) {
            if (cadence.fallbackHeartbeatIntervalMs <= 0) return floor
            return cadence.fallbackHeartbeatInterval.coerceIn(floor, maxActionDelay)
        }
        val advertised = (ttlSeconds.toLong() * 1000L).milliseconds
        return (advertised - pollFloor(surface, mode)).coerceIn(floor, maxActionDelay)
    }

    /**
     * How long to wait before honouring a push wake-up, so an inbound push can never out-pace the
     * platform's fast-poll floor. (Push is a v1.1 optimization, but the floor-gate is defined now.)
     *
     * @return [Duration.ZERO] when the wake may be honoured immediately (never run yet, or the floor
     *   has already elapsed); otherwise the remaining time until the floor is satisfied.
     */
    fun pushCoalesceDelay(now: Instant, lastRunAt: Instant?, surface: RuntimeSurface, mode: TrackerMode): Duration {
        if (lastRunAt == null) return Duration.ZERO
        val elapsed = now - lastRunAt
        val floor = pollFloor(surface, mode)
        return if (elapsed >= floor) Duration.ZERO else floor - elapsed
    }

    companion object {
        /** The cadence policy backed by [CadenceConfig] defaults (the in-code baseline). */
        fun defaults(): CadencePolicy = CadencePolicy(CadenceConfig())
    }
}
