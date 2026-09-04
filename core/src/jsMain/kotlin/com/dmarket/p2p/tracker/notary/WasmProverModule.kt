package com.dmarket.p2p.tracker.notary

import com.dmarket.p2p.tracker.adapter.webext.webExtApi
import kotlinx.coroutines.await
import kotlin.js.Date
import kotlin.js.Promise
import kotlin.math.roundToInt

/**
 * One prove-and-present request handed to a [WasmProverModule]: everything needed to run a single MPC
 * issuance and build the selective-disclosure `Presentation`.
 *
 * [steamAccessToken] is the on-device Steam JWT that authenticates the proven `IEconService` read. It is
 * substituted into the [ProvenReadSpec.path]'s `{token}` slot here, at the IO edge — which is why the spec
 * itself stays credential-free — and it is withheld from the resulting proof because the spec sets
 * `revealRequestTarget = false`.
 */
data class WasmProveRequest(
    val notaryUrl: String,
    val notaryToken: String,
    val proxyBaseUrl: String,
    val spec: ProvenReadSpec,
    val steamAccessToken: String,
    val maxSentData: Int,
    val maxRecvData: Int,
    /**
     * Response bytes preprocessed for ONLINE decryption; the rest of the body is decrypted deferred. See
     * `NotaryConfig.maxRecvDataOnline` — the artifact's own default is 2 KiB, and raising it above the real
     * response size is what takes the body off the deferred path.
     */
    val maxRecvDataOnline: Int,
    /**
     * Response **records** preprocessed for online decryption, or `null` to send no key and keep the
     * artifact's own contract default. See `NotaryConfig.maxRecvRecordsOnline` for why `null` is the
     * default rather than a number: upstream documents no figure for this cap, so a guess could lower it.
     *
     * Nullable, unlike [maxRecvDataOnline] beside it, for exactly that reason — the two bound the same
     * phase but only one of them has a known default to mirror.
     */
    val maxRecvRecordsOnline: Int? = null,
    /**
     * PEM roots to verify the TARGET's certificate chain against, or `null` to keep the prover's bundled
     * Mozilla set. See `NotaryConfig.rootStorePem`: non-null is a test-fixture affordance, never production.
     */
    val rootStorePem: String? = null,
    /**
     * The Steam **web session** `cookie` header value for a cookie-authenticated request, or `null` for the
     * token-authed reads (which send no cookie at all).
     *
     * Substituted into the spec's `{cookie}` slots here, at the IO edge — which is why the spec stays
     * credential-free — and withheld from the presentation because the header's name is in
     * `ProvenReadSpec.redactRequestHeaderValues`.
     */
    val steamSessionCookie: String? = null,
    /**
     * Steam's `sessionid` anti-CSRF token for a community write, or `null` for every read.
     *
     * ⚠️ Unlike every other credential on this path, this one is **disclosed** by the resulting proof: it
     * occupies the request body, and the vendored `RevealPolicy` has no request-body control. See
     * `SESSION_ID_PLACEHOLDER`.
     */
    val steamSessionId: String? = null,
) {
    /**
     * Redacted: this request carries the Steam access token, the notary bearer token, and — for a community
     * request — the Steam web session cookie and `sessionid`.
     *
     * The `sessionid` is redacted here even though the *proof* discloses it. The two are unrelated: a proof is
     * a document a verifier receives under a policy, a log line is a string that lands in a crash reporter.
     *
     * The PEM is described rather than printed — a root certificate is public, but it is a multi-kilobyte
     * blob and pasting it into every log line of a failing proof helps nobody.
     */
    override fun toString(): String = "WasmProveRequest(notaryUrl=$notaryUrl, notaryToken=<redacted>, proxyBaseUrl=$proxyBaseUrl, " +
        "spec=$spec, steamAccessToken=<redacted>, maxSentData=$maxSentData, maxRecvData=$maxRecvData, " +
        "maxRecvDataOnline=$maxRecvDataOnline, maxRecvRecordsOnline=$maxRecvRecordsOnline, " +
        "rootStorePem=${rootStorePem?.let { "<${it.length} chars>" }}, " +
        "steamSessionCookie=${steamSessionCookie?.let { "<redacted>" }}, " +
        "steamSessionId=${steamSessionId?.let { "<redacted>" }})"
}

