package com.dmarket.p2p.tracker.notary

import com.dmarket.p2p.tracker.adapter.webext.webExtApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.await
import kotlinx.coroutines.withContext
import kotlin.js.Date
import kotlin.js.Promise
import kotlin.math.roundToInt

/** The request type the web prover has always taken, now shared with the native one ([ProveRequest]). */
typealias WasmProveRequest = ProveRequest

/**
 * The thin, injectable seam over the steam-provenance wasm prover (`client-wasm`) + its WebSocket
 * transport (`client-wasm-transport`). Split out from [WasmNotaryProver] so the orchestration logic
 * (concurrency cap, base64 wrapping) is unit-testable with a fake module — the real MPC/notary
 * handshake needs a live notary and is therefore not covered by this repo's tests, which is why the
 * seam exists rather than being an untested integration gap someone should close.
 *
 * There is deliberately no `teardown()`: the wasm instance is process-wide — the ES module registry
 * keeps the namespace alive and re-initialising hands back the same instance — so dropping references
 * frees nothing. Recycling the wasm context means recycling the host's offscreen document.
 */
interface WasmProverModule {
    /** Load + initialize the wasm module and its rayon pool once (idempotent). */
    suspend fun initialize(threadCount: Int)

    /** Select the issuance protocol before starting workers; old injected modules retain their setup. */
    suspend fun initialize(threadCount: Int, mode: IssuanceMode) {
        require(mode == IssuanceMode.MPC) { "this injected prover module does not support proxy issuance" }
        initialize(threadCount)
    }

    /** Old injected modules must opt into egress explicitly, never silently use a direct route. */
    suspend fun initialize(threadCount: Int, mode: IssuanceMode, transport: String) {
        require(transport == "direct") { "this injected prover module does not support egress transport" }
        initialize(threadCount, mode)
    }

    /** Run one issuance and return the raw `postcard` `Presentation` bytes. */
    suspend fun prove(request: WasmProveRequest): ByteArray
}

/**
 * Production [WasmProverModule], driving the prover this library **ships with** through
 * `client-wasm-transport`'s `createProver` or `createProxyProver` façade. The façade owns module instantiation, the
 * rayon-pool setup, the disposable `ProveOutput` handle, and the tsify wire conversions — this class
 * supplies the mode-specific IO channels and translates a [ProvenReadSpec] into an issuance config +
 * reveal policy.
 *
 * Flow: `createProver(wasm)` → `initialize()` → `connectNotary` + `connectProxy` → `prove()` →
 * `present(policy)`. By default the MPC target is reached through the `p2p-wss-proxy` worker because a
 * browser extension can't open a raw TCP socket; a host that can supplies its own connector ([host]).
 * Proxy flow: `createProxyProver(wasm)` → `initialize()` → `connectNotary` → `proveProxy()` → `present(policy)`.
 * Egress substitutes `connectEgressNotary` for `connectNotary`; issuance and disclosure stay identical.
 * Proxy capability is checked before compilation or worker startup; the target connector is never used.
 *
 * **Module resolution — deliberately not a literal specifier.** The assets are located at runtime,
 * against the extension root (or wherever [WasmProverHost.assetUrl] says), and `import()`ed lazily on the
 * first proof.
 *
 * A literal relative specifier does not survive bundling: the bundler owns chunk layout and rewrites
 * relative paths against the emitted chunk. Vite turns `./pkg/client_wasm.js` into a
 * `../../…/node_modules/@dmarket/…` path that does not exist in a packed extension — a green build
 * with a dead prover. A runtime-computed URL is invisible to static analysis, so no consumer needs an
 * `external` rule, an alias or a stub; the extension root is the one base every bundler agrees on.
 *
 * The client's remaining duty is to ship `pkg/` and `transport/` at that root as web-accessible
 * resources — unavoidable, since an extension can only fetch what it packages. Those obligations
 * (keep `pkg/` loose, cross-origin isolation) are in `core/PROVER.md`.
 *
 * @param onProgress optional trace sink for this realm: one [issuanceLine] per proof (the parameters the
 *   prover was actually handed) followed by the wasm's own stage boundaries ([progressLine]). Absent by
 *   default, which passes the wasm a null callback — byte-for-byte the previous behaviour.
 *
 *   Worth wiring in any host that can log: the two IO channels are observable from outside (a host can
 *   wrap `WebSocket`), but *which stage the prover is in* is not, and that is the fact a stalled proof
 *   turns on. Measured live on dev 2026-08-25: one proof pushed 39 MB to the notary, received the
 *   target's response, and then produced nothing at all for 170 s until the host's deadline killed it —
 *   with no callback there was no way to tell whether it died in the disclosure step, the phase-2
 *   attestation exchange, or waiting on a rayon thread that never came back. The very next attempt, on a
 *   fresh context, finished the same work in 15.8 s.
 * @param host where the assets, the compiled `.wasm`, the notary socket and the target leg come from. The
 *   default is the extension's own answer to all four; a host outside the browser replaces the ones it cannot
 *   use. See [WasmProverHost].
 */
