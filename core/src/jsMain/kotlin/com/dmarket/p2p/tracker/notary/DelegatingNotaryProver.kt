package com.dmarket.p2p.tracker.notary

import com.dmarket.p2p.tracker.model.marketplace.ProofSubmission
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.port.notary.NotaryProver
import com.dmarket.p2p.tracker.port.notary.NotaryTokenProvider
import kotlinx.coroutines.await
import kotlinx.serialization.json.Json
import kotlin.js.Promise

/**
 * A [NotaryProver] that does no proving: it hands the request to a host callback and takes the
 * finished presentation back.
 *
 * This exists because of where an MV3 loop lives. The tracker runs in a service worker, and the wasm
 * prover cannot: its rayon pool calls `new Worker(…)`, which `ServiceWorkerGlobalScope` does not expose,
 * and Chrome's own docs say cross-origin isolation "is not fully implemented" for service workers
 * either. The proof has to happen in an offscreen document, so the loop delegates across a boundary the
 * library does not own — consistent with the host owning message transport everywhere else here.
 *
 * The host's side is one function: relay the two strings to a context that can, call
 * `proveNotaryTransition` there, resolve with the base64 presentation. The wasm and its
 * `SharedArrayBuffer` stay wholly inside that context, which also sidesteps a documented Chrome
 * limitation — a `SharedArrayBuffer` cannot be reliably passed from a service worker to an offscreen
 * document, so nothing tries to.
 *
 * Both credentials are resolved **here**, on the loop's side, and passed along rather than re-derived across
 * the boundary: the service worker owns credential refresh, and a second refresh authority racing it over a
 * rotating token is the failure this codebase avoids everywhere else. It also means a missing session fails
 * before any message is sent.
 *
 * **Two tokens, two purposes** — the delegate takes them separately because they are not interchangeable and
 * conflating them would bind proofs to the wrong account:
 *  - the DMarket access token authenticates the client *to the notary service*;
 *  - the Steam access token authenticates the *proven read* to `api.steampowered.com`.
 *
 * The Steam JWT crossing this boundary is a deliberate widening, forced by the proven read being a token-authed
 * API call rather than a cookie-authed page (see [SteamProofReadMapper]). It stays device-only: MPC means the
 * notary sees only ciphertext, and `revealRequestTarget = false` keeps the query string — and therefore the
 * token — out of the presentation. Nothing sends it to DMarket.
 */
class DelegatingNotaryProver(
    override val maxConcurrency: Int,
    private val tokenProvider: NotaryTokenProvider,
    private val delegate: (String, String, String) -> Promise<String>,
) : NotaryProver {

    /** Stated, not inherited: this relays to a real prover, and the port's default claims neither. */
    override val id: String = "tlsn"

    override suspend fun proveRead(binding: ProvenReadBinding, kind: ProvenReadKind, credential: SteamCredential): ProofSubmission {
        val request = notaryProofRequest(binding, kind, credential.subjectSteamId)
        val payload = delegate(Json.encodeToString(request), tokenProvider.notaryToken(), credential.token).await()
        return ProofSubmission(dealId = binding.dealId, proofPayload = payload)
    }
}
