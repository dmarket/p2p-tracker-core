@file:OptIn(ExperimentalJsExport::class)

package com.dmarket.p2p.headless

import com.dmarket.p2p.headless.node.AccountDirectories
import com.dmarket.p2p.tracker.adapter.host.NoOpEventObserver
import com.dmarket.p2p.tracker.adapter.host.SystemClock
import com.dmarket.p2p.tracker.config.TrackerConfig
import com.dmarket.p2p.tracker.runtime.HeadlessPlatform
import com.dmarket.p2p.tracker.runtime.HeadlessTracker
import com.dmarket.p2p.tracker.runtime.TradeTrackerCore
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.promise
import kotlin.js.Promise

/** The version of the library inside this package: the one the tracker reports on every heartbeat. */
@JsExport
fun headlessVersion(): String = TradeTrackerCore.VERSION

/**
 * Builds a tracker for one process and one Steam account, and returns its handle. Nothing runs until [start].
 *
 * `options` is a plain object:
 * - `dataDir` (required): where each Steam account's loop state is kept, one owner-only directory per account. The
 *   pushed secrets are never written there, or anywhere.
 * - `baseUrl`: the DMarket gateway; `defaultBaseUrl()` by default.
 * - `steamWrites`: `false` for an account that only buys: no Steam offer is created or cancelled. `true` by default.
 * - `notaryUrl`: the notary proofs go to; `defaultNotaryUrl()` by default.
 * - `onEvent(json)`: each of the loop's events, as a JSON string with no secret in it.
 * - `nowMs()`: the clock, in epoch milliseconds; the system clock by default.
 *
 * Throws on an unknown option or a value of the wrong type, naming the option and never the value.
 *
 * The handle is an empty frozen object with no prototype: it identifies the tracker and carries nothing. The
 * tracker it stands for stays inside the library, in a `WeakMap` keyed by the handle. A Kotlin object handed out
 * instead would expose its fields, private ones included, as plain JS properties, and a host's `console.log` or
 * `util.inspect` would walk from it to the held key and token.
 */
@JsExport
fun createHeadlessTracker(options: dynamic): Any = create(HeadlessOptions.parse(options), httpEngine = null, background = null)

/** Starts the driver: a cycle now, then one per wake the loop asks for. A second call does nothing. */
@JsExport
fun start(handle: Any) {
    val entry = entryOf(handle)
    if (entry.started) return
    entry.started = true
    entry.tracker.start()
}

/** Runs one cycle now, behind any cycle in flight. */
@Suppress("NON_EXPORTABLE_TYPE")
@JsExport
fun cycle(handle: Any): Promise<Unit> = call(handle) { tracker.cycle() }

/**
 * Stops the tracker: every cycle and wake it has in flight is cancelled. Every later call with the handle is
 * refused.
 */
@Suppress("NON_EXPORTABLE_TYPE")
@JsExport
fun stop(handle: Any): Promise<Unit> {
    val entry = lookup(handle) ?: return refused(IllegalArgumentException(UNKNOWN_HANDLE))
    if (!entry.stopped) {
        entry.stopped = true
        entry.scope.cancel()
    }
    return Promise.resolve(Unit)
}

/**
 * Pushes the DMarket Trading API key pair (hex). Resolves to a plain object keyed by `type`: `held` (with `change`),
 * `invalid`, `accountChanged` or `unverified`. No result and no error carries the secret key.
 */
@Suppress("NON_EXPORTABLE_TYPE")
@JsExport
fun pushTradingApiKey(handle: Any, publicKey: String, secretKey: String): Promise<dynamic> {
    if (jsTypeOf(publicKey) != "string" || jsTypeOf(secretKey) != "string") {
        return refused(IllegalArgumentException("publicKey and secretKey must be strings"))
    }
    return call(handle) { tracker.pushTradingApiKey(publicKey, secretKey).toJs() }
}

/**
 * Pushes a Steam access token. Resolves to a plain object keyed by `type`: `held`, `wrongAccount` or `invalid`. No
 * result and no error carries the token.
 */
@Suppress("NON_EXPORTABLE_TYPE")
@JsExport
fun pushSteamToken(handle: Any, accessToken: String): Promise<dynamic> {
    if (jsTypeOf(accessToken) != "string") return refused(IllegalArgumentException("accessToken must be a string"))
    return call(handle) { tracker.pushSteamToken(accessToken).toJs() }
}

/** What the tracker holds right now, as a plain object without either secret. */
@JsExport
fun trackerState(handle: Any): dynamic = entryOf(handle).tracker.state().toJs()

/**
 * A tracker on [httpEngine], for tests that answer DMarket and Steam from a mock. Not exported: a host gets only
 * the platform's engine. [background] runs the tracker's own work (the driver, the cycle a push triggers) in place
 * of the handle's scope, so a test can keep it from running at all and drive every cycle itself.
 */
internal fun createHeadlessTrackerForTest(options: dynamic, httpEngine: HttpClientEngine, background: CoroutineScope? = null): Any =
    create(HeadlessOptions.parse(options), httpEngine, background)

private fun create(options: HeadlessOptions, httpEngine: HttpClientEngine?, background: CoroutineScope?): Any {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val accounts = AccountDirectories(options.dataDir)
    // Request signing and the HTTP engine stay the platform's own: a host supplies neither.
    val platform = HeadlessPlatform(
        storageFor = { accounts.storeFor(it.value) },
        clock = options.clock() ?: SystemClock(),
        eventObserver = options.eventObserver() ?: NoOpEventObserver,
        httpEngine = httpEngine,
    )
    val tracker = HeadlessTracker(
        platform = platform,
        scope = background ?: scope,
        baseUrl = options.baseUrl,
        tunables = TrackerConfig.defaults().let { it.copy(notary = it.notary.copy(notaryUrl = options.notaryUrl)) },
        steamWrites = options.steamWrites,
    )
    val handle: Any = js("Object.freeze(Object.create(null))")
    trackers.set(handle, TrackerEntry(tracker, scope))
    return handle
}

/** What a handle stands for. Reachable only through [trackers], never from the handle. */
private class TrackerEntry(val tracker: HeadlessTracker, val scope: CoroutineScope) {
    var started = false
    var stopped = false
}

/** The JS `WeakMap`: a frozen handle can key it, unlike a Kotlin map, which hashes by writing into the key. */
private external class WeakMap<K : Any, V : Any> {
    fun get(key: K): V?

    fun set(key: K, value: V): WeakMap<K, V>
}

private val trackers = WeakMap<Any, TrackerEntry>()

private const val UNKNOWN_HANDLE = "unknown tracker handle"
private const val STOPPED = "tracker stopped"

private fun lookup(handle: Any?): TrackerEntry? = if (jsTypeOf(handle) == "object" && handle != null) trackers.get(handle) else null

private fun entryOf(handle: Any): TrackerEntry {
    val entry = lookup(handle) ?: throw IllegalArgumentException(UNKNOWN_HANDLE)
    check(!entry.stopped) { STOPPED }
    return entry
}

private fun <T> refused(error: Throwable): Promise<T> = Promise.reject(error).unsafeCast<Promise<T>>()

/** Runs [block] on the handle's scope; refuses an unknown or stopped handle. */
private fun <T> call(handle: Any, block: suspend TrackerEntry.() -> T): Promise<T> {
    val entry = try {
        entryOf(handle)
    } catch (e: Throwable) {
        return refused(e)
    }
    return entry.scope.promise { entry.block() }
}
