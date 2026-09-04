@file:OptIn(ExperimentalJsExport::class)

package com.dmarket.p2p.tracker.runtime

import com.dmarket.p2p.tracker.config.TrackerConfig
import com.dmarket.p2p.tracker.game.Cs2GameAdapter
import com.dmarket.p2p.tracker.notary.BundledWasmProverModule
import com.dmarket.p2p.tracker.notary.NotaryProofRequest
import com.dmarket.p2p.tracker.notary.WasmNotaryProver
import com.dmarket.p2p.tracker.notary.WebExtSteamProofCookieSource
import com.dmarket.p2p.tracker.notary.toBinding
import com.dmarket.p2p.tracker.notary.toKind
import com.dmarket.p2p.tracker.notary.toSubjectSteamId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise
import kotlinx.serialization.json.Json
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlin.js.Promise

/**
 * Run one TLSN proof **in the context that can actually host the prover**, and return the base64
 * `Presentation`.
 *
 * The tracker loop runs in an MV3 service worker, which cannot run this: the prover's rayon pool calls
 * `new Worker(…)` — not exposed in `ServiceWorkerGlobalScope` — and Chrome documents cross-origin
 * isolation as "not fully implemented" for service workers. So the host relays the loop's request to an
 * **offscreen document** and calls this there. The wasm module and its `SharedArrayBuffer` are created
 * and stay inside that document; nothing tries to hand a `SharedArrayBuffer` across the boundary, which
 * Chrome does not reliably support anyway.
 *
 * The hosting document must be cross-origin isolated — manifest `cross_origin_embedder_policy:
 * {"value":"require-corp"}` + `cross_origin_opener_policy: {"value":"same-origin"}` — and `pkg/` plus
 * `transport/` must be shipped at the extension root as web-accessible resources. See
 * `vendor/tlsn/INTEGRATION.md`.
 *
 * @param requestJson the payload produced by the loop's delegating prover; **carries no credential**,
 *   only the deal/offer/asset ids, the axis, and the public `subjectSteamId`.
 * @param notaryToken the live DMarket access token, which authenticates this client **to the notary**. Passed
 *   in rather than re-derived here on purpose: the service worker owns token refresh, and a second refresh
 *   authority racing it over a rotating credential is the failure this codebase avoids everywhere else.
 * @param steamAccessToken the Steam JWT that authenticates the **proven read** to `api.steampowered.com` —
 *   a different credential for a different party, hence a separate argument rather than one of the two being
 *   reused. It is joined to the request only at the IO edge and withheld from the presentation (the spec
 *   sets `revealRequestTarget = false`), so it stays device-only: the notary sees ciphertext and DMarket
 *   never sees it at all.
 * @param config tunables; only [TrackerConfig.notary] and [TrackerConfig.game] are read. **Required**, and
 *   it stays required now that `notaryUrl` has a default — if anything, more so. This argument used to be
 *   optional, so a host that simply forgot it compiled, silently ran on [TrackerConfig.defaults] (whose
 *   `notaryUrl` was then `null`) and failed every single proof: exactly what shipped in
 *   `dmarket-p2p-extension`, and the reason the notary had never produced a proof from a browser. The same
 *   omission today would not fail loudly at all — it would prove against the PRODUCTION notary from
 *   whatever context forgot to say otherwise, which is worse. A missing argument is a compile error in
 *   Kotlin and a type error in TypeScript.
 * @param onProgress optional sink for the prover's stage boundaries, one preformatted line per call (see
 *   `BundledWasmProverModule`). Optional so an existing 4-argument call still compiles, but a host with any
 *   log at all should pass it: the stage a stalled proof last reached is the one fact about a wedged prover
 *   that no amount of watching the sockets can recover, and a wedge here costs the host's whole proof
 *   deadline (180 s in the reference extension). The sink is called from inside the wasm — keep it cheap,
 *   and it must not assume any particular realm: on web this runs in a dedicated worker, not the document.
 */
@Suppress("NON_EXPORTABLE_TYPE")
@JsExport
fun proveNotaryTransition(
    requestJson: String,
    notaryToken: String,
    steamAccessToken: String,
    config: TrackerConfig,
    onProgress: ((String) -> Unit)? = null,
): Promise<String> = offscreenScope.promise {
    val request = Json.decodeFromString<NotaryProofRequest>(requestJson)
    val prover = WasmNotaryProver(
        config = config.notary,
        tokenProvider = { notaryToken },
        module = BundledWasmProverModule(onProgress),
        adapter = Cs2GameAdapter(config.game),
        // Constructed here, not passed in: the Steam web session lives in the extension's cookie jar, which
        // this document can read directly. See `SteamProofCookieSource` — resolving it locally is what keeps it
        // out of `requestJson` and off the delegate's parameter list.
        cookies = WebExtSteamProofCookieSource(),
    )
    prover.prove(request.toBinding(), request.toKind(), request.toSubjectSteamId(), steamAccessToken).proofPayload
}

/**
 * The offscreen document is torn down as a whole when the host recycles it, so a supervisor scope that
 * lives as long as the document is the right lifetime — one failed proof must not cancel the next.
 */
private val offscreenScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
