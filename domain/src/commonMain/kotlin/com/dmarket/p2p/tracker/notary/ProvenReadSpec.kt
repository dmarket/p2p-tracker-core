package com.dmarket.p2p.tracker.notary

import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.TradeId

/**
 * The trade-specific identifiers the loop knows for a decisive transition, handed to
 * [com.dmarket.p2p.tracker.port.notary.NotaryProver] so the proven Steam read can **bind** the exact trade.
 * Binding is a golden-source requirement: something in the attestation must pin the exact offer/trade,
 * otherwise a malicious prover could attach someone else's response. These ids address the read; the binding
 * itself is then disclosed out of the *response* (see [ProvenReadSpec.revealRequestTarget], which explains
 * why it cannot come from the request line any more).
 *
 * Deliberately credential-free — the token that authenticates the read is filled in at the IO edge by the
 * platform actual (see [TOKEN_PLACEHOLDER]), never part of this pure value.
 */
data class ProvenReadBinding(
    val dealId: DealId,
    val steamOfferId: OfferId? = null,
    val assetId: AssetId? = null,
    /**
     * Steam's own `tradeid` — the history axis's binding key, and the only thing that can address a single
     * trade in a proven read (`GetTradeStatus?tradeid=…`). Steam sets it on the offer once the offer is
     * accepted, so it is available for exactly the transitions the history axis proves.
     */
    val tradeId: TradeId? = null,
    /**
     * The **other** account in the trade — the subject of a profile or level read, and the `partner` field of
     * a create body. Never the device's own id, which travels separately as `subjectSteamId` because it comes
     * off the credential rather than the deal.
     */
    val partnerSteamId: SteamId? = null,
    /**
     * The partner's trade-offer access token, when the deal carries one. Not a device credential and not a
     * secret of this account's — it is the counterparty's public invite parameter, which is why it may sit in
     * a pure value while the Steam JWT may not.
     */
    val tradeToken: String? = null,
    /** Assets the create body offers. Empty for every read; only [ProvenReadKind.CREATE_OFFER] reads it. */
    val assetsToGive: List<AssetId> = emptyList(),
    /**
     * The online-decryption budget a previous refusal proved this deal's response needs, or `null` when
     * nothing has been learned and the configured default stands on its own.
     *
     * A **minimum**, never a replacement: [SteamProofReadMapper] resolves it against
     * `NotaryConfig.maxRecvDataOnline` with `maxOf`, so a stored lesson can raise the configured value but can
     * never pin a deal below a default someone later raised.
     *
     * Carried on the binding because it is knowledge about **this deal's response**, not about the read kind:
     * two deals sharing the same read template can need different budgets if their offers hold different item
     * counts. See [OnlineBudgetLesson].
     *
     * Appended last: every field here is filled by name, but the platform actuals hand this value across a
     * JSON boundary (`NotaryProofRequest`), and positional drift is how one of those ends up silently dropped.
     */
    val minOnlineBudget: Int? = null,
)

/**
 * The placeholder [ProvenReadSpec.path] leaves for a credential the IO edge must fill.
 *
 * Lives in `:domain` because it is part of the [ProvenReadSpec] contract, not one platform's detail: every
 * platform's `NotaryProver` actual has to fill it, and the default read templates in `NotaryConfig`
 * interpolate it so the two spellings cannot drift. It used to be an `internal` constant in the web prover,
 * with the contract stated only as prose here — which meant a mobile prover would re-derive the literal from
 * a comment.
 */
const val TOKEN_PLACEHOLDER: String = "{token}"

/**
 * The placeholder a cookie-authenticated read leaves for the Steam **web session** cookie header, filled at
 * the IO edge exactly like [TOKEN_PLACEHOLDER].
 *
 * Its own credential, not the JWT: `steamcommunity.com` authenticates by `steamLoginSecure`, which the Web
 * API host has no use for and vice versa. It appears only in a [ProvenReadSpec.sendHeaders] value template —
 * never in a path — and the header carrying it is always named in
 * [ProvenReadSpec.redactRequestHeaderValues], which is what withholds it from the presentation.
 */
