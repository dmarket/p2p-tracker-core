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
 * @param foregroundMinTokenLifetime When set, the heartbeat's `foreground` follows the Steam session instead of
 *   [mode]: `true` only while the last heartbeat named the credential's account as linked and the credential has at
 *   least this long left, so the backend counts the device present only while it can deliver. `null` (the default)
 *   reports [mode].
 * @param heartbeatWithoutCredential When `true`, a due heartbeat still goes out while no Steam credential is held —
 *   for the Steam account the last heartbeat named as linked, with `foreground: false` — and the cycle takes no
 *   Steam action. With no linked account known there is nothing to heartbeat for, and it does not. `false` (the
 *   default) keeps the browser behaviour: no Steam session, no heartbeat.
 * @param steamWrites When `false`, the loop writes no Steam offer, whatever is leased or asked: a `create_offer` or
 *   `cancel_offer` is dropped unreported (its lease lapses to a device that writes) and [TradeTrackerLoop.createTrade]
 *   refuses. For a client whose account only buys. `true` by default.
 * @param reportUnsentCreates When `false`, a `create_offer` that never reached Steam is not reported `FAILED`: one
 *   refused by the account or the buyer-role guard, or deferred (the token, a cooldown, a stopped chain — even with
 *   `SteamWriteConfig.reportThrottledWrites` on). The backend reads `FAILED` as the seller failing to deliver, and
 *   nothing was tried; unanswered, the lease lapses to a device that can. `true` (the default) keeps the browser's
 *   reporting.
 */
data class LoopConfig(
    val clientVersion: String,
    val surface: RuntimeSurface,
    val mode: TrackerMode,
    val tunables: TrackerConfig = TrackerConfig.defaults(),
    val requireLinkedSteamId: Boolean = false,
    val minWriteTokenLifetime: Duration? = null,
    val foregroundMinTokenLifetime: Duration? = null,
    val heartbeatWithoutCredential: Boolean = false,
    val steamWrites: Boolean = true,
    val reportUnsentCreates: Boolean = true,
)
