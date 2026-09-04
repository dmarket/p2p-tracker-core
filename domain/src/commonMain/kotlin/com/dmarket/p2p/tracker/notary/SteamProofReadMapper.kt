package com.dmarket.p2p.tracker.notary

import com.dmarket.p2p.tracker.config.NotaryConfig
import com.dmarket.p2p.tracker.config.ProvenRead
import com.dmarket.p2p.tracker.config.ProvenReadAuth
import com.dmarket.p2p.tracker.game.GameAdapter
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusSource
import com.dmarket.p2p.tracker.model.steam.toAccountId

/**
 * Pure mapping from a [ProvenReadKind] to the [ProvenReadSpec] the prover should prove. Zero IO, no clock —
 * every platform's `NotaryProver` actual calls this to decide *what* Steam request to witness and *what* to
 * reveal vs redact, then drives its own TLSN transport.
 *
 * **It used to be a two-branch `when` over [TradeStatusSource]**, which meant only two of the ten Steam
 * endpoints this client calls could be proven at all, and enabling a third was a code change. It is now keyed
 * on the registry, so every endpoint has a spec and enabling one is a `NotaryConfig.enabledReads` edit. The
 * [TradeStatusSource] entry point remains as an overload and resolves to exactly the reads it always did, so
 * the two live axes are untouched.
 *
 * ## The two authentication models, and why disclosure differs between them
 *
 * [ProvenReadAuth.TOKEN_QUERY] — the `IEconService`/`ISteamUser`/`IPlayerService` reads, including both trade
 * axes. They authenticate with the Steam JWT as `?access_token=`, so:
 *
 *  - the token is left as the [TOKEN_PLACEHOLDER] in [ProvenReadSpec.path] and filled at the IO edge, so it
 *    never becomes a field of a pure value (and so cannot reach a log or a `toString`);
 *  - `revealRequestTarget = false`, so the filled token is withheld from the presentation;
 *  - the trade binding therefore comes from the **response**, which is disclosed WHOLE
 *    ([ResponseBodyReveal.All]). Two things have to reach the verifier, not one: the bound ids *with their
 *    field names* (so a prover cannot present one trade's response, or one unrelated same-shaped field, as
 *    another's) **and** an HTTP response it can still parse. A selective reveal delivers the first and
 *    destroys the second — which is what rejected every live proof; see [ResponseBodyReveal.All] and the
 *    `TODO(disclosure)` in [readSpec] for the narrowing this is waiting on.
 *
 * [ProvenReadAuth.SESSION_COOKIE] — the community inventory read and both writes. The credential is a cookie,
 * not a query parameter, which inverts every one of the above:
 *
 *  - the path carries no secret, so `revealRequestTarget = true` — and for a write that disclosure *is* the
 *    binding (`/tradeoffer/{id}/cancel` names the offer);
 *  - the cookie rides a request header whose name is in [ProvenReadSpec.redactRequestHeaderValues], which is
 *    what withholds it. That pairing is asserted per kind in the tests, because getting it wrong is silent:
 *    the proof still verifies, it just published the session;
 *  - the response is **not** disclosed whole. `steamcommunity.com` is the host most likely to answer with
 *    `set-cookie`, and [ResponseBodyReveal.All] would publish it, so these use
 *    [ResponseBodyReveal.JsonPaths]. That mode is not yet accepted by the verifier for an unrelated framing
 *    reason, which is why enabling a community kind also needs
 *    [NotaryConfig.acknowledgeCommunityResponseDisclosure] — see [requireCommunityAcknowledged].
 */
class SteamProofReadMapper(private val config: NotaryConfig) {

    /**
     * Build the proof spec for the decisive transition on [source] — the trade axes' entry point, unchanged in
     * behaviour and resolved through [defaultProvenReadKind].
     */
    fun readSpec(source: TradeStatusSource, binding: ProvenReadBinding, subjectSteamId: SteamId, adapter: GameAdapter): ProvenReadSpec =
        readSpec(source.defaultProvenReadKind, binding, subjectSteamId, adapter)

