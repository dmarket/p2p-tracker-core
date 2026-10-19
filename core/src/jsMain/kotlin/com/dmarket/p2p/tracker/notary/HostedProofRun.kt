package com.dmarket.p2p.tracker.notary

import com.dmarket.p2p.tracker.config.TrackerConfig
import com.dmarket.p2p.tracker.credential.steam.HeaderSteamProofCookieSource
import com.dmarket.p2p.tracker.game.Cs2GameAdapter
import com.dmarket.p2p.tracker.model.steam.PushedSteamToken
import com.dmarket.p2p.tracker.model.steam.SteamTokenCheck
import kotlin.time.Instant

/**
 * Runs one [HostedProofRequest] in this realm with the extension's wasm prover, over [host]'s seams, and returns
 * the base64 presentation: the far side of a [ProofHost] whose prover runs in a process of its own. The
 * extension's offscreen counterpart is `proveNotaryTransition`; this one is not exported to JS.
 *
 * There is no DMarket session here, so the notary upgrade must be authenticated by
 * [WasmProverHost.notaryWebSocket], a socket that sends signed headers and drops the `bearer.` entry the transport
 * offers. A placeholder fills that entry; nothing reads it.
 *
 * The token is checked again here, as a pushed token is: a cookie-authenticated read builds `steamLoginSecure`
 * from it, so it has to name the request's account and be live at [now].
 *
 * @param config only [TrackerConfig.notary] and [TrackerConfig.game] are read.
 * @param onProgress the prover's trace: one issuance line, then its stage lines. Neither carries a credential.
 */
suspend fun runHostedProof(
    proof: HostedProofRequest,
    config: TrackerConfig,
    host: WasmProverHost,
    now: Instant,
    onProgress: ((String) -> Unit)? = null,
): String {
    requireNotNull(host.notaryWebSocket) { "a hosted proof signs its notary upgrade: WasmProverHost.notaryWebSocket is required" }
    val request = proof.request
    val subject = request.toSubjectSteamId()
    val credential = when (val check = PushedSteamToken.check(proof.steamAccessToken, now)) {
        is SteamTokenCheck.Accepted -> check.credential
        is SteamTokenCheck.Refused -> error("the Steam token cannot authenticate the proven read: ${check.reason}")
    }
    require(credential.subjectSteamId == subject) { "the Steam token is not for the account the proof names" }
    val prover = WasmNotaryProver(
        config = config.notary,
        tokenProvider = { HEADER_AUTH_PLACEHOLDER },
        module = BundledWasmProverModule(onProgress, host),
        adapter = Cs2GameAdapter(config.game),
        cookies = HeaderSteamProofCookieSource({ credential }, proof.steamSessionId),
    )
    return prover.prove(request.toBinding(), request.toKind(), subject, proof.steamAccessToken).proofPayload
}

/** Fills the transport's `bearer.` subprotocol entry, which a header-authenticated socket drops. Not a credential. */
private const val HEADER_AUTH_PLACEHOLDER = "signed-upgrade"
