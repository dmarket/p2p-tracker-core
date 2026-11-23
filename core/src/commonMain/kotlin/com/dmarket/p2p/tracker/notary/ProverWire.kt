package com.dmarket.p2p.tracker.notary

import com.dmarket.p2p.tracker.config.NotaryConfig
import com.dmarket.p2p.tracker.game.GameAdapter
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.port.notary.NotaryTokenProvider
import com.dmarket.p2p.tracker.port.notary.SteamProofCookieSource

/**
 * One prove-and-present request handed to a prover (the web's `WasmProverModule`, the native
 * `NativeNotaryProver`): everything needed to run a single issuance and build the selective-disclosure
 * `Presentation`. The prover-neutral wire it turns into is [mpcIssuance], [proxyIssuance], [proverHttpRequest]
 * and [proverRevealPolicy]; each prover only maps those into its own format.
 *
 * [steamAccessToken] is the on-device Steam JWT that authenticates the proven `IEconService` read. It is
 * substituted into the [ProvenReadSpec.path]'s `{token}` slot here, at the IO edge — which is why the spec
 * itself stays credential-free — and it is withheld from the resulting proof because the spec sets
 * `revealRequestTarget = false`.
 */
data class ProveRequest(
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
     * occupies the request body, and the pinned prover's `RevealPolicy` has no request-body control. See
     * `SESSION_ID_PLACEHOLDER`.
     */
    val steamSessionId: String? = null,
    val transport: String = "direct",
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
    override fun toString(): String = "ProveRequest(notaryUrl=$notaryUrl, notaryToken=<redacted>, proxyBaseUrl=$proxyBaseUrl, " +
        "transport=$transport, spec=$spec, steamAccessToken=<redacted>, maxSentData=$maxSentData, maxRecvData=$maxRecvData, " +
        "maxRecvDataOnline=$maxRecvDataOnline, maxRecvRecordsOnline=$maxRecvRecordsOnline, " +
        "rootStorePem=${rootStorePem?.let { "<${it.length} chars>" }}, " +
        "steamSessionCookie=${steamSessionCookie?.let { "<redacted>" }}, " +
        "steamSessionId=${steamSessionId?.let { "<redacted>" }})"
}

// The prover-neutral wire of one issuance. Each prover maps these into its own format (the wasm's objects in
// jsMain, the C ABI's JSON on Android) and decides nothing on its own: every value comes from here, so the two
// cannot drift on a limit, a credential slot or a disclosure.

/** An MPC issuance: the target's TLS name and the MPC limits. */
data class MpcIssuance(
    val serverName: String,
    val maxSentData: Int,
    val maxRecvData: Int,
    /** Clamped to [maxRecvData]: the online budget is a slice of the receive ceiling, and upstream refuses more. */
    val maxRecvDataOnline: Int,
    /** `null` sends no key, which keeps the prover's own contract default: upstream documents no figure for it. */
    val maxRecvRecordsOnline: Int? = null,
    /** PEM roots that replace the bundled Mozilla set; `null` (production) keeps it. */
    val rootStorePem: String? = null,
)

/** A proxy issuance: the target's TLS name and the plaintext limits. No MPC preprocessing knobs, no route. */
data class ProxyIssuance(val serverName: String, val maxSentData: Int, val maxRecvData: Int, val rootStorePem: String? = null)

/**
 * The proven HTTP request with its credentials filled in. The prover injects `Host`, `Accept-Encoding: identity`
 * and `Connection: close` itself, so they are never here. [headers] is empty and [body] `null` unless the read has
 * them; both trade axes have neither.
 *
 * [toString] redacts the target, the header values and the body: all three can carry a credential.
 */
class ProverHttpRequest(val method: String, val uri: String, val headers: List<Pair<String, String>>, val body: String?) {
    override fun toString(): String =
        "ProverHttpRequest(method=$method, uri=<redacted>, headers=${headers.map { it.first }}, body=${body?.let { "<redacted>" }})"
}

/** What the presentation discloses, every field set from the spec: a disclosure decision is never a prover default. */
data class ProverRevealPolicy(
    val redactRequestHeaderValues: List<String>,
    val revealRequestTarget: Boolean,
    val revealResponseHeaders: Boolean,
    val responseBody: ResponseBodyReveal,
)