const val COOKIE_PLACEHOLDER: String = "{cookie}"

/**
 * The placeholder a Steam community **write** leaves for the `sessionid` anti-CSRF token, filled at the IO
 * edge.
 *
 * ⚠️ Unlike [TOKEN_PLACEHOLDER] and [COOKIE_PLACEHOLDER], the value that lands here is **disclosed** by the
 * proof. It occupies a form body, and the vendored prover's `RevealPolicy` has no request-body control at all
 * (only `redactRequestHeaderValues` / `revealRequestTarget` / `revealResponseHeaders` /
 * `revealResponseBody`), so a request body cannot be withheld the way a header value can. The exposure is
 * bounded — `sessionid` is worthless without the `steamLoginSecure` cookie, which *is* redacted — but it is a
 * disclosure decision, which is why constructing a write requires
 * [com.dmarket.p2p.tracker.config.ProvenRead.acknowledgeRequestBodyDisclosure].
 */
const val SESSION_ID_PLACEHOLDER: String = "{sessionId}"

/**
 * What the presentation discloses of the proven read's **response**. Mirrors the prover's own `BodyReveal`
 * (`"all" | "none" | { jsonPaths }`) minus `"none"`: with the request target withheld, a proof that
 * discloses nothing of the response identifies nothing at all, so this library has no way to ask for it.
 */
sealed interface ResponseBodyReveal {

    /**
     * Disclose the **whole** response — status line, headers and body, field *names* included.
     *
     * **The setting for both trade axes**, and the reason is HTTP framing rather than binding.
     *
     * The verifier does not consume the revealed bytes as a blob — it parses them as an HTTP response, which
     * means splitting on `\r\n\r\n` first. A [JsonPaths] reveal with [ProvenReadSpec.revealResponseHeaders]
     * `false` cannot produce that sequence at all: `spansy` includes each header's trailing CRLF **inside**
     * that header's span (`header_range = name.start..value.end + crlf + 2`), so withholding the headers also
     * withholds the CRLF that closes the last one, and `Response::without_data` leaves only the status line
     * plus the blank line. The revealed bytes therefore read `HTTP/1.1 200 OK\r\n` + a `'#'`-filled run +
     * `\r\n` + body — one CRLF where the parser needs two. Live result: `/notary` answered every proof
     * `{verified: false, reason: "the revealed response has no header/body separator"}`. No choice of reveal
     * *paths* can fix it, because the missing bytes are not in the body.
     *
     * That makes this the only setting that works **today**; [JsonPaths] plus `revealResponseHeaders = true`
     * is the narrower end state, and the `TODO(disclosure)` in [SteamProofReadMapper.readSpec] records what
     * it is waiting on. Also the conservative fallback for a response shape no fixed path can address.
     *
     * It had *first* replaced [JsonPaths] for an unrelated reason: a path reveal disclosed far less than its
     * path spelled.
     * On the prover of the time (`d9dcb58` and earlier) `spansy` resolved a path to the `KeyValue`'s **value**
     * span and the prover revealed that alone, so on a real `GetTradeOffer` body the two configured paths put
     * exactly `9313246543` (10 B) and `2` (1 B) into the presentation — no key, no quotes, no structure, out of
     * ~2.4 KB. Three things followed, and the third was a hole in our own reveal policy rather than an
     * inconvenience:
     *  - the verifier cannot bind `steam_offer_id` or the status *by name*, because no name is disclosed;
     *  - the two axes are indistinguishable — each discloses an id-shaped run and a small integer, and the
     *    `/notary` frame (`{dealId, proofPayload}`) carries no axis discriminator either;
     *  - the disclosure was **forgeable by selection**: the same body also carries `confirmation_method: 2`
     *    and an 11-digit `assetid`, so a prover could reveal unrelated values and produce a presentation
     *    that is structurally indistinguishable from an honest one.
     *
     * All three are closed as of the vendored prover `f7d40de`, which reveals each selected field with the key
     * path that names it — so they are no longer why this setting stands. The framing above is.
     *
     * Revealing the whole response costs disclosure of the response *headers* too (the prover reveals one
     * span covering the lot), so [ProvenReadSpec.revealResponseHeaders] no longer decides that — see its doc
     * for the measurement that says the header set is safe to publish.
     *
     * **Size, measured** (same attestation + secrets, both policies, `client-core`'s hermetic harness): the
     * presentation grows by almost exactly one byte per newly disclosed response byte — +756 B over a 722 B
     * body, +3,146 B over a 3,111 B one. On a real ~2.4 KB Steam response that projects the live payload from
     * 8,088 B (10,784 base64 chars) to **~10.5 KB (~14 K chars)**, and `NotaryConfig.maxRecvData` (16 KiB)
     * caps the worst case at roughly 25 KB (~33 K chars) — it bounds the transcript, which bounds what can
     * be revealed. Unrelated to `maxRecvData` otherwise: this change does not alter the transcript itself.
     */
    data object All : ResponseBodyReveal