class BundledWasmProverModule(private val onProgress: ((String) -> Unit)? = null, private val host: WasmProverHost = WasmProverHost()) :
    WasmProverModule {
    private var transport: dynamic = null
    private var prover: dynamic = null

    private var mode: IssuanceMode? = null
    private var notaryTransport: String? = null

    override suspend fun initialize(threadCount: Int) = initialize(threadCount, IssuanceMode.MPC)

    override suspend fun initialize(threadCount: Int, mode: IssuanceMode) = initialize(threadCount, mode, "direct")

    override suspend fun initialize(threadCount: Int, mode: IssuanceMode, transport: String) {
        require(transport == "direct" || transport == "egress") { "notary.transport must be direct or egress" }
        require(transport != "egress" || mode == IssuanceMode.PROXY) { "notary.transport egress requires mode proxy" }
        check(notaryTransport == null || notaryTransport == transport) { "a prover module cannot change transport after initialization" }
        check(this.mode == null || this.mode == mode) { "a prover module cannot change issuance mode after initialization" }
        if (prover == null) {
            val wasm = importModule(assetUrl("pkg/client_wasm.js")).await()
            this.transport = importModule(assetUrl("transport/dist/index.js")).await()
            ProverCapabilities(
                proxy = jsTypeOf(this.transport.createProxyProver) == "function" && jsTypeOf(wasm.proveProxy) == "function",
                egress = jsTypeOf(this.transport.connectEgressNotary) == "function",
            ).requireSupport(mode, transport)
            val compiled = host.wasmModule?.invoke()
            val bound = if (compiled == null) wasm else withCompiledModule(wasm, compiled)
            prover = if (mode == IssuanceMode.PROXY) this.transport.createProxyProver(bound) else this.transport.createProver(bound)
            this.mode = mode
            notaryTransport = transport
        }
        // The façade memoizes instantiation + init and releases the memo on failure, so repeat calls are
        // free and a genuine retry (e.g. after fixing COOP/COEP) still re-runs.
        prover.initialize(null, threadCount).unsafeCast<Promise<dynamic>>().await()
    }

    override suspend fun prove(request: WasmProveRequest): ByteArray {
        check(notaryTransport == request.transport) { "initialize the prover with the request transport before proving" }
        val mode = checkNotNull(mode) { "initialize the prover before proving" }
        val spec = request.spec
        // What this proof is ACTUALLY parameterised with, from the realm that parameterises it — emitted before
        // anything can fail, because the failures on this path are the ones where the config is the suspect.
        //
        // It exists because a regression could not be diagnosed without it: a proof died `invalid peer
        // certificate: UnknownIssuer` on a substrate whose fixture CA was proven present in the host's bundle,
        // and no artifact anywhere said whether that PEM reached `IssuanceConfig.rootStore` — or which target
        // the byte pipe had dialled, which is the other half of the same verdict (a fixture root REPLACES the
        // Mozilla set, so a fixture PEM aimed at the real `api.steampowered.com` fails identically to no PEM
        // aimed at a fixture). The host-side log can say what it SENT; only this line says what arrived.
        onProgress?.let { runCatching { it(issuanceLine(request, mode, hostTarget = host.connectTarget != null)) } }

        // A browser cannot read why the upgrade failed. The notary answers 400 / 401 / 503 with real
        // meaning behind the split, but JS is handed none of it: all three, and a bare TCP close, arrive
        // identically as close code 1006. So there is deliberately no status-dependent retry here —
        // building one would mean guessing. Re-authentication is not this path's job either: the
        // credential is the same DMarket token every marketplace call carries, so a genuinely dead token
        // is caught by the existing HTTP-401 refresh and surfaces through `needsMarketplaceReLogin`.
        val notaryIo = try {
            connectNotary(request)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Throwable) {
            // The cause carries the transport's own message and the URL, never the credential.
            throw IllegalStateException(
                "notary handshake failed for ${request.notaryUrl} — the cause is not observable from a " +
                    "browser (rejected credential, notary unavailable, or blocked upgrade all look alike)",
                cause,
            )
        }
        val out = try {
            val issued = if (mode == IssuanceMode.PROXY) {
                prover.proveProxy(notaryIo, proxyIssuanceConfig(request), httpRequest(request), progressCallback())
                    .unsafeCast<Promise<dynamic>>().await()
            } else {
                // MPC preprocessing can outlast a target socket's idle deadline. Dial only on first write.
                val serverIo = lazyTargetChannel(onTrace = onProgress, connect = { connectTarget(request) })
                try {
                    prover.prove(notaryIo, serverIo, issuanceConfig(request), httpRequest(request), progressCallback())
                        .unsafeCast<Promise<dynamic>>().await()
                } finally {
                    closeQuietly(serverIo)
                }
            }
            host.onTargetResponse?.let { inspect -> targetStatus(issued)?.let(inspect) }
            issued
        } finally {
            if (request.transport == "egress") {
                // FIN alone can wait for credits forever after failure. Abort also closes the socket and clears the session timer.
                withContext(NonCancellable) { runCatching { notaryIo.abort().unsafeCast<Promise<dynamic>>().await() } }
            } else {
                closeQuietly(notaryIo)
            }
        }

        return asByteArray(prover.present(out.attestation, out.secrets, revealPolicy(spec)))
    }

    private fun assetUrl(path: String): String = host.assetUrl?.invoke(path) ?: extensionAssetUrl(path)

    /**
     * Direct transport uses two arguments without a host socket, on purpose: that is the call the extension has always made, so its
     * path stays the transport's own default (the global `WebSocket`) rather than an explicit equivalent.
     */
    private suspend fun connectNotary(request: WasmProveRequest): dynamic {
        val webSocket = host.notaryWebSocket?.invoke()
        if (request.transport == "egress") {
            // The connector resolves only after HELLO. Own the socket during that wait too, so cancellation
            // can close it before a channel is returned (including a resolve/cancel race).
            val owner: dynamic = js("({})")
            val wrap: dynamic =
                js("(function (WS, owner) { return function (url, protocols) { return owner.socket = new WS(url, protocols); }; })")
            val options: dynamic = js("({})")
            options.WebSocketImpl = wrap(webSocket ?: js("globalThis.WebSocket"), owner)
            try {
                return transport.connectEgressNotary(request.notaryUrl, request.notaryToken, options)
                    .unsafeCast<Promise<dynamic>>().await()
            } catch (cause: Throwable) {
                runCatching { owner.socket?.close() }
                throw cause
            }
        }
        if (webSocket == null) {
            return transport.connectNotary(request.notaryUrl, request.notaryToken).unsafeCast<Promise<dynamic>>().await()
        }
        val options: dynamic = js("({})")
        options.WebSocketImpl = webSocket
        return transport.connectNotary(request.notaryUrl, request.notaryToken, options).unsafeCast<Promise<dynamic>>().await()
    }

    private fun connectTarget(request: WasmProveRequest): Promise<dynamic> {
        val serverName = request.spec.serverName
        val hosted = host.connectTarget
        return if (hosted != null) {
            hosted(serverName, TARGET_PORT).unsafeCast<Promise<dynamic>>()
        } else {
            transport.connectProxy(request.proxyBaseUrl, serverName, TARGET_PORT).unsafeCast<Promise<dynamic>>()
        }
    }

    /** Best-effort: a close failure must not mask the prove error we may be unwinding from. */
    private suspend fun closeQuietly(channel: dynamic) {
        runCatching { channel.close().unsafeCast<Promise<dynamic>>().await() }
    }

    /**
     * The wasm's `progress_cb`, or `null` when no sink was supplied.
     *
     * `runCatching` is not defensive habit: this lambda is invoked BY the wasm, mid-issuance, so a throwing
     * sink would unwind through a wasm frame — which is how a logging bug becomes a trapped prover instance
     * the host then has to recycle. A dropped trace line is the strictly better failure.
     */
    private fun progressCallback(): dynamic = onProgress?.let { sink ->
        { event: dynamic ->
            runCatching { sink(progressLine(event)) }
            Unit
        }
    }
}

