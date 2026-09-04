package com.dmarket.p2p.tracker.config

import com.dmarket.p2p.tracker.net.SteamHosts
import com.dmarket.p2p.tracker.notary.OFFER_ID
import com.dmarket.p2p.tracker.notary.SESSION_ID_PLACEHOLDER
import com.dmarket.p2p.tracker.notary.TOKEN_PLACEHOLDER

/**
 * How a proven Steam request authenticates — and therefore which host it may address, which placeholder it
 * must carry, and what the IO edge has to fill.
 *
 * The two are not interchangeable and never both apply: the Web API host authenticates by query parameter and
 * ignores cookies; the community host authenticates by cookie and has no use for the JWT. `ProvenRead.init`
 * enforces exactly that, so a read cannot carry a credential the endpoint it names does not want.
 */
enum class ProvenReadAuth {
    /** `?access_token={token}` on `api.steampowered.com`. The whole path is withheld from the presentation. */
    TOKEN_QUERY,

    /**
     * `steamLoginSecure` in a `cookie` header on a `steamcommunity.com` surface, with `sessionid` echoed in
     * the form body for a write. The path carries no credential, so the request target *is* disclosed — which
     * is what binds a write to the offer it names.
     */
    SESSION_COOKIE,
}

/**
 * One Steam request that a TLSN proof may witness: **which host, which method, which path, what it sends, and
 * what the presentation discloses of the answer** — held together because they only ever change together.
 *
 * They were three separate config fields, and the host was a single value shared by every axis while the path
 * and the reveal list were per-axis. That split was wrong in both directions. The reveal paths address fields
 * of the body *that* path returns *from that host*, so publishing one without the others produces a proof of
 * a document the reveal paths do not describe — which is exactly how a stale config once made every proof
 * fail with `JSON path not found`. And a shared host cannot express a second read living somewhere else.
 *
 * **Not host-suppliable and not remotely tunable** (`@JsExport.Ignore`d on [NotaryConfig], absent from every
 * remote-config schema). A wrong value here does not degrade gracefully: it fails every proof for that read,
 * or — worse — proves the wrong document. These track Steam's response shapes, which are fixed rather than
 * per-deployment, so they belong with the code that depends on them.
 *
 * **Adding a read** is now a catalog entry plus an enum case
 * ([com.dmarket.p2p.tracker.notary.ProvenReadKind] +
 * [com.dmarket.p2p.tracker.notary.ProvenReadCatalog]), not a new config field and a new `when` branch. Two
 * things still do not come for free:
 *  - **A new host must be agreed with the verifier.** It compares the attested `server_name` against a single
 *    configured value by exact string equality, so every [ProvenReadAuth.SESSION_COOKIE] read is rejected
 *    downstream until that becomes an allow-list on its side. The client-side plumbing is complete; the
 *    rejection is remote.
 *  - **A community response's disclosure is unmeasured.** Neither reveal mode can withhold response headers
 *    while staying verifiable, so a `steamcommunity.com` read needs
 *    [NotaryConfig.acknowledgeCommunityResponseDisclosure] before `SteamProofReadMapper` will build a spec
 *    for it.
 */