/**
 * The thin, injectable seam over the steam-provenance wasm prover (`client-wasm`) + its WebSocket
 * transport (`client-wasm-transport`). Split out from [WasmNotaryProver] so the orchestration logic
 * (concurrency cap, base64 wrapping) is unit-testable with a fake module — the real MPC/notary
 * handshake is exercised only in steam-provenance's own e2e harness.
 *
 * There is deliberately no `teardown()`: the wasm instance is process-wide — the ES module registry
 * keeps the namespace alive and re-initialising hands back the same instance — so dropping references
 * frees nothing. Recycling the wasm context means recycling the host's offscreen document.
 */
interface WasmProverModule {
    /** Load + initialize the wasm module and its rayon pool once (idempotent). */
    suspend fun initialize(threadCount: Int)

    /** Run one issuance and return the raw `postcard` `Presentation` bytes. */
    suspend fun prove(request: WasmProveRequest): ByteArray
}

/**
 * Production [WasmProverModule], driving the prover this library **ships with** through
 * `client-wasm-transport`'s `createProver` façade. The façade owns module instantiation, the
 * rayon-pool setup, the disposable `ProveOutput` handle, and the tsify wire conversions — this class
 * only supplies the two IO channels and translates a [ProvenReadSpec] into an issuance config +
 * reveal policy.
 *
 * Flow: `createProver(wasm)` → `initialize()` → `connectNotary` + `connectProxy` → `prove()` →
 * `present(policy)`. The MPC target is reached through the `p2p-wss-proxy` worker because a browser
 * extension can't open a raw TCP socket.
 *
 * **Module resolution — deliberately not a literal specifier.** The assets are located at runtime,
 * against the extension root, and `import()`ed lazily on the first proof.
 *
 * A literal relative specifier does not survive bundling: the bundler owns chunk layout and rewrites
 * relative paths against the emitted chunk. Vite turns `./pkg/client_wasm.js` into a
 * `../../…/node_modules/@dmarket/…` path that does not exist in a packed extension — a green build
 * with a dead prover. A runtime-computed URL is invisible to static analysis, so no consumer needs an
 * `external` rule, an alias or a stub; the extension root is the one base every bundler agrees on.
 *
 * The client's remaining duty is to ship `pkg/` and `transport/` at that root as web-accessible
 * resources — unavoidable, since an extension can only fetch what it packages. Those obligations
 * (keep `pkg/` loose, cross-origin isolation) are in `vendor/tlsn/INTEGRATION.md`.
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
 */
class BundledWasmProverModule(private val onProgress: ((String) -> Unit)? = null) : WasmProverModule {
    private var transport: dynamic = null
    private var prover: dynamic = null

    override suspend fun initialize(threadCount: Int) {
        if (prover == null) {
            val wasm = importModule(proverAssetUrl("pkg/client_wasm.js")).await()
            transport = importModule(proverAssetUrl("transport/dist/index.js")).await()
            prover = transport.createProver(wasm)
        }
        // The façade memoizes instantiation + init and releases the memo on failure, so repeat calls are
        // free and a genuine retry (e.g. after fixing COOP/COEP) still re-runs.
        prover.initialize(null, threadCount).unsafeCast<Promise<dynamic>>().await()
    }