    /**
     * Disclose only the spans at these `spansy` paths (dot-separated segments, array indices as literal
     * integers, no wildcards or filters) — everything else in the response stays withheld.
     *
     * **Not used by [SteamProofReadMapper] today** — the intended end state, not the current one. It is what
     * `ProvenRead.revealJsonPaths` is kept (unread) for, and [All] carries the live rejection that sent both
     * axes back to a whole-response reveal. Flipping to this again means flipping
     * [ProvenReadSpec.revealResponseHeaders] to `true` in the same change, or the presentation loses the
     * header/body separator the verifier splits on.
     *
     * As of the vendored prover `f7d40de`, each path is revealed **with the key path that names it**, plus the
     * enclosing braces and commas — a value on its own proves those bytes were in the authenticated response,
     * but nothing ties them to the field they came from. Two consequences for whoever consumes the
     * presentation, and the first is the reason this is not live yet:
     *  - the revealed body is **not** `JSON.parse`-able: withheld siblings come back as `'#'` sentinel runs.
     *    The structure is recoverable (braces and commas are revealed), but the bytes are not JSON, so the
     *    verifier has to match the bound fields textually — which is the backend answer this is blocked on;
     *  - a path that **indexes into an array reveals the whole array** — the notary never commits array
     *    elements individually, so an element cannot be proven on its own. See `NotaryConfig.historyRead`,
     *    whose `response.trades.0` therefore discloses every row.
     *
     * ⚠️ The previous artifact (`d9dcb58`) resolved a path to the matched `KeyValue`'s **value** span alone, so
     * a leaf disclosed bare keyless bytes — the hole recorded on [All] — and both axes were pointed at the
     * *object* holding the bound fields purely to get field names into the presentation. That is why the
     * configured paths still name objects rather than the two bound fields, and it now costs disclosure it no
     * longer buys. Narrowing to leaves is open; it is a disclosure decision needing a live re-measurement,
     * not part of an artifact refresh.
     *
     * A path that does not resolve fails the proof outright (`JSON path not found`) rather than disclosing
     * less, so these track a response shape and belong with the code, not in remote config.
     */
    data class JsonPaths(val paths: List<String>) : ResponseBodyReveal {
        init {
            // A withheld target AND an empty path list produce a well-formed attestation binding no trade at
            // all — the one failure a verifier cannot detect, because there is nothing to disagree with.
            require(paths.isNotEmpty()) { "a JsonPaths reveal with no paths would bind no trade id" }
        }
    }
}

/**
 * One request header the proven read must **send**, as a name plus a value *template*.
 *
 * A template rather than a value, for the reason [ProvenReadSpec] is credential-free at all: the cookie that
 * authenticates a community read is filled in at the IO edge ([COOKIE_PLACEHOLDER]), so the secret never
 * becomes a field of a pure value and cannot reach a log, a crash report or a `toString`.
 *
 * Sending a header is not the same as disclosing it. Every header named here whose template carries a
 * credential slot must also appear in [ProvenReadSpec.redactRequestHeaderValues] — the prover reveals every
 * request header **in full** unless its name is listed there. `SteamProofReadMapper` guarantees the pairing,
 * and `SteamProofReadMapperTest` asserts it over every kind, because getting it wrong is silent: the proof
 * still verifies, it just published the session.
 */
