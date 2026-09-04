package com.dmarket.p2p.tracker.port.notary

import com.dmarket.p2p.tracker.model.marketplace.ProofSubmission
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusSource
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.notary.ProvenReadBinding
import com.dmarket.p2p.tracker.notary.ProvenReadKind
import com.dmarket.p2p.tracker.notary.defaultProvenReadKind

/**
 * Generates a TLSN proof of a Steam request. Runs in an offscreen document (web Chrome) or a WebView WASM
 * context (mobile active); a no-op implementation is supplied where TLSN is unavailable.
 *
 * The proven request is named by a [ProvenReadKind] — one entry per Steam endpoint this client calls, so no
 * endpoint is a dead end. A decisive trade transition resolves to one of them through [proveTransition], which
 * is what the tracker loop uses and the only path that reaches `POST /notary`.
 *
 * Whichever credential authenticates the request is **redacted** from the resulting presentation and stays
 * on-device; nothing here is ever sent to the marketplace (audit boundary). There is no `proof_type` — the
 * proven transcript carries the Steam request URL, so the backend reads which transition it covers.
 */
interface NotaryProver {
    /** Maximum proofs in flight per device (reference cap: 2). */
    val maxConcurrency: Int

    /**
     * Short identifier for this implementation, carried on `ProofSubmitted` so a reader can tell a real
     * proof the backend rejected from a stub that was never a proof at all. Those two look identical in the
     * log otherwise, and they call for opposite responses.
     *
     * The default is deliberately **neither** of the real answers. Defaulting it to `"tlsn"` would make
     * silence mean "a real proof ran" — fail-open on precisely the distinction this field exists to draw, so
     * the next implementation that forgets to override would claim a proof it never produced. Same discipline
     * as the host's own `normalizeBlockingReason`, which renders an unrecognised state as blocked rather than
     * fine.
     */
    val id: String get() = "unknown"

    /**
     * Produce a [ProofSubmission] (a base64 `postcard` `Presentation`) witnessing the Steam request named by
     * [kind] for [credential]'s account, bound to the trade identified by [binding].
     *
     * **The one abstract member**, so an implementation cannot support one axis and not another, and so the
     * eight endpoints beyond the two trade axes are reachable without a second method to keep in sync.
     * [proveTransition] is expressed in terms of it.
     *
     * Which credential authenticates the proven request depends on the endpoint, and both stay device-only:
     *  - a token-authed read (`api.steampowered.com`, including both trade axes) carries [credential]'s JWT as
     *    `?access_token=`, protected structurally rather than by omission —
     *    `ProvenReadSpec.revealRequestTarget` is `false`, withholding the whole path+query span from the
     *    presentation, and MPC means the notary only ever holds ciphertext. The trade binding therefore comes
     *    from the revealed **response** body, not the request line.
     *  - a cookie-authed request (`steamcommunity.com`) carries the Steam **web session** cookie instead, in a
     *    header named in `ProvenReadSpec.redactRequestHeaderValues` — which is what withholds it. That
     *    credential is not [credential]'s and is resolved at the IO edge, never passed through this port.
     *
     * Nothing goes to the marketplace either way. An implementer must not reveal a token-authed target, and
     * must not move the Steam JWT into a header.
     *
     * **Two things a caller must know about the non-trade kinds:**
     *  - a [ProvenReadKind] with `dealScoped = false` produces a proof no channel accepts — `POST /notary`
     *    takes `{dealId, proofPayload}` — so it is returned for the caller to use, not submitted;
     *  - a [ProvenReadKind] with `isWrite = true` **performs** the Steam write, because TLSN requires the
     *    prover to be the TLS client. Enabling one replaces the client's own write; it does not add a proof
     *    alongside it.
     *
     * Implementations resolve [kind] against `NotaryConfig.enabledReads` and fail loudly for a kind the
     * operator has not enabled, rather than silently proving something nobody asked for.
     *
     * MVP: the default ([com.dmarket.p2p.tracker.adapter.notary.NoOpNotaryProver]) returns a **stub** (empty
     * payload), so the deal flow runs end-to-end against the backend's mock verify until a real prover is
     * wired.
     */
    suspend fun proveRead(binding: ProvenReadBinding, kind: ProvenReadKind, credential: SteamCredential): ProofSubmission

    /**
     * Produce a proof for the decisive transition on [source] — the trade axes' entry point, and what the
     * tracker loop calls.
     *
     * Defaulted rather than abstract so the axis→endpoint mapping lives in exactly one place
     * ([defaultProvenReadKind]) and no implementation can disagree with it about which read witnesses which
     * axis. Note the history axis's proven read is `GetTradeStatus`, not the `GetTradeHistory` the polling
     * path reads — see that mapping's doc for why.
     */
    suspend fun proveTransition(binding: ProvenReadBinding, source: TradeStatusSource, credential: SteamCredential): ProofSubmission =
        proveRead(binding, source.defaultProvenReadKind, credential)
}