/** The target's HTTP status from a `ProveOutput`, or `null` when this build of the façade does not carry one. */
private fun targetStatus(issued: dynamic): Int? {
    val status = issued?.response?.status
    return if (jsTypeOf(status) == "number") status.unsafeCast<Int>() else null
}

/** Reinterpret the presentation `Uint8Array` as a [ByteArray] — Kotlin/JS backs `ByteArray` with `Int8Array`. */
private fun asByteArray(bytes: dynamic): ByteArray =
    js("new Int8Array(bytes.buffer, bytes.byteOffset, bytes.length)").unsafeCast<ByteArray>()

// The wire objects handed to the wasm. Top-level and `internal` rather than private methods so the contract test
// can assert the REAL objects against the real module — a hand-copied expectation in a test proves only that the
// copy matches itself, which is exactly how the missing `revealRequestTarget` went unnoticed. Every value comes
// from the typed commonMain wire (`ProverWire.kt`), which the native prover maps too; these only build objects.

internal fun issuanceConfig(request: WasmProveRequest): dynamic {
    val mpc = request.mpcIssuance()
    val issuance: dynamic = js("({})")
    issuance.serverName = mpc.serverName
    issuance.maxSentData = mpc.maxSentData
    issuance.maxRecvData = mpc.maxRecvData
    // Always set: it has a numeric artifact default (2 KiB) our config mirrors, so the effective value shows in the
    // trace line instead of being implied by the artifact build.
    issuance.maxRecvDataOnline = mpc.maxRecvDataOnline
    // Set ONLY when configured: an absent key keeps the artifact's own contract default.
    mpc.maxRecvRecordsOnline?.let { issuance.maxRecvRecordsOnline = it }
    // Set ONLY when configured: the wasm's `RootStore` is `"mozilla" | { pem }`, and an absent key keeps the bundled
    // Mozilla set production runs on.
    mpc.rootStorePem?.let { issuance.rootStore = pemRootStore(it) }
    return issuance
}

