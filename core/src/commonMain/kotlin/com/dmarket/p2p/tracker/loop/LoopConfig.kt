package com.dmarket.p2p.tracker.loop

import com.dmarket.p2p.tracker.config.TrackerConfig
import com.dmarket.p2p.tracker.model.RuntimeSurface
import com.dmarket.p2p.tracker.model.TrackerMode

/**
 * Static configuration for a [TradeTrackerLoop] instance.
 *
 * @param clientVersion The semver string reported by the client (e.g. `"1.4.2"`).
 * @param surface The runtime surface (browser, iOS, Android) — drives cadence floors.
 * @param mode Foreground vs background — affects cadence floors.
 * @param tunables The host-suppliable [TrackerConfig] (cadence, backoff, endpoints, …). Defaults to
 *   [TrackerConfig.defaults]; the loop builds its [com.dmarket.p2p.tracker.policy.CadencePolicy] from
 *   `tunables.cadence` and reads `tunables.steamEndpoints` for the history poll size.
 */
data class LoopConfig(
    val clientVersion: String,
    val surface: RuntimeSurface,
    val mode: TrackerMode,
    val tunables: TrackerConfig = TrackerConfig.defaults(),
)
