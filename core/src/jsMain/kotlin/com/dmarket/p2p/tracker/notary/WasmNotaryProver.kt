package com.dmarket.p2p.tracker.notary

import com.dmarket.p2p.tracker.config.NotaryConfig
import com.dmarket.p2p.tracker.game.Cs2GameAdapter
import com.dmarket.p2p.tracker.game.GameAdapter
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.marketplace.ProofSubmission
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.port.notary.NoSteamProofCookieSource
import com.dmarket.p2p.tracker.port.notary.NotaryProver
import com.dmarket.p2p.tracker.port.notary.NotaryTokenProvider
import com.dmarket.p2p.tracker.port.notary.SteamProofCookieSource
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The **web / browser-extension** [NotaryProver]: drives the steam-provenance wasm prover (via
 * [WasmProverModule]) to produce a TLSN `Presentation` for a decisive transition, base64-encoded into a
 * [ProofSubmission]. Selected by `createBrowserLoop` whenever a proof delegate was supplied, and on that
 * alone: [NotaryConfig.notaryUrl] defaults to the deployed production notary, so it is no longer part of
 * the gate. No delegate ⇒ the no-op prover runs (client-reported) — which is what a runtime that cannot
 * host the prover gets, Firefox today.
 *
 * **Audit boundary:** the proven request is the token-authed `IEconService` read whose status integer the
 * client reports (see [SteamProofReadMapper]). The Steam JWT therefore *is* in the request, as
 * `?access_token=` — and is protected structurally rather than by omission: the spec sets
 * `revealRequestTarget = false`, so the whole path+query span is withheld from the presentation, and MPC
 * means the notary only ever holds ciphertext. Nothing sends it to DMarket. The trade binding moves to the
 * revealed response body, which discloses the trade id next to the status.
 *
 * This replaced a cookie-authed `steamcommunity.com` page read that no part of the client parses — that
 * proof attested a document with no defined relationship to the number being reported.
 *
 * Concurrency is capped at [NotaryConfig.maxConcurrency] (reference: 2). There is no proof-count
 * teardown: the wasm instance is process-wide and re-importing it hands back the same one, so dropping
 * references frees nothing — recycling means recycling the host's offscreen document.
 */
class WasmNotaryProver(
    private val config: NotaryConfig,
    private val tokenProvider: NotaryTokenProvider,
    private val module: WasmProverModule,
    private val adapter: GameAdapter = Cs2GameAdapter(),
    private val mapper: SteamProofReadMapper = SteamProofReadMapper(config),
    /**
     * Resolves the Steam web session for a cookie-authenticated read. Defaults to "no session", which fails
     * those reads fast and is invisible to the token-authed ones — so a caller that only proves the trade axes
     * needs no cookie plumbing at all.
     */
    private val cookies: SteamProofCookieSource = NoSteamProofCookieSource,
) : NotaryProver {

    override val maxConcurrency: Int = config.maxConcurrency

    /** Stated, not inherited: the port's default is deliberately neither real answer. */
    override val id: String = "tlsn"

    private val gate = Semaphore(maxConcurrency)

    override suspend fun proveRead(binding: ProvenReadBinding, kind: ProvenReadKind, credential: SteamCredential): ProofSubmission =
        prove(binding, kind, credential.subjectSteamId, credential.token)

    /**
     * The actual proof, taking the credential's two parts rather than the credential itself.
     *
     * Split out because the offscreen entry point (`proveNotaryTransition`) reaches the same work from a
     * context that never constructs a [SteamCredential] — it receives the public `subjectSteamId` in the
     * request JSON and the token as its own argument, which keeps the secret out of anything serialised.
     *
     * @param steamAccessToken the Steam JWT that authenticates the proven `IEconService` read. Substituted into
     *   the spec's [TOKEN_PLACEHOLDER] slot at the IO edge, never held in [ProvenReadSpec], and withheld from the
     *   presentation because the spec sets `revealRequestTarget = false`.
     */
    @OptIn(ExperimentalEncodingApi::class)
    internal suspend fun prove(
        binding: ProvenReadBinding,
        kind: ProvenReadKind,
        subjectSteamId: SteamId,
        steamAccessToken: String,
    ): ProofSubmission {
        // Before anything is spent, and before any Steam request is issued: an unenabled kind is an operator
        // decision this prover must not make for itself. It matters most for a write, where "proving" performs
        // a real Steam POST — so a kind arriving from a stale message must not be honoured just because it
        // decodes.
        check(kind in config.enabledReads) {
            "$kind is not in NotaryConfig.enabledReads, so no proof may be spent on it"
        }

        return gate.withPermit {
            // Resolve the credentials and the spec BEFORE loading the prover: all of them can fail (no DMarket
            // session, no Steam web session for a cookie-authed read, a template whose placeholder the binding
            // cannot fill) and failing here costs nothing, while `initialize` fetches and compiles ~10 MB of
            // WASM and brings up a thread pool.
            val notaryToken = tokenProvider.notaryToken()
            val spec = mapper.readSpec(kind, binding, subjectSteamId, adapter)
            // One read of the jar, not one per value: `sessionid` is part of the same session as the cookie
            // header, so fetching it separately cost an extra round-trip and opened a window where the two
            // could describe different sessions.
            val session = if (spec.needsSessionCookie) {
                requireNotNull(cookies.currentSession()) {
                    "$kind is cookie-authenticated but there is no Steam web session on this device"
                }
            } else {
                null
            }
            val sessionId = if (spec.needsSessionId) {
                requireNotNull(session?.sessionId) { "$kind needs the Steam sessionid and there is none" }
            } else {
                null
            }

            module.initialize(config.threadCount)
            val bytes = module.prove(
                WasmProveRequest(
                    notaryUrl = config.notaryUrl,
                    notaryToken = notaryToken,
                    proxyBaseUrl = config.proxyBaseUrl,
                    spec = spec,
                    steamAccessToken = steamAccessToken,
                    // Sized to the request actually being issued, per-read override included — `ProvenSentBudget`
                    // owns the whole rule (why it can only spend less, and which reads it declines to touch).
                    // It is resolved HERE rather than in the mapper, unlike `maxRecvDataOnline` below, because
                    // the one input it needs is the token length and the spec is credential-free by
                    // construction. That is not the drift risk the mapper comment warns about: the rule is a
                    // pure tested object, so a second platform's prover calls it rather than re-deriving it.
                    maxSentData = ProvenSentBudget.sentBudget(spec, config, steamAccessToken.length),
                    maxRecvData = spec.maxRecvDataOverride ?: config.maxRecvData,
                    // No `?: config` fallback, unlike the two above: the mapper already resolved this one
                    // against the config, because it is a learned *minimum* rather than a per-read override.
                    maxRecvDataOnline = spec.maxRecvDataOnline,
                    // Straight off the config, unlike the byte budget above, because it carries no per-read and
                    // no per-response decision: there is no `*Override` for it on the read template and no
                    // lesson that raises it (`OnlineBudgetLesson` reads bytes out of the refusal, not records).
                    // A field on `ProvenReadSpec` would therefore be a value copied through a pure type that
                    // never gets to decide anything. `maxSentData` is not in the spec for a *different* reason
                    // now that it is sized per proof — it decides plenty, but on an input (the token length)
                    // that a credential-free type cannot be handed.
                    maxRecvRecordsOnline = config.maxRecvRecordsOnline,
                    rootStorePem = config.rootStorePem,
                    steamSessionCookie = session?.cookieHeader,
                    steamSessionId = sessionId,
                ),
            )
            ProofSubmission(dealId = binding.dealId, proofPayload = Base64.encode(bytes))
        }
    }
}