/** Proxy has nested plaintext limits and no MPC preprocessing knobs or client-selected route. */
internal fun proxyIssuanceConfig(request: WasmProveRequest): dynamic {
    val proxy = request.proxyIssuance()
    val config: dynamic = js("({})")
    config.serverName = proxy.serverName
    val limits: dynamic = js("({})")
    limits.maxSentData = proxy.maxSentData
    limits.maxRecvData = proxy.maxRecvData
    config.limits = limits
    proxy.rootStorePem?.let { config.rootStore = pemRootStore(it) }
    return config
}

private fun pemRootStore(pem: String): dynamic {
    val store: dynamic = js("({})")
    store.pem = pem
    return store
}

internal fun httpRequest(request: WasmProveRequest): dynamic {
    val http = request.proverHttpRequest()
    val httpRequest: dynamic = js("({})")
    httpRequest.method = http.method
    httpRequest.uri = http.uri
    // NO `headers` key when the read sends none (every token-authed read, both trade axes included): an absent key
    // states "this request adds none" more plainly than an empty array, and keeps that path byte-for-byte as it was.
    if (http.headers.isNotEmpty()) {
        val headers = js("([])")
        http.headers.forEach { (name, value) ->
            val pair = js("([])")
            pair.push(name)
            pair.push(value)
            headers.push(pair)
        }
        httpRequest.headers = headers
    }
    // Same for the body: absent unless the read has one. A write's body is the one part of a proven request the
    // presentation cannot withhold — see `SESSION_ID_PLACEHOLDER`.
    http.body?.let { body ->
        val bytes = js("([])")
        body.encodeToByteArray().forEach { byte -> bytes.push(byte.toInt() and 0xFF) }
        httpRequest.body = bytes
    }
    return httpRequest
}

