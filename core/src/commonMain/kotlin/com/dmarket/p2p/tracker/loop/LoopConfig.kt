package com.dmarket.p2p.tracker.loop

import com.dmarket.p2p.tracker.config.TrackerConfig
import com.dmarket.p2p.tracker.model.RuntimeSurface
import com.dmarket.p2p.tracker.model.TrackerMode
import kotlin.time.Duration

/**
 * Static configuration for a [TradeTrackerLoop] instance.
 *
 * @param clientVersion The semver string reported by the client (e.g. `"1.4.2"`).
 * @param surface The runtime surface (browser, iOS, Android) — drives cadence floors.
 * @param mode Foreground vs background — affects cadence floors.
 * @param tunables The host-suppliable [TrackerConfig] (cadence, backoff, endpoints, …). Defaults to
 *   [TrackerConfig.defaults]; the loop builds its [com.dmarket.p2p.tracker.policy.CadencePolicy] from
 *   `tunables.cadence` and reads `tunables.steamEndpoints` for the history poll size.
 * @param requireLinkedSteamId When `true`, the loop takes no Steam action — no directive, no deal-watch read —
 *   on a heartbeat that names no linked Steam id, and emits `LinkedSteamIdUnknown` instead; the heartbeat
 *   itself keeps running. For a host that cannot check the account any other way (a headless client acting
 *   on a token someone else pushed). `false` keeps the browser behaviour: an absent id is "unknown", never a
 *   block.
 * @param minWriteTokenLifetime When set, a leased `create_offer` is not attempted while the Steam credential has
 *   less than this left: it is deferred unreported, so its lease lapses and the backend re-leases it. For a
 *   host whose token is pushed from outside and cannot be refreshed here. `null` (the default) never defers.
 */
data class LoopConfig(
    val clientVersion: String,
    val surface: RuntimeSurface,
    val mode: TrackerMode,
    val tunables: TrackerConfig = TrackerConfig.defaults(),
    val requireLinkedSteamId: Boolean = false,
    val minWriteTokenLifetime: Duration? = null,
)