internal fun ProveRequest.mpcIssuance(): MpcIssuance = MpcIssuance(
    serverName = spec.serverName,
    maxSentData = maxSentData,
    maxRecvData = maxRecvData,
    maxRecvDataOnline = minOf(maxRecvDataOnline, maxRecvData),
    maxRecvRecordsOnline = maxRecvRecordsOnline,
    rootStorePem = rootStorePem,
)

internal fun ProveRequest.proxyIssuance(): ProxyIssuance = ProxyIssuance(spec.serverName, maxSentData, maxRecvData, rootStorePem)

/** The one place the Steam token, the web session cookie and the `sessionid` are joined to the request. */
internal fun ProveRequest.proverHttpRequest(): ProverHttpRequest = ProverHttpRequest(
    method = spec.method,
    uri = spec.path.replace(TOKEN_PLACEHOLDER, steamAccessToken),
    headers = spec.sendHeaders.map { it.name to fillCredentials(it.valueTemplate) },
    body = spec.body?.let(::fillCredentials),
)

internal fun ProvenReadSpec.proverRevealPolicy(): ProverRevealPolicy = ProverRevealPolicy(
    redactRequestHeaderValues = redactRequestHeaderValues,
    revealRequestTarget = revealRequestTarget,
    revealResponseHeaders = revealResponseHeaders,
    responseBody = responseBodyReveal,
)

/**
 * Substitutes the session's slots. A slot with no value stays its literal placeholder rather than `""`: an empty
 * cookie header would make an unauthenticated request that still looks well-formed, and the proof would faithfully
 * attest a logged-out response. The provers fail before this point when a needed credential is missing.
 */
private fun ProveRequest.fillCredentials(template: String): String {
    var filled = template
    steamSessionCookie?.let { filled = filled.replace(COOKIE_PLACEHOLDER, it) }
    steamSessionId?.let { filled = filled.replace(SESSION_ID_PLACEHOLDER, it) }
    return filled
}

/**
 * The [ProveRequest] for proving [kind] for [binding], shared by every prover so they size and fill it alike. Resolves
 * what can fail before any prover work starts: the DMarket token for the notary, the read's spec, and the Steam web
 * session a cookie-authenticated read needs (one read of the jar, so the cookie and the `sessionid` describe one
 * session). The send budget is sized here because its one input is the token's length, which the spec never holds.
 */
internal suspend fun prepareProveRequest(
    config: NotaryConfig,
    tokenProvider: NotaryTokenProvider,
    mapper: SteamProofReadMapper,
    adapter: GameAdapter,
    cookies: SteamProofCookieSource,
    binding: ProvenReadBinding,
    kind: ProvenReadKind,
    subjectSteamId: SteamId,
    steamAccessToken: String,
): ProveRequest {
    val notaryToken = tokenProvider.notaryToken()
    val spec = mapper.readSpec(kind, binding, subjectSteamId, adapter)
    val session = if (spec.needsSessionCookie) {
        requireNotNull(cookies.currentSession()) { "$kind is cookie-authenticated but there is no Steam web session on this device" }
    } else {
        null
    }
    val sessionId = if (spec.needsSessionId) {
        requireNotNull(session?.sessionId) {
            "$kind needs the Steam sessionid and there is none"
        }
    } else {
        null
    }
    return ProveRequest(
        notaryUrl = config.notaryUrl,
        transport = config.transport,
        notaryToken = notaryToken,
        proxyBaseUrl = config.proxyBaseUrl,
        spec = spec,
        steamAccessToken = steamAccessToken,
        maxSentData = ProvenSentBudget.sentBudget(spec, config, steamAccessToken.length),
        maxRecvData = spec.maxRecvDataOverride ?: config.maxRecvData,
        // Already resolved against the config by the mapper: it is a learned minimum, not a per-read override.
        maxRecvDataOnline = spec.maxRecvDataOnline,
        maxRecvRecordsOnline = config.maxRecvRecordsOnline,
        rootStorePem = config.rootStorePem,
        steamSessionCookie = session?.cookieHeader,
        steamSessionId = sessionId,
    )
}