/**
 * Every field of the wasm `RevealPolicy` is required — a partial policy is rejected at deserialization, not
 * defaulted — so each one is set explicitly from the spec's [ProverRevealPolicy].
 */
internal fun revealPolicy(spec: ProvenReadSpec): dynamic {
    val reveal = spec.proverRevealPolicy()
    val policy: dynamic = js("({})")
    val redact = js("([])")
    reveal.redactRequestHeaderValues.forEach { redact.push(it) }
    policy.redactRequestHeaderValues = redact
    policy.revealRequestTarget = reveal.revealRequestTarget
    policy.revealResponseHeaders = reveal.revealResponseHeaders
    // The wasm's `BodyReveal` is `"all" | "none" | { jsonPaths }`. `"none"` has no counterpart in
    // [ResponseBodyReveal] on purpose: a proof disclosing nothing of the response binds no trade.
    val body: dynamic = when (val body = reveal.responseBody) {
        ResponseBodyReveal.All -> "all"
        is ResponseBodyReveal.JsonPaths -> {
            val selective: dynamic = js("({})")
            val paths = js("([])")
            body.paths.forEach { paths.push(it) }
            selective.jsonPaths = paths
            selective
        }
    }
    policy.revealResponseBody = body
    return policy
}

/**
 * An [IoChannel] for the proof's target leg that dials on **first write** instead of up front.
 *
 * Three methods, same contract as the transport's `WsIoChannel` — `read()` resolving `null` for EOF,
 * `write()` resolving once the bytes are queued, `close()` idempotent — with the connection deferred.
 *
 * **Why write and not read.** TLS is client-first: nothing can arrive on this leg before the `ClientHello`
 * goes out, so a `read()` issued before that cannot be missing any data, and it parks until the dial happens
 * (exactly as it would park on an open-but-silent socket). Live traces confirm the prover writes first on
 * every attempt. If a future prover ever *read* first and awaited it before writing, that would deadlock —
 * the tell is a proof sitting at `CONNECTING_TO_SERVER` with no `ws#N CONNECTING` line for the proxy at all,
 * and the host's liveness watch bounds it rather than the 180 s deadline.
 *
 * **Failure shape changed, deliberately.** An unreachable proxy used to reject before the wasm was entered;
 * now it rejects the wasm's first write, which is a worse error surface (see the trap above). That is what
 * [onTrace] is for: the dial is narrated, so "the proxy was never reachable" stays distinguishable from "the
 * prover died on a live channel" without reading the wasm's mind.
 */
