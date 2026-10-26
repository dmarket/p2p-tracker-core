@file:OptIn(ExperimentalJsExport::class)

package com.dmarket.p2p.headless

import com.dmarket.p2p.headless.node.AccountDirectories
import com.dmarket.p2p.headless.prover.ForkedProofHost
import com.dmarket.p2p.headless.prover.NodeProofHost
import com.dmarket.p2p.headless.prover.NodeWasmProver
import com.dmarket.p2p.headless.prover.ProofOutcomeJson
import com.dmarket.p2p.headless.prover.ProverLimits
import com.dmarket.p2p.headless.prover.ProverSettings
import com.dmarket.p2p.headless.prover.runProverChild
import com.dmarket.p2p.tracker.adapter.host.NoOpEventObserver
import com.dmarket.p2p.tracker.adapter.host.SystemClock
import com.dmarket.p2p.tracker.config.TrackerConfig
import com.dmarket.p2p.tracker.credential.marketplace.KeyChange
import com.dmarket.p2p.tracker.credential.marketplace.TradingKeyPushResult
import com.dmarket.p2p.tracker.notary.ProofFailure
import com.dmarket.p2p.tracker.port.host.Clock
import com.dmarket.p2p.tracker.runtime.HeadlessPlatform
import com.dmarket.p2p.tracker.runtime.HeadlessProving
import com.dmarket.p2p.tracker.runtime.HeadlessTracker
import com.dmarket.p2p.tracker.runtime.TradeTrackerCore
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.promise
import kotlin.js.Promise

/**
 * The entry of a prover process: proves what the tracker that forked it sends, and exits when it closes the
 * channel. The package's `prover-child.mjs` is the script that calls it; a host never does.
 */
@JsExport
@JsName("runProverChild")
fun proverChildMain() = runProverChild(NodeWasmProver(SystemClock()))

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
 * - `prover`: with it the tracker proves, each proof in a forked prover process; without it, it makes none. A plain
 *   object of `notaryUrl` (overrides the tracker's), `maxConcurrency` (1..2, 1 by default), `proofsPerChild` (1..5,
 *   1), `threadCount` (1..16, 2), `assetsDir` (the prover's `pkg/` and `transport/`; this package's directory by
 *   default), `connectDeadlineMs` (30 000) and `sessionDeadlineMs` (240 000).
 * - `onProofOutcome({dealId, failure})`: how each proof ended, before the loop's event about it. `failure` is `null`
 *   for a proof made, else a plain object keyed by `type`, with every text in it, `summary` included, redacted.
 * - `onProverLine(stream, line)`: a prover process's output (`stdout`, `stderr`) and the host's own lines about the
 *   processes (`host`, a JSON object with `msg` and `level`), each line redacted.
 *
 * Throws on an unknown option or a value of the wrong type, naming the option and never the value. With `prover`,
 * throws "prover files not found" when the child script or the prover's files are missing.
 *
 * The handle is an empty frozen object with no prototype: it identifies the tracker and carries nothing. The
 * tracker it stands for stays inside the library, in a `WeakMap` keyed by the handle. A Kotlin object handed out
 * instead would expose its fields, private ones included, as plain JS properties, and a host's `console.log` or
 * `util.inspect` would walk from it to the held key and token.
 */
@JsExport
fun createHeadlessTracker(options: dynamic): Any =
    create(HeadlessOptions.parse(options), httpEngine = null, background = null, proverPackageRoot = null)

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
 * Stops the tracker: every cycle and wake it has in flight is cancelled, and every prover process, idle or proving,
 * is killed at once. Every later call with the handle is refused.
 */