    /** Build the proof spec for [kind] on the trade identified by [binding], owned by [subjectSteamId]. */
    fun readSpec(kind: ProvenReadKind, binding: ProvenReadBinding, subjectSteamId: SteamId, adapter: GameAdapter): ProvenReadSpec {
        // One record per endpoint: the host, the method, the path, the body and what the presentation discloses
        // of the answer travel together, because the reveal paths address fields of the body that path returns
        // from that host. Its own `init` guarantees the host is allow-listed for its auth model, the template
        // carries the slots that model needs, and the reveal list is non-empty — so none of that is re-checked
        // per proof here.
        val read = config.provenRead(kind)
        // Not a re-check of `enabledReads` — that says the operator wants the read; this says the operator has
        // accepted what its response discloses. They are separate decisions, and a spec built without the
        // second one would publish whatever `steamcommunity.com` puts in its response headers.
        require(read.auth != ProvenReadAuth.SESSION_COOKIE || config.acknowledgeCommunityResponseDisclosure) {
            "proving $kind discloses a steamcommunity.com response's headers, which are unmeasured and may " +
                "carry set-cookie — set NotaryConfig.acknowledgeCommunityResponseDisclosure to accept that"
        }

        val path = read.pathTemplate.fillBinding(binding, subjectSteamId, adapter)
        return ProvenReadSpec(
            serverName = read.serverName,
            method = read.method,
            path = path,
            // `authorization` alongside the configured cookie header, because supplying a policy REPLACES the
            // prover's own default (which redacts authorization/cookie/user-agent) and every request header
            // not named here is revealed in full. The token-authed reads send neither header; the point is
            // that a read which later does — and the community reads, which send the cookie today — cannot
            // leak by omission.
            redactRequestHeaderValues = listOf(config.provenCookieHeader, AUTHORIZATION_HEADER).distinct(),
            // Decides nothing while `All` stands (that mode reveals one span covering the whole response,
            // headers included), and load-bearing under `JsonPaths`, where the header spans carry the CRLF
            // that closes the header block. So it tracks the body mode rather than being chosen separately.
            revealResponseHeaders = read.auth == ProvenReadAuth.SESSION_COOKIE,
            responseBodyReveal = responseBodyReveal(read),
            revealRequestTarget = when (read.auth) {
                // Withheld: the path carries `access_token=`, and disclosure is all-or-nothing. The binding
                // moves to the revealed response body instead (see the class doc).
                ProvenReadAuth.TOKEN_QUERY -> false
                // Disclosed, because there is nothing in it to protect and everything to bind: the offer id a
                // cancel names lives in the path and nowhere else.
                ProvenReadAuth.SESSION_COOKIE -> true
            },
            sendHeaders = sendHeaders(read, binding),
            body = read.bodyTemplate?.fillBinding(binding, subjectSteamId, adapter)?.fillWriteFragments(binding, adapter),
            maxSentDataOverride = read.maxSentDataOverride,
            maxRecvDataOverride = read.maxRecvDataOverride,
            // From the BINDING, not the read: the online budget follows the response THIS deal produces, and
            // the read template is shared by every deal on the axis. Resolved here rather than at the IO edge
            // so the "a lesson raises, never lowers" rule is stated once, in pure code, instead of once per
            // platform prover.
            maxRecvDataOnline = maxOf(binding.minOnlineBudget ?: 0, config.maxRecvDataOnline),
        )
    }

    // ---- private -----------------------------------------------------------------------------------

    /**
     * What the presentation discloses of the response.
     *
     * The token-authed reads keep the whole-response reveal, which is a rollback forced by a live rejection:
     * every proof came back `verified:false, "the revealed response has no header/body separator"`. The
     * verifier splits the revealed bytes on `\r\n\r\n`, and a `JsonPaths` reveal with the headers withheld can
     * never contain that sequence — `spansy` puts each header's trailing CRLF INSIDE that header's span, so
     * withholding the headers also withholds the CRLF closing the last one. Not a tuning miss: no combination
     * of reveal PATHS fixes it, because the missing bytes are not in the body at all.
     *
     * TODO(disclosure): narrow the token-authed reads back to `JsonPaths(read.revealJsonPaths)` — leaf paths
     *  now that the vendored `f7d40de` reveals key paths for leaves — which the community branch below already
     *  does with `revealResponseHeaders = true`, the flag that restores the separator. Blocked on one backend
     *  answer: under a path reveal the withheld siblings come back as `'#'` sentinel runs, so the revealed body
     *  is NOT `JSON.parse`-able and the verifier has to match the bound fields textually. Until that is
     *  confirmed, narrowing only swaps one rejection reason for another.
     *
     * The community reads cannot wait for that answer, because for them `All` is not merely broad — it would
     * publish `set-cookie`. So they take the narrow mode and the framing risk, which is precisely what
     * [NotaryConfig.acknowledgeCommunityResponseDisclosure] is the operator's acknowledgement of.
     */
    private fun responseBodyReveal(read: ProvenRead): ResponseBodyReveal = when (read.auth) {
        ProvenReadAuth.TOKEN_QUERY -> ResponseBodyReveal.All
        ProvenReadAuth.SESSION_COOKIE -> ResponseBodyReveal.JsonPaths(read.revealJsonPaths)
    }