internal fun lazyTargetChannel(connect: () -> Promise<dynamic>, onTrace: ((String) -> Unit)? = null): dynamic {
    val trace: (String) -> Unit = { line -> onTrace?.let { sink -> runCatching { sink(line) } } }
    // Resolved with the live channel on the first write — or with `null` when this channel is closed before
    // anything was ever sent, which is what turns a parked `read()` into an EOF instead of a hang.
    //
    // `Any?` rather than `dynamic` only because Kotlin refuses `dynamic` as a type ARGUMENT here; every use
    // site casts straight back.
    var settle: ((Any?) -> Unit)? = null
    var fail: ((Throwable) -> Unit)? = null
    val ready = Promise<Any?> { resolve, reject ->
        settle = resolve
        fail = reject
    }
    // The executor above ran synchronously, so both are set; named locals so the closures below carry no `!!`.
    val resolveReady = checkNotNull(settle)
    val rejectReady = checkNotNull(fail)
    var dialled = false
    var closed = false

    val channel: dynamic = js("({})")

    channel.write = fun(data: dynamic): dynamic {
        if (closed) {
            return Promise.reject(IllegalStateException("target channel was written after close"))
        }
        if (!dialled) {
            dialled = true
            val startedAt = Date.now()
            trace("target dial — connecting")
            connect().then<Any?>(
                { io ->
                    trace("target dialled in ${(Date.now() - startedAt).toInt()}ms")
                    resolveReady(io)
                    io
                },
                { cause ->
                    // Named here because the rejection's next stop is inside the wasm, which reports it as
                    // whatever it makes of a failed write.
                    trace("target dial FAILED after ${(Date.now() - startedAt).toInt()}ms — ${cause.message}")
                    rejectReady(cause)
                    null
                },
            )
        }
        // Chained rather than awaited so writes issued back-to-back keep their order: `then` callbacks run in
        // registration order, and each one calls the underlying `write` synchronously.
        return ready.then<Any?> { io -> io.asDynamic().write(data) }
    }
    channel.read = { ready.then<Any?> { io -> if (io == null) null else io.asDynamic().read() } }
    channel.close = {
        closed = true
        if (dialled) {
            ready.then<Any?> { io -> if (io == null) null else io.asDynamic().close() }
        } else {
            // Never dialled: there is nothing to close, and anything parked on a read is at EOF.
            resolveReady(null)
            Promise.resolve<Any?>(null)
        }
    }
    return channel
}

/**
 * The resolved issuance parameters of one proof, as a single trace line.
 *
 * **Redaction is the whole design.** Two credentials are in scope here and neither may appear: the Steam JWT
 * (in `spec.path`'s `{token}` slot, substituted only at the IO edge) and the notary bearer token. So this line
 * names the PATH TEMPLATE rather than the request URI — the template still carries the `{token}` placeholder,
 * which is exactly the fact worth seeing, since a template with no slot is a read that would go
 * unauthenticated. The root store is reported as a length, never bytes: a CA certificate is public, but a
 * multi-kilobyte blob in every proof's log helps nobody.
 *
 * `rootStore` is spelled as the wasm sees it (`mozilla` when absent), because that IS the disclosure-relevant
 * fact: a PEM REPLACES the Mozilla set rather than extending it.
 *
 * [hostTarget] says the target leg is the host's own connector ([WasmProverHost.connectTarget]), so the line
 * reports `target=host` instead of a proxy URL that nothing dials.
 */
internal fun issuanceLine(request: WasmProveRequest, mode: IssuanceMode = IssuanceMode.MPC, hostTarget: Boolean = false): String =
    buildString {
        append("issuance serverName=").append(request.spec.serverName)
        append(" mode=").append(mode.wireName)
        when {
            request.transport == "egress" -> append(" transport=egress target=worker")
            mode == IssuanceMode.PROXY -> append(" target=notary")
            hostTarget -> append(" target=host")
            else -> append(" proxy=").append(request.proxyBaseUrl)
        }
        append(" rootStore=").append(request.rootStorePem?.let { "pem(${it.length} chars)" } ?: "mozilla")
        append(" maxSent=").append(request.maxSentData)
        append(" maxRecv=").append(request.maxRecvData)
        if (mode == IssuanceMode.MPC) append(" maxRecvOnline=").append(request.maxRecvDataOnline)
        // Spelled as the wasm sees it, like `rootStore` above: `default` is the honest word for an absent key,
        // because the number it resolves to lives in the artifact and not in any config this line could read.
        if (mode == IssuanceMode.MPC) append(" maxRecvRecordsOnline=").append(request.maxRecvRecordsOnline ?: "default")
        // Stated separately because a host log pipeline will redact the param VALUE — the extension's scrubber
        // rewrites every `access_token=…` it sees, and that eats the literal `{token}` too. So the path alone
        // cannot answer "does this template still have its credential slot?", which is the question that matters:
        // a template with no slot is a read that would go out UNAUTHENTICATED, and the core's own guard rejects
        // it. This flag survives redaction.
        append(" tokenSlot=").append(if (request.spec.needsAccessToken) "present" else "MISSING")
        // Same reasoning as `tokenSlot`, for the credentials a community request adds: a host scrubber rewrites
        // cookie-shaped values, so the filled header cannot answer "did this request carry a session at all?" —
        // and for a cookie-authed read that is the difference between a proof and an attested 401 page.
        if (request.spec.needsSessionCookie) {
            append(" cookieSlot=").append(if (request.steamSessionCookie != null) "filled" else "MISSING")
        }
        if (request.spec.needsSessionId) {
            append(" sessionIdSlot=").append(if (request.steamSessionId != null) "filled" else "MISSING")
        }
        // Byte count, never content: a write body is disclosed by the proof but that is no reason to put a CSRF
        // token in a log. The size is what diagnoses an exceeded `maxSentData`, which fails every proof for a read.
        request.spec.body?.let { append(" bodyBytes=").append(it.length) }
        append(" ").append(request.spec.method).append(" ").append(request.spec.path)
    }