data class ProvenRequestHeader(val name: String, val valueTemplate: String)

/**
 * A pure, platform-agnostic description of the single Steam read to prove for a decisive transition:
 * *what* request to issue and *what* to reveal vs redact in the resulting TLSN `Presentation`.
 * Produced by [SteamProofReadMapper]; consumed by every platform's `NotaryProver` actual (the web
 * WASM prover today, iOS/Android provers later) — nothing platform-specific lives here.
 *
 * Audit boundary is structural, and [revealRequestTarget] is the single place the current model is stated:
 * the read authenticates by query parameter, so the whole [path] is withheld from the proof and the trade
 * binding is disclosed out of the response instead. The secret value never appears in this type at all —
 * [path] carries only [TOKEN_PLACEHOLDER], which the IO edge fills. [redactRequestHeaderValues] remains the
 * header-shaped counterpart, kept non-empty so a header added later cannot leak by omission.
 *
 * Every *disclosure* field here has exactly one counterpart in the prover's reveal policy (the rest
 * describe the request to issue), so that mapping is total — a policy field with no source here would
 * be a silent disclosure decision made at the IO edge.
 */
data class ProvenReadSpec(
    /** The TLS `server_name` to prove against (bound into the attestation), e.g. `api.steampowered.com`. */
    val serverName: String,
    /** HTTP method, e.g. `GET`. */
    val method: String,
    /**
     * Request path + query for the read to issue.
     *
     * **May still contain [TOKEN_PLACEHOLDER]**, which the platform actual substitutes with the Steam access
     * token immediately before issuing. That is what keeps this type credential-free (see the class doc): the
     * secret is never a field here, so it cannot reach a log, a crash report or a `toString`.
     * It is the query-parameter analogue of [redactRequestHeaderValues] — that names a header whose value the
     * IO edge fills, this marks a query slot the IO edge fills.
     *
     * NOT revealed when [revealRequestTarget] is `false`, which is the case for every token-authed read.
     */
    val path: String,
    /** Request header names whose **values** are redacted from the proof (e.g. `cookie`). */
    val redactRequestHeaderValues: List<String>,
    /**
     * Whether response headers are disclosed **on their own**. `false` for both axes — nothing here asks for
     * them.
     *
     * It is no longer the whole story, and that is the one thing to know about this field:
     * [ResponseBodyReveal.All] reveals a single span covering the entire response, headers included, so under
     * that mode the headers are published whatever this says. That was checked before switching rather than
     * assumed — `api.steampowered.com` answers these reads with `server`, `content-type`, `expires`, `date`,
     * `content-length` (plus `connection` on HTTP/1.1) and **no `set-cookie`**, so there is nothing sensitive
     * in the span. Re-measure before proving a *different* host through here.
     *
     * Which also means this field is **not** the disclosure decision it looks like — it is load-bearing for
     * *framing*. Under [ResponseBodyReveal.JsonPaths] the header spans carry the CRLF that closes the header
     * block, so `false` there costs the verifier the `\r\n\r\n` it splits on; see [ResponseBodyReveal.All].
     * Any narrowing away from `All` flips this to `true` in the same change.
     */
    val revealResponseHeaders: Boolean,
    /**
     * How much of the response body is disclosed. With [revealRequestTarget] `false` this is the only thing
     * that identifies the trade a proven status belongs to, which is why [ResponseBodyReveal] has no
     * "disclose nothing" case at all.
     */
    val responseBodyReveal: ResponseBodyReveal,
    /**
     * Whether the request line's target — the whole **path + query** span — is disclosed. `false` for both
     * trade axes, because both authenticate with the Steam JWT as `?access_token=` and disclosure is
     * all-or-nothing (the prover has no per-parameter elision): revealing the target would put the credential
     * inside the attestation.
     *
     * The binding it would otherwise have provided comes from [responseBodyReveal] instead, which discloses
     * the trade id out of the *response*. That is why [ResponseBodyReveal] cannot express "disclose nothing":
     * a withheld target with nothing revealed from the body would be an attestation that proves a status
     * belonging to no identifiable trade.
     *
     * Deliberately has **no default**: this is a disclosure decision, and the compiler is the only thing
     * that can force each new read to make it consciously.
     */
    val revealRequestTarget: Boolean,
    /**
     * Request headers to **send**, or empty for a read that adds none (every token-authed `IEconService`
     * read — they authenticate by query parameter).
     *
     * Empty is the historical behaviour and stays byte-for-byte: the IO edge writes no `headers` key at all
     * rather than an empty array. Non-empty exists for the cookie-authenticated community surface, which the
     * prover could not reach before because it sent no headers whatsoever.
     */
    val sendHeaders: List<ProvenRequestHeader> = emptyList(),
    /**
     * Form body to send, or `null` for a GET.
     *
     * May carry [SESSION_ID_PLACEHOLDER], filled at the IO edge. **Disclosed by the proof** — see that
     * placeholder's doc: the prover has no request-body reveal control, so this is the one part of a proven
     * request that cannot be withheld.
     */
    val body: String? = null,
    /**
     * Per-read override of `NotaryConfig.maxSentData` / `maxRecvData`, or `null` to keep the global value.
     *
     * Per-read rather than global because both caps cost real MPC pre-processing — a live session was
     * measured at 42 MB uploaded for a 717 B request — so a community write that needs a larger send budget
     * must not make every trade-axis proof pay for it.
     */
    val maxSentDataOverride: Int? = null,
    val maxRecvDataOverride: Int? = null,
    /**
     * The online-decryption budget for this read, **already resolved** — not an override.
     *
     * Named without the `Override` suffix the two above carry precisely because it does not behave like them.
     * Those are per-*read-kind* replacements sourced from the read template, applied as `spec.x ?: config.x`
     * at the IO edge. This is a per-*response* decision — the online budget has to cover the response head, and
     * a `GetTradeOffer` body grows with the offer's item count — resolved by [SteamProofReadMapper] as
     * `maxOf(binding.minOnlineBudget, config.maxRecvDataOnline)` so that what a refusal taught can raise the
     * configured value but never lower it.
     *
     * Resolving in the mapper rather than in each `NotaryProver` actual keeps that `maxOf` in one pure,
     * testable place: an actual reads a number, and a second platform's prover cannot re-derive the rule
     * differently from a comment.
     */
    val maxRecvDataOnline: Int,
) {
    /**
     * Whether the IO edge must resolve a Steam **web session** cookie for this request.
     *
     * Derived from the headers rather than carried as a flag, so it cannot disagree with them: the cookie is
     * needed exactly when some header asks for it. A flag would let a spec declare a cookie it never sends, or
     * send a header the IO edge never fills.
     */
    val needsSessionCookie: Boolean get() = sendHeaders.any { COOKIE_PLACEHOLDER in it.valueTemplate }

    /** Whether the IO edge must resolve the `sessionid` anti-CSRF token — true exactly for the writes. */
    val needsSessionId: Boolean get() = body?.contains(SESSION_ID_PLACEHOLDER) == true

    /**
     * Whether the IO edge must substitute the Steam **access token** into [path] — true for every read that
     * authenticates by query parameter, i.e. both trade axes and every other `api.steampowered.com` kind.
     *
     * The third of these derived predicates, and it arrived last only because nothing had needed to *ask*
     * before: the IO edge substitutes unconditionally, so the question first came up when
     * [ProvenSentBudget] had to know whether the token length was the one unknown in the request. Derived
     * from the path for the same reason its two siblings are derived from what they describe — a flag could
     * claim a slot the path does not have, and the substitution would then silently do nothing.
     */
    val needsAccessToken: Boolean get() = TOKEN_PLACEHOLDER in path
}