    /**
     * The headers a proven request must send — empty for every token-authed read, which is what keeps that
     * path byte-for-byte what it was before headers were supported at all.
     *
     * A cookie-authed request sends the session cookie as a template ([COOKIE_PLACEHOLDER]); a create
     * additionally sends the `Referer`/`Origin` pair Steam's anti-CSRF check demands. That check is why the web
     * actual has to install a `declarativeNetRequest` rule — `Referer` is a forbidden fetch header, so a
     * service-worker request cannot set it — and the prover has no such restriction: it writes the header
     * directly. Steam validates the `Referer`'s `partner` against the body's, so both come off one binding.
     */
    private fun sendHeaders(read: ProvenRead, binding: ProvenReadBinding): List<ProvenRequestHeader> {
        if (read.auth != ProvenReadAuth.SESSION_COOKIE) return emptyList()
        val cookie = ProvenRequestHeader(config.provenCookieHeader, COOKIE_PLACEHOLDER)
        // Driven by the read's own `refererTemplate`, not by which kind this is. That requirement is endpoint
        // data — Steam's create endpoint cross-checks the Referer's `partner` against the body's and its cancel
        // endpoint asks for nothing — and keying it on enum identity here meant an eleventh community endpoint
        // needing a Referer would be a mapper edit, which is the code change the registry exists to remove.
        val referer = read.refererTemplate ?: return listOf(cookie)

        val partner = requireNotNull(binding.partnerSteamId) { "a proven ${read.method} needs the partner steam id for its Referer" }
        val accountId = requireNotNull(partner.toAccountId()) { "partner steam id '${partner.value}' is not numeric" }
        return listOf(
            cookie,
            ProvenRequestHeader(
                REFERER_HEADER,
                referer
                    .replace(SteamWriteBody.PARTNER_ACCOUNT_ID_PLACEHOLDER, accountId.toString())
                    .replace(SteamWriteBody.TRADE_TOKEN_PARAM_PLACEHOLDER, SteamWriteBody.tradeTokenParam(binding.tradeToken)),
            ),
            ProvenRequestHeader(ORIGIN_HEADER, "https://${read.serverName}"),
        )
    }

    /**
     * Substitute every binding-supplied slot. Absent values **fail** rather than becoming `""`: a
     * `?tradeofferid=&…` empty-value query would silently produce a malformed (unverifiable) proof read, and a
     * template that does not reference a placeholder is unaffected.
     *
     * `{token}` and `{sessionId}` are deliberately NOT filled here — see the class doc.
     */
    private fun String.fillBinding(binding: ProvenReadBinding, subjectSteamId: SteamId, adapter: GameAdapter): String = this
        .fillPlaceholder(SUBJECT_STEAM_ID, subjectSteamId.value)
        .fillPlaceholder(PARTNER_STEAM_ID, binding.partnerSteamId?.value)
        .fillPlaceholder(OFFER_ID, binding.steamOfferId?.value)
        .fillPlaceholder(APP_ID, adapter.game.appId.toString())
        .fillPlaceholder(CONTEXT_ID, adapter.inventoryContextId.toString())
        .fillPlaceholder(ASSET_ID, binding.assetId?.value)
        .fillPlaceholder(TRADE_ID, binding.tradeId?.value)

    /**
     * Fill the create body's two JSON fragments, which cannot be plain placeholders because they are
     * variable-length documents. [SteamWriteBody] returns them already form-encoded — encoding at substitution
     * time instead would leave the JSON's `&` and `=` free to split the body into fields Steam never sent.
     */
    private fun String.fillWriteFragments(binding: ProvenReadBinding, adapter: GameAdapter): String = this
        .fillPlaceholder(
            SteamWriteBody.ASSETS_PLACEHOLDER,
            SteamWriteBody.assetsFragment(binding.assetsToGive, adapter.game.appId, adapter.inventoryContextId),
        )
        .fillPlaceholder(SteamWriteBody.CREATE_PARAMS_PLACEHOLDER, SteamWriteBody.createParamsFragment(binding.tradeToken))

    /** Substitute [placeholder] with [value]; if the template uses it, [value] must be present. */
    private fun String.fillPlaceholder(placeholder: String, value: String?): String {
        if (!contains(placeholder)) return this
        requireNotNull(value) { "proof read template '$this' requires $placeholder but the trade binding has no value for it" }
        return replace(placeholder, value)
    }

    private companion object {
        const val AUTHORIZATION_HEADER = "authorization"
        const val REFERER_HEADER = "referer"
        const val ORIGIN_HEADER = "origin"
    }
}