/**
 * Render one wasm `ProveProgress` — `{ step, progress, message, source }` — as a single trace line.
 *
 * Formatted here rather than handed over as an object, because the sink's whole job is to log it and a
 * `dynamic` crossing a host boundary is a shape nobody can typecheck. `source` is dropped: it is always
 * `"wasm"` on this path.
 *
 * Every field is read defensively. This object is produced by the pinned prover, so a version bump can
 * rename or drop a field (`TlsnProverPin` in build-logic pins the release, not the event shape) — and the failure
 * mode of a naive read is a `TypeError` thrown from inside a wasm-invoked callback, i.e. exactly the trap
 * this call site exists to help diagnose.
 */
internal fun progressLine(event: dynamic): String {
    val step = dynString(event?.step)?.takeIf { it.isNotBlank() } ?: "(unnamed stage)"
    // A percentage only when the number is one: `roundToInt` THROWS on NaN, and an absent field reads as
    // `undefined`, so both have to be excluded before the conversion rather than after it.
    val pct = dynNumber(event?.progress)
        ?.takeIf { it.isFinite() }
        ?.let { " ${(it * 100).roundToInt()}%" }
        ?: ""
    val message = dynString(event?.message)?.takeIf { it.isNotBlank() }
    return "stage $step$pct" + (message?.let { " — $it" } ?: "")
}

private fun dynString(value: dynamic): String? = if (jsTypeOf(value) == "string") value.unsafeCast<String>() else null

private fun dynNumber(value: dynamic): Double? = if (jsTypeOf(value) == "number") value.unsafeCast<Double>() else null

/** Absolute URL of a prover asset, resolved against the extension root. */
private fun extensionAssetUrl(path: String): String = webExtApi().runtime.getURL(path) as String

/** The proven reads are HTTPS; the target leg always dials 443. */
private const val TARGET_PORT = 443

/**
 * The `pkg/` namespace with its default-export init bound to an already compiled module.
 *
 * The transport façade instantiates the module by calling `wasm.default()` with no argument, which makes the
 * wasm-pack init fetch `client_wasm_bg.wasm` relative to `import.meta.url`. A host that cannot fetch that URL
 * (Node refuses `file:`) compiles the bytes itself, and this hands them to the same init as `module_or_path`.
 * A copy rather than an edit, because a module namespace is frozen; every other export is the original
 * function, and the wasm-bindgen glue keeps its state at module scope, not on `this`.
 */
private fun withCompiledModule(wasm: dynamic, compiled: Any): dynamic {
    val init = wasm.default
    val bound: dynamic = js("Object.assign({}, wasm)")
    bound.default = {
        val input: dynamic = js("({})")
        input.module_or_path = compiled
        init(input)
    }
    return bound
}

/**
 * `import()` a URL known only at runtime — the whole point being that a variable specifier is one no
 * bundler can resolve, rewrite or inline, so no consumer has to configure around it.
 *
 * The `@vite-ignore` marker helps only in a development build: the production minifier strips
 * comments, so what a consumer actually bundles is a bare `import(url)`. Vite and Rollup emit a
 * "cannot be analyzed" **warning** for that and then leave it alone, which is the behaviour we want —
 * expect the warning downstream rather than treating it as a defect.
 */
private fun importModule(url: String): Promise<dynamic> = js("import(/* @vite-ignore */ url)").unsafeCast<Promise<dynamic>>()