data class ProvenRead(
    /** TLS `server_name` to prove against — the host the MPC dials, so it is allow-listed, not free text. */
    val serverName: String,
    /**
     * Path + query, with `{offerId}` / `{tradeId}` / `{steamId}` / `{partnerSteamId}` / `{appId}` /
     * `{contextId}` / `{assetId}` substituted by `SteamProofReadMapper` and [TOKEN_PLACEHOLDER] deliberately
     * left for the IO edge to fill, so the credential never becomes a field of a pure value (and so cannot
     * reach a log or a `toString`).
     */
    val pathTemplate: String,
    /**
     * `spansy` paths whose spans the presentation would disclose (dot-separated, array indices as literal
     * integers, no wildcards).
     *
     * **Inert for the token-authed trade reads**, and deliberately kept so: `SteamProofReadMapper` asks for
     * `ResponseBodyReveal.All` there because a selective reveal cannot present the header/body separator the
     * verifier splits on. These are the target of that narrowing, not live policy — see
     * `ResponseBodyReveal.All` for the rejection that forced it, and `ResponseBodyReveal.JsonPaths` for why
     * they name objects rather than leaves and what that would disclose beyond the bound fields.
     *
     * They are **not** inert for a [ProvenReadAuth.SESSION_COOKIE] read: a whole-response reveal there would
     * publish `set-cookie`, so those specs use these paths. Validated below in either case, because an empty
     * list is the one mistake a verifier could not detect once they are live.
     */
    val revealJsonPaths: List<String>,
    /**
     * How this request authenticates. Defaults to [ProvenReadAuth.TOKEN_QUERY] — every read that existed
     * before the catalog — so an existing three-argument construction is unchanged.
     */
    val auth: ProvenReadAuth = ProvenReadAuth.TOKEN_QUERY,
    /** HTTP method. Anything other than `GET` is a write, and writes carry the extra requirements below. */
    val method: String = "GET",
    /**
     * Form body template for a write, `null` for a read. Must carry [SESSION_ID_PLACEHOLDER], which the IO
     * edge fills — Steam's community endpoints reject a body without `sessionid`.
     */
    val bodyTemplate: String? = null,
    /**
     * `Referer` (and, implied, `Origin`) this request must send, or `null` when it needs neither.
     *
     * Endpoint **data**, not a property of the write in general, which is why it lives here rather than as a
     * per-kind branch in `SteamProofReadMapper`: Steam's create endpoint validates the `Referer`'s `partner`
     * against the POST body's and answers `403` on a mismatch, while its cancel endpoint asks for no such
     * thing. Substituted through the same placeholder machinery as [pathTemplate] — `{partnerAccountId}` is
     * the 32-bit form Steam wants here (the body carries the steamid64; the two are not interchangeable).
     *
     * Withheld from nothing: this header carries no credential, so unlike the cookie it is not in the
     * redaction list. It is the one header a proven request sends purely to satisfy an anti-CSRF check.
     */
    val refererTemplate: String? = null,
    /**
     * Per-read override of [NotaryConfig.maxSentData], or `null` to keep the global value.
     *
     * The global is sized for `196 + len(token)` — a create POST's cookie header alone is larger than that,
     * and exceeding the cap fails every proof for the read. Raising it globally instead would make every
     * trade-axis proof pay the pre-processing (42 MB measured for a 717 B request), which is why this is
     * per-read.
     */
    val maxSentDataOverride: Int? = null,
    /**
     * Per-read override of [NotaryConfig.maxRecvData], or `null` to keep the global value. Needed by the
     * unbounded reads (the offer list, an inventory page) whose responses can dwarf the 16 KiB global.
     */
    val maxRecvDataOverride: Int? = null,
    /**
     * Required to construct a write, and it names a disclosure rather than a preference.
     *
     * The vendored prover's `RevealPolicy` has **no request-body field** — only
     * `redactRequestHeaderValues`, `revealRequestTarget`, `revealResponseHeaders` and `revealResponseBody` —
     * so a proven write's form body, `sessionid` included, is disclosed to the verifier and cannot be withheld
     * the way a header value can. The exposure is bounded (`sessionid` is useless without the
     * `steamLoginSecure` cookie, which *is* redacted), but it is still a decision, and the compiler is the
     * only thing that can force it to be made consciously — the same discipline as
     * `ProvenReadSpec.revealRequestTarget` having no default.
     *
     * Lifting this means an upstream prover field; the ask is recorded in `vendor/tlsn/INTEGRATION.md`.
     */
    val acknowledgeRequestBodyDisclosure: Boolean = false,
) {
    /** True when this read performs a Steam write rather than witnessing a read. */
    val isWrite: Boolean get() = method != "GET"

    init {
        // The host the MPC dials, with a device-only credential in the request it issues — so an unchecked
        // value sends that credential to whatever origin it names, through our own proxy. That is precisely
        // what SteamHosts exists to make impossible, and this class shipped without the check.
        //
        // Wrapped in a scheme because `requireAllowed` takes a URL, which also rejects the host-confusion
        // shapes a bare comparison would miss (`evil.example/api.steampowered.com`,
        // `api.steampowered.com@evil.example`) — `hostOf` strips userinfo, port and path first.
        val allowed = when (auth) {
            ProvenReadAuth.TOKEN_QUERY -> SteamHosts.API
            ProvenReadAuth.SESSION_COOKIE -> SteamHosts.WEB
        }
        SteamHosts.requireAllowed("https://$serverName", allowed, "ProvenRead.serverName")
        require(pathTemplate.startsWith("/")) { "proven read path must be root-relative, was '$pathTemplate'" }
        // The base alone is not enough: the request URL is host + path, and a path can move the effective
        // host (`"@evil.example.com/"` turns the checked host into userinfo). Same guard SteamEndpointsConfig
        // applies to the paths that carry the JWT — this one carries it too.
        SteamHosts.requirePathKeepsHost("https://$serverName", pathTemplate, allowed, "ProvenRead.pathTemplate")

        when (auth) {
            // A template with no token slot issues an UNAUTHENTICATED read: Steam answers 401 inside MPC and
            // it surfaces as an opaque proof failure, for every proof, with nothing else able to catch it.
            ProvenReadAuth.TOKEN_QUERY -> require(TOKEN_PLACEHOLDER in pathTemplate) {
                "proven read '$pathTemplate' has no $TOKEN_PLACEHOLDER slot, so the read would be unauthenticated"
            }
            // The mirror image, and it is about blast radius rather than about the request failing: a
            // cookie-authenticated endpoint has no use for the Steam JWT, so carrying it there would widen
            // where that credential travels for no benefit at all.
            ProvenReadAuth.SESSION_COOKIE -> {
                require(TOKEN_PLACEHOLDER !in pathTemplate) {
                    "cookie-authenticated read '$pathTemplate' must not carry $TOKEN_PLACEHOLDER — " +
                        "the community host authenticates by cookie and has no use for the Steam token"
                }
                require(bodyTemplate == null || TOKEN_PLACEHOLDER !in bodyTemplate) {
                    "cookie-authenticated write body must not carry $TOKEN_PLACEHOLDER"
                }
            }
        }

        if (isWrite) {
            // Every one of these is a way a write could go wrong silently, so each is stated rather than
            // inferred from the others.
            require(auth == ProvenReadAuth.SESSION_COOKIE) {
                "a Steam write authenticates by session cookie, but '$pathTemplate' declares $auth"
            }
            require(bodyTemplate != null) { "write '$pathTemplate' has no body template" }
            require(SESSION_ID_PLACEHOLDER in bodyTemplate) {
                "write body for '$pathTemplate' has no $SESSION_ID_PLACEHOLDER slot, and Steam's community " +
                    "endpoints reject a body without sessionid"
            }
            require(acknowledgeRequestBodyDisclosure) {
                "write '$pathTemplate' discloses its request body (including sessionid) to the verifier, " +
                    "because the prover has no request-body reveal control — set " +
                    "acknowledgeRequestBodyDisclosure to accept that"
            }
            // The hard rules are enforced by encapsulation everywhere else in this codebase: each write
            // actual builds only its one fixed URL, so no confirm or `mobileconf` endpoint is reachable
            // through it. The prover is the second place a Steam write URL can be built, so it is bounded the
            // same way rather than trusted — no configuration, host- or operator-supplied, can point it at a
            // confirm surface.
            require(pathTemplate in WRITE_PATHS) {
                "write path '$pathTemplate' is not one of the two permitted Steam write surfaces " +
                    "($WRITE_PATHS) — no other write endpoint may be reached through a proven request"
            }
        } else {
            require(bodyTemplate == null) { "read '$pathTemplate' must not carry a request body" }
        }

        // A withheld request target AND nothing disclosed of the response is a well-formed attestation that
        // binds no trade at all — the one failure a verifier cannot detect, because there is nothing to
        // disagree with.
        require(revealJsonPaths.isNotEmpty()) { "a proven read with no reveal paths would bind no trade" }
        maxSentDataOverride?.let { require(it >= 1) { "maxSentDataOverride must be >= 1, was $it" } }
        maxRecvDataOverride?.let { require(it >= 1) { "maxRecvDataOverride must be >= 1, was $it" } }
    }

    private companion object {
        /**
         * The only two write paths a proven request may take, mirroring the fixed URLs the two write actuals
         * build (`…/tradeoffer/new/send`, `…/tradeoffer/{id}/cancel`).
         *
         * **Exact templates, not patterns.** A [ProvenRead] is always a template — the offer id arrives as
         * `{offerId}` and is substituted by `SteamProofReadMapper` — so equality is both sufficient and the
         * strictest option available: nothing loose enough to admit `…/tradeoffer/{id}/accept` can slip
         * through.
         *
         * It also avoids a regex, which this guard briefly used and which is a trap in `commonMain`: `\{` is a
         * valid escape on the JVM and a hard `SyntaxError` under JS's unicode-mode regex, so the check passed
         * `:domain:jvmTest` and threw on construction in every JS build.
         */
        val WRITE_PATHS: Set<String> = setOf("/tradeoffer/new/send", "/tradeoffer/$OFFER_ID/cancel")
    }
}
