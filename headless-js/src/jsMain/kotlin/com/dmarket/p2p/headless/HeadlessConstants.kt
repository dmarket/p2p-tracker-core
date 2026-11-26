@file:OptIn(ExperimentalJsExport::class)

package com.dmarket.p2p.headless

import com.dmarket.p2p.tracker.config.NotaryConfig
import com.dmarket.p2p.tracker.config.TrackerConfig
import com.dmarket.p2p.tracker.credential.steam.SteamProxyRefusal
import com.dmarket.p2p.tracker.engine.FailedCancelKind
import com.dmarket.p2p.tracker.engine.ProofSkipReason
import com.dmarket.p2p.tracker.loop.OFFER_WRITES_DISABLED
import com.dmarket.p2p.tracker.loop.STEAM_SESSION_MISSING_DROP_REASON
import com.dmarket.p2p.tracker.loop.TOKEN_EXPIRING_DEFER_REASON
import com.dmarket.p2p.tracker.model.marketplace.DirectiveAction
import com.dmarket.p2p.tracker.net.STEAM_PROXY_MISSING_DETAIL
import com.dmarket.p2p.tracker.net.STEAM_PROXY_UNREACHABLE_REASON
import com.dmarket.p2p.tracker.notary.PROOF_TOKEN_EXPIRING_REASON
import com.dmarket.p2p.tracker.runtime.HeadlessTracker

// The fixed strings a host matches the tracker's events against, so it never keeps a copy of its own that can
// drift from the one the library emits. Functions rather than exported values: an ES module built from Kotlin
// exports a top-level property as a `{ get() }` wrapper, not as the value. Each call returns the same frozen
// object or the same primitive.

/**
 * The `reason` texts of the events that say why a write or a proof did not run:
 * - `offerWritesDisabled`: a directive dropped because the tracker was built with `steamWrites: false`;
 * - `steamSessionMissing`: a directive dropped for want of a usable Steam token;
 * - `tokenExpiringDefer`: a create deferred because the token expires before it could be confirmed;
 * - `proofTokenExpiring`: a proof not started because the token is too close to its expiry (it appears inside
 *   a longer reason);
 * - `noProver`: a proof suppressed because the tracker makes none;
 * - `steamProxyUnreachable`: a create or a cancel not sent because the Steam proxy pushed with the token could not be
 *   reached — or, with `requireSteamProxy`, because none is held;
 * - `steamProxyMissing`: with `requireSteamProxy`, a proof not started (and the `detail` of a `SteamProxyMissing` a
 *   Steam call or a proof raised) because no proxy is held.
 */
@JsExport
fun reasons(): dynamic = REASONS

private val REASONS: dynamic = frozen(
    "offerWritesDisabled" to OFFER_WRITES_DISABLED,
    "steamSessionMissing" to STEAM_SESSION_MISSING_DROP_REASON,
    "tokenExpiringDefer" to TOKEN_EXPIRING_DEFER_REASON,
    "proofTokenExpiring" to PROOF_TOKEN_EXPIRING_REASON,
    "noProver" to ProofSkipReason.NO_PROVER.message,
    "steamProxyUnreachable" to STEAM_PROXY_UNREACHABLE_REASON,
    "steamProxyMissing" to STEAM_PROXY_MISSING_DETAIL,
)

/** The `kind` of a failed-cancel check, by name: `alreadyInactive`, `alreadyTraded`, `stillOpen`, `unread`. */
@JsExport
fun failedCancelKinds(): dynamic = FAILED_CANCEL_KINDS

private val FAILED_CANCEL_KINDS: dynamic = frozen(*FailedCancelKind.entries.map { it.name.camelCase() to it.wireName }.toTypedArray())

/** The `kind` of a directive, by name: `createOffer`, `cancelOffer`, `reportInventory`, `unknown`. */
@JsExport
fun directiveActions(): dynamic = DIRECTIVE_ACTIONS

private val DIRECTIVE_ACTIONS: dynamic = frozen(*DirectiveAction.entries.map { it.name.camelCase() to it.wireName }.toTypedArray())

/** The gateway's error code for a heartbeat refused because the DMarket account links no Steam account. */
@JsExport
fun steamNotLinkedCode(): String = HeadlessTracker.STEAM_NOT_LINKED_CODE

/**
 * The `code` of the error `pushSteamToken` rejects with when its `proxy` is neither `null` nor an `http://` URL with a
 * host and a port: the rejection a host traces back to the proxy it was given rather than to its own call.
 */
@JsExport
fun steamProxyInvalidCode(): String = STEAM_PROXY_INVALID_CODE

internal const val STEAM_PROXY_INVALID_CODE = "STEAM_PROXY_INVALID"

/**
 * The `code` of the error `pushSteamToken` rejects with when it names `proxy: null` once a proxy has been held: the
 * tracker is pinned to a proxy, another may replace it, none may not. Nothing changed; a host maps it to a 400.
 */
@JsExport
fun steamProxyLockedCode(): String = SteamProxyRefusal.LOCKED.code

/**
 * The `code` of the error `pushSteamToken` rejects with, on a tracker made with `requireSteamProxy`, when the push
 * would leave no proxy held (none named with none held, or `proxy: null`). Nothing changed; a host maps it to a 400.
 */
@JsExport
fun steamProxyRequiredCode(): String = SteamProxyRefusal.REQUIRED.code

/** Under this much token life no create or proof starts, and the tracker stops claiming the device present. */
@JsExport
fun minWriteTokenLifetimeMs(): Double = HeadlessTracker.MIN_WRITE_TOKEN_LIFETIME.inWholeMilliseconds.toDouble()

/** How far this host's clock may be off DMarket's before `clockSkewed` is set. */
@JsExport
fun maxClockSkewMs(): Double = HeadlessTracker.MAX_CLOCK_SKEW.inWholeMilliseconds.toDouble()

/** The production notary: `notaryUrl`'s default. */
@JsExport
fun defaultNotaryUrl(): String = NotaryConfig.PRODUCTION_NOTARY_URL

/** The production DMarket gateway: `baseUrl`'s default. */
@JsExport
fun defaultBaseUrl(): String = TrackerConfig.DEFAULT_DMARKET_BASE_URL

private fun frozen(vararg entries: Pair<String, String>): dynamic {
    val o = plainObject()
    entries.forEach { (key, value) -> o[key] = value }
    return js("Object.freeze")(o)
}

/** `ALREADY_INACTIVE` → `alreadyInactive`. */
private fun String.camelCase(): String = lowercase().split('_').mapIndexed { i, part ->
    if (i == 0) part else part.replaceFirstChar { it.uppercase() }
}.joinToString("")