    override suspend fun prove(request: WasmProveRequest): ByteArray {
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
        onProgress?.let { runCatching { it(issuanceLine(request)) } }

        // A browser cannot read why the upgrade failed. The notary answers 400 / 401 / 503 with real
        // meaning behind the split, but JS is handed none of it: all three, and a bare TCP close, arrive
        // identically as close code 1006. So there is deliberately no status-dependent retry here —
        // building one would mean guessing. Re-authentication is not this path's job either: the
        // credential is the same DMarket token every marketplace call carries, so a genuinely dead token
        // is caught by the existing HTTP-401 refresh and surfaces through `needsMarketplaceReLogin`.
        val notaryIo = try {
            transport.connectNotary(request.notaryUrl, request.notaryToken)
                .unsafeCast<Promise<dynamic>>().await()
        } catch (cause: Throwable) {
            // The cause carries the transport's own message and the URL, never the credential.
            throw IllegalStateException(
                "notary handshake failed for ${request.notaryUrl} — the cause is not observable from a " +
                    "browser (rejected credential, notary unavailable, or blocked upgrade all look alike)",
                cause,
            )
        }
        val out = try {
            // LAZY on purpose — the socket is dialled by the prover's first write, not here.
            //
            // Eager was measured to be the bug. The prover opens the target leg, then spends the whole MPC
            // pre-processing round with the notary before it touches it: 4.9 s on a healthy run, 23.5 s on a
            // stalling one (both measured live on 2026-08-25). The proxy's upstream drops an idle connection
            // at ~21 s — so on the slow run the channel was already gone (`code=1000 reason=upstream closed`,
            // 0 B either way) by the time the prover sent its `ClientHello`, and writing to it did not fail
            // cleanly: the wasm trapped `memory access out of bounds`. Dialling on first use makes the socket's
            // age independent of how long the notary takes, which is the only half of that race we own.
            val serverIo = lazyTargetChannel(
                onTrace = onProgress,
                connect = {
                    transport.connectProxy(request.proxyBaseUrl, spec.serverName, 443).unsafeCast<Promise<dynamic>>()
                },
            )
            try {
                prover.prove(notaryIo, serverIo, issuanceConfig(request), httpRequest(request), progressCallback())
                    .unsafeCast<Promise<dynamic>>().await()
            } finally {
                // Whether `prove` closes its channels on every path is unconfirmed upstream; closing an
                // already-closed WsIoChannel is a no-op, leaking two open sockets per failed proof is not.
                closeQuietly(serverIo)
            }
        } finally {
            closeQuietly(notaryIo)
        }

        return asByteArray(prover.present(out.attestation, out.secrets, revealPolicy(spec)))
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

/** Reinterpret the presentation `Uint8Array` as a [ByteArray] — Kotlin/JS backs `ByteArray` with `Int8Array`. */
private fun asByteArray(bytes: dynamic): ByteArray =
    js("new Int8Array(bytes.buffer, bytes.byteOffset, bytes.length)").unsafeCast<ByteArray>()

// The three wire objects handed to the wasm. Top-level and `internal` rather than private methods so
// the contract test can assert the REAL objects against the real module — a hand-copied expectation in
// a test proves only that the copy matches itself, which is exactly how the missing `revealRequestTarget`
// went unnoticed.

internal fun issuanceConfig(request: WasmProveRequest): dynamic {
    val issuance: dynamic = js("({})")
    issuance.serverName = request.spec.serverName
    issuance.maxSentData = request.maxSentData
    issuance.maxRecvData = request.maxRecvData
    // Always set, unlike `rootStore` below: this one has a numeric artifact default (2 KiB) that our config
    // mirrors, so writing it explicitly makes the effective value visible in the trace line instead of implied
    // by the artifact build. An absent key here would mean "whatever build 423 happens to default to", which is
    // the value this experiment is about.
    // Clamped, because the two caps are now set independently: a read that raises `maxRecvData` is fine, but
    // the online budget is a slice of the receive ceiling, and a value above it is a config that cannot mean
    // what it says. Upstream enforces the same invariant, so an unclamped value is a rejected issuance rather
    // than a subtle one.
    issuance.maxRecvDataOnline = minOf(request.maxRecvDataOnline, request.maxRecvData)
    // Set ONLY when configured, and NOT clamped against anything — the opposite treatment to the byte budget
    // one line up, for the reason `NotaryConfig.maxRecvRecordsOnline` gives: this cap has no documented default
    // to mirror and no stated relation to the byte caps, so an absent key means "whatever the artifact's own
    // contract default is" — which is the value every deployment before this field ran on — and a written key
    // means an operator chose a number deliberately. Clamping it against a byte count would be arithmetic
    // between two different units, which is how a config stops meaning what it says.
    request.maxRecvRecordsOnline?.let { issuance.maxRecvRecordsOnline = it }
    // Set ONLY when configured. The wasm's `RootStore` is `"mozilla" | { pem }` and an ABSENT key keeps the
    // bundled Mozilla web-PKI set — which is what production runs on, so the untouched path stays byte-for-byte
    // what every deployment before this had. Writing `"mozilla"` explicitly would be the same thing said less
    // safely: a typo in that literal is a rejected config rather than a default.
    request.rootStorePem?.let { pem ->
        val store: dynamic = js("({})")
        store.pem = pem
        issuance.rootStore = store
    }
    return issuance
}

internal fun httpRequest(request: WasmProveRequest): dynamic {
    val spec = request.spec
    val httpRequest: dynamic = js("({})")
    httpRequest.method = spec.method
    // The one place the Steam token is joined to the request. `client_core::issue` injects `Host`,
    // `Accept-Encoding: identity` and `Connection: close` itself, so those are never added here.
    httpRequest.uri = spec.path.replace(TOKEN_PLACEHOLDER, request.steamAccessToken)

    // Headers exist for the cookie-authenticated community surface, which was unreachable while this function
    // sent none: the prover accepts `headers?: [string, string][]` and nothing here ever filled it, so a read
    // authenticating by `steamLoginSecure` could not be proven at all.
    //
    // Still NO `headers` key when the spec names none — which is every token-authed read, i.e. both trade
    // axes. They authenticate by query parameter, so an absent key states "this request adds none" more
    // plainly than an empty array, and it keeps that path byte-for-byte what it was.
    if (spec.sendHeaders.isNotEmpty()) {
        val headers = js("([])")
        spec.sendHeaders.forEach { header ->
            val pair = js("([])")
            pair.push(header.name)
            pair.push(header.valueTemplate.fillCredentials(request))
            headers.push(pair)
        }
        httpRequest.headers = headers
    }
    // Same discipline for the body: absent unless the spec has one, so a GET is unchanged. A write's body is
    // the one part of a proven request the presentation cannot withhold — see `SESSION_ID_PLACEHOLDER`.
    spec.body?.let { body ->
        val bytes = js("([])")
        body.fillCredentials(request).encodeToByteArray().forEach { byte -> bytes.push(byte.toInt() and 0xFF) }
        httpRequest.body = bytes
    }
    return httpRequest
}

/**
 * Substitute the IO-edge credential slots — the two that exist so a pure [ProvenReadSpec] can describe a
 * request without holding its secrets.
 *
 * A slot with no value is left as its literal placeholder rather than replaced with `""`: an empty cookie
 * header would produce an *unauthenticated* Steam request that still looks well-formed, and the resulting
 * attestation would faithfully prove a logged-out response. `WasmNotaryProver` fails before this point when a
 * needed credential is missing, so reaching here with one absent is a bug — and a literal `{cookie}` on the
 * wire is a far louder symptom than a silent 401.
 */
private fun String.fillCredentials(request: WasmProveRequest): String {
    var filled = this
    request.steamSessionCookie?.let { filled = filled.replace(COOKIE_PLACEHOLDER, it) }
    request.steamSessionId?.let { filled = filled.replace(SESSION_ID_PLACEHOLDER, it) }
    return filled
}

/**
 * Every field of the wasm `RevealPolicy` is required — a partial policy is rejected at
 * deserialization, not defaulted — so each one is set explicitly from [spec]. That is also the point:
 * a disclosure decision must come from the pure spec, never from a default chosen here.
 */
internal fun revealPolicy(spec: ProvenReadSpec): dynamic {
    val policy: dynamic = js("({})")
    val redact = js("([])")
    spec.redactRequestHeaderValues.forEach { redact.push(it) }
    policy.redactRequestHeaderValues = redact
    policy.revealRequestTarget = spec.revealRequestTarget
    policy.revealResponseHeaders = spec.revealResponseHeaders
    // The wasm's `BodyReveal` is `"all" | "none" | { jsonPaths }`. `"none"` has no counterpart in
    // [ResponseBodyReveal] on purpose — a proof disclosing nothing of the response binds no trade — so it is
    // unreachable from here rather than reachable by an empty list, which is how the old mapping spelled it.
    val body: dynamic = when (val reveal = spec.responseBodyReveal) {
        ResponseBodyReveal.All -> "all"
        is ResponseBodyReveal.JsonPaths -> {
            val selective: dynamic = js("({})")
            val paths = js("([])")
            reveal.paths.forEach { paths.push(it) }
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
 */
internal fun issuanceLine(request: WasmProveRequest): String = buildString {
    append("issuance serverName=").append(request.spec.serverName)
    append(" proxy=").append(request.proxyBaseUrl)
    append(" rootStore=").append(request.rootStorePem?.let { "pem(${it.length} chars)" } ?: "mozilla")
    append(" maxSent=").append(request.maxSentData)
    append(" maxRecv=").append(request.maxRecvData)
    append(" maxRecvOnline=").append(request.maxRecvDataOnline)
    // Spelled as the wasm sees it, like `rootStore` above: `default` is the honest word for an absent key,
    // because the number it resolves to lives in the artifact and not in any config this line could read.
    append(" maxRecvRecordsOnline=").append(request.maxRecvRecordsOnline ?: "default")
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
 * Every field is read defensively. This object is produced by the vendored prover, so a version bump can
 * rename or drop a field (`vendor/tlsn/VERSION` pins the artifact, not the event shape) — and the failure
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

/** Absolute URL of a vendored prover asset, resolved against the extension root. */
private fun proverAssetUrl(path: String): String = webExtApi().runtime.getURL(path) as String

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