@Suppress("NON_EXPORTABLE_TYPE")
@JsExport
fun stop(handle: Any): Promise<Unit> {
    val entry = lookup(handle) ?: return refused(IllegalArgumentException(UNKNOWN_HANDLE))
    if (!entry.stopped) {
        entry.stopped = true
        entry.proofHost?.close()
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
    return call(handle) {
        val push = tracker.pushTradingApiKey(publicKey, secretKey)
        // A new key may answer the notary's refusal of the old one: the next proof need not wait it out.
        if (push is TradingKeyPushResult.Held && push.change != KeyChange.SAME) proofHost?.keyChanged()
        push.toJs()
    }
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
 * [proverPackageRoot] stands in for this package's directory: the child script `prover-child.mjs` is taken from
 * there, and the prover's files too unless `prover.assetsDir` says otherwise.
 */
internal fun createHeadlessTrackerForTest(
    options: dynamic,
    httpEngine: HttpClientEngine,
    background: CoroutineScope? = null,
    proverPackageRoot: String? = null,
): Any = create(HeadlessOptions.parse(options), httpEngine, background, proverPackageRoot)

private fun create(options: HeadlessOptions, httpEngine: HttpClientEngine?, background: CoroutineScope?, proverPackageRoot: String?): Any {
    val clock = options.clock() ?: SystemClock()
    // Checked before anything else is built: a broken install fails here, not on the first proof.
    val proofHost = options.prover?.let { proofHost(options, it, ProverFiles.locate(proverPackageRoot, it.assetsDir), clock) }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val accounts = AccountDirectories(options.dataDir)
    // Request signing and the HTTP engine stay the platform's own: a host supplies neither.
    val platform = HeadlessPlatform(
        storageFor = { accounts.storeFor(it.value) },
        clock = clock,
        eventObserver = options.eventObserver() ?: NoOpEventObserver,
        httpEngine = httpEngine,
        proving = proofHost?.let { HeadlessProving(it, options.prover?.maxConcurrency ?: 1) },
    )
    val tracker = HeadlessTracker(
        platform = platform,
        scope = background ?: scope,
        baseUrl = options.baseUrl,
        tunables = TrackerConfig.defaults().let { it.copy(notary = it.notary.copy(notaryUrl = options.notaryUrl)) },
        steamWrites = options.steamWrites,
    )
    val handle: Any = js("Object.freeze(Object.create(null))")
    trackers.set(handle, TrackerEntry(tracker, scope, proofHost))
    return handle
}

/**
 * The host that runs each proof in a forked `prover-child.mjs`, with no argument of the library's, reporting to the
 * host's callbacks. A callback that throws is ignored: it is called from the processes' own event handlers.
 */
private fun proofHost(options: HeadlessOptions, prover: ProverOptions, files: ProverFiles, clock: Clock): NodeProofHost {
    val onLine = options.onProverLine
    val onOutcome = options.onProofOutcome
    return ForkedProofHost(
        script = files.script,
        args = emptyList(),
        settings = ProverSettings(notaryUrl = options.notaryUrl, threadCount = prover.threadCount, assetsDir = files.assetsDir),
        clock = clock,
        limits = ProverLimits(prover.connectDeadline, prover.sessionDeadline, prover.proofsPerChild),
        onLine = { _, stream, line -> onLine?.let { runCatching { it(stream, line) } } },
        onNotice = { message, warning, fields ->
            onLine?.let { runCatching { it("host", ProofOutcomeJson.notice(message, warning, fields)) } }
        },
        onOutcome = { dealId, failure -> onOutcome?.let { runCatching { it(proofOutcome(dealId, failure)) } } },
    )
}

/** `{dealId, failure}`: `null` for a proof made, else the failure as a plain object, its texts redacted. */
private fun proofOutcome(dealId: String, failure: ProofFailure?): dynamic {
    val o = plainObject()
    o.dealId = dealId
    o.failure = failure?.let { js("JSON.parse")(ProofOutcomeJson.failure(it)) }
    return o
}

/** What a handle stands for. Reachable only through [trackers], never from the handle. */
private class TrackerEntry(val tracker: HeadlessTracker, val scope: CoroutineScope, val proofHost: NodeProofHost?) {
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
