package com.dmarket.p2p.tracker.model

/**
 * A coarse, secret-free signal emitted at the nodal points of one tracker cycle, surfaced to a
 * [com.dmarket.p2p.tracker.port.host.EventObserver].
 *
 * **Why this exists alongside [NetworkExchange]:** the network observer can only tap the two Ktor
 * clients (Steam reads + DMarket calls). The Steam **write/inventory/scrape** actions run on raw
 * `window.fetch` (the JS engine cannot force `credentials: "include"`), so they bypass the Ktor tap.
 * These lifecycle events make those actions — and the loop's high-level progress — visible in a session
 * log without ever widening the network tap onto the raw-fetch surfaces (which would risk capturing a
 * `sessionid`/JWT from a fetch body).
 *
 * **Secret-free, and how that is enforced.** Every field here is an already-public id, an enum name, a
 * count or a boolean — with one class of exception that used to make the blanket claim false: the free-text
 * `reason` carried by [SteamReadFailed], [DirectiveReportFailed], [TradeStatusReportFailed],
 * [DealLookupFailed], [ProgressStoreFailed], [ProofFailed] and [CycleFailed]. Each is derived either from a failure — via
 * [com.dmarket.p2p.tracker.net.redactedSummary], which scrubs and length-caps — or from a **backend-supplied**
 * rejection string, which is scrubbed and capped by the same [com.dmarket.p2p.tracker.net.NetworkRedaction]
 * rules at the emitting site. That sits on top of the exceptions themselves being sanitized at the source
 * (see [com.dmarket.p2p.tracker.client.HttpStatusException] and the `exceptionsWithDebugInfo = false` posture
 * in [com.dmarket.p2p.tracker.wire.trackerJson]).
 *
 * A host may forward this stream to a warehouse, so anything added here must hold to the same standard: no
 * raw `Throwable.message`, and no unbounded string from a remote party, may ever be assigned to a field.
 */
sealed interface LifecycleEvent {
    /** One [com.dmarket.p2p.tracker.loop] cycle began. */
    data object CycleStarted : LifecycleEvent

    /** A `/heartbeat` round-trip completed; carries the backend's TTL and what it returned. */
    data class HeartbeatSent(val ttlSeconds: Int, val trackingCount: Int, val directiveCount: Int) : LifecycleEvent

    /** A leased directive finished executing. [kind] = `create_offer`/`cancel_offer`/`report_inventory`. */
    data class DirectiveExecuted(val kind: String, val status: String, val steamOfferId: String? = null) : LifecycleEvent

    /**
     * A directive-outcome report (`/trade-actions` or `/inventory`) was rejected by the backend or
     * failed outright (network error, undecodable ack). Without this the loop's `runCatching` around
     * the report swallows the failure and the session log shows a "successful" directive whose lease
     * was never released. [reason] is the backend's rejection reason or the failure message.
     */
    data class DirectiveReportFailed(val kind: String, val directiveId: String, val reason: String? = null) : LifecycleEvent

    /**
     * The backend re-served an already-handled directive (our earlier `/trade-actions` report never
     * landed, so its lease expired and it was re-leased) and the stored outcome was re-*sent* — the
     * Steam write is never re-executed. [status] is the stored [DirectiveOutcome]'s status name.
     */
    data class DirectiveOutcomeResent(val kind: String, val directiveId: String, val status: String, val accepted: Boolean) :
        LifecycleEvent

    /**
     * A re-served directive was already handled but no stored outcome exists to re-send (recorded by a
     * pre-outcome-persistence version, or already pruned after an accepted report) — skipped.
     */
    data class HandledDirectiveSkipped(val kind: String, val directiveId: String) : LifecycleEvent

    /**
     * A directive of a **known** action was dropped because its payload is malformed for that action
     * (e.g. a `create_offer` missing a field). Distinct from an [DirectiveAction.UNKNOWN] action, which
     * is silently forward-compatible: a malformed known directive is re-leased on every heartbeat, so
     * without this event the deal stalls invisibly. [reason] names the missing/invalid field. [kind] is
     * the directive's action wire name.
     */
    data class DirectiveDropped(val kind: String, val directiveId: String, val reason: String) : LifecycleEvent

    /**
     * A non-idempotent Steam write (`create_offer` / `cancel_offer`) was **suppressed before touching
     * Steam** because this device already holds a claim on that write for that deal — the deal-keyed
     * duplicate guard ([DealWriteClaim]) firing. [phase] is the standing claim's [ClaimPhase] name
     * (`COMPLETED` → the first result was replayed to the caller; `IN_FLIGHT` → the first write is still
     * running), and [steamOfferId] is the already-created offer when there is one.
     *
     * This is the signal that a *caller* asked twice — a repeated host request or a backend re-lease under
     * a fresh `directive_id`. Without it the suppression is invisible and the duplicate request looks
     * like a normal success in the session log.
     */
    data class DuplicateWriteSuppressed(
        val kind: String,
        val dealId: String,
        val directiveId: String,
        val phase: String,
        val steamOfferId: String? = null,
    ) : LifecycleEvent

    /**
     * A leased `create_offer` was **not attempted this cycle** — the create surface's own back-pressure
     * firing, not a Steam failure. [reason] names which limit deferred it (the surface or the partner is
     * cooling down after a Steam refusal, a per-partner / per-cycle cap, or the concurrent-chain limit), and
     * [retryAfterSeconds] is set when the reason is a cooldown with a known deadline.
     *
     * Nothing was written and (pending agreement with the backend) nothing was reported, so the directive is
     * simply re-leased later. Without this event that is indistinguishable from a client silently ignoring
     * its work: the observed session leased 26 creates and would have shown one write and no explanation.
     * See [com.dmarket.p2p.tracker.policy.CreateChainPlanner].
     */
    data class SteamWriteDeferred(
        val kind: String,
        val directiveId: String,
        val reason: String,
        val dealId: String? = null,
        val retryAfterSeconds: Int? = null,
    ) : LifecycleEvent

    /**
     * A counterparty's `create_offer` chain **stopped early**: creates are grouped per partner and run one
     * at a time within a group, and the first failure ends that group for the cycle so the client learns a
     * refusal once instead of once per leased directive. [skipped] is how many of that chain's creates were
     * abandoned, [reason] why it stopped. Sibling chains are unaffected — that isolation is the point.
     *
     * [partnerSteamId] is a public steamid64 (the same identifier [LinkedSteamIdMismatch] already carries),
     * never a credential.
     */
    data class CreateChainStopped(val partnerSteamId: String, val directiveId: String, val reason: String, val skipped: Int) :
        LifecycleEvent

    /**
     * A Steam read axis threw and its result was substituted with an empty read for this cycle. Auth
     * failures self-heal via the refreshing read client / re-login signal; this surfaces a *persistent*
     * non-auth failure (5xx, parse error) that would otherwise produce silent no-op ticks. [axis] =
     * `offer`/`history`; [reason] is the failure message.
     */
    data class SteamReadFailed(val axis: String, val reason: String? = null) : LifecycleEvent

    /**
     * A deal whose `watch` includes the history axis produced **no** matching row while the account-wide
     * `GetTradeHistory` read did return rows — the asset-id join found nothing, so that deal's history axis
     * contributed no observation at all.
     *
     * This is the event whose absence made a real rollback undiagnosable: a deal whose transfer is due (the
     * offer axis says the trade was accepted) but which correlates to nothing is *anomalous*, and without a
     * signal it is indistinguishable from a deal that is merely still in flight, from a deduped code, and
     * from an aborted cycle. [rows] is how many rows the read returned; [refetched] says whether the deal's
     * cached asset id was discarded and re-read from the backend in response.
     */
    data class HistoryCorrelationMiss(val dealId: String, val rows: Int, val refetched: Boolean) : LifecycleEvent

    /**
     * The deal read that supplies the history axis's join key (`GET /p2p/deals/{id}`) failed, so the deal's
     * history axis is dark for this cycle. Previously swallowed whole — the failure and its cause were both
     * dropped, for every history-watched deal at once. [reason] is a redacted, length-capped summary.
     */
    data class DealLookupFailed(val dealId: String, val reason: String? = null) : LifecycleEvent

    /**
     * The per-deal dedup/progress store could not be read or written, so the watch pass was skipped rather
     * than run against a substituted-empty baseline (which would re-report every watched code).
     *
     * [operation] names the store call (e.g. `loadReported`). Deliberately an event and not a throw: the
     * unguarded read used to unwind the whole cycle **after** the Steam reads and before any report, leaving
     * no artifact anywhere.
     */
    data class ProgressStoreFailed(val operation: String, val reason: String? = null) : LifecycleEvent

    /**
     * The verdict of one watch pass, emitted **before** the early return an empty plan takes — so a cycle
     * that reports nothing still says *why* it reported nothing.
     *
     * [observed] is deals with at least one axis read, [historyObserved] those with a correlated transfer,
     * [uncorrelated] those whose history axis found no row, [planned] the reports about to be sent, and
     * [suppressed] the observed axes whose raw code equalled the stored baseline (i.e. ordinary dedup).
     * `planned == 0 && suppressed > 0` reads "nothing changed"; `planned == 0 && uncorrelated > 0` reads
     * "we could not see the axis at all" — the two failure modes this event exists to separate.
     *
     * [demanded] is the backend freshness marks this pass is answering
     * ([com.dmarket.p2p.tracker.engine.ProofFreshness]). Without it this event LIES on exactly the cycle
     * where it matters most: a demanded re-attestation plans no report and its unchanged code lands in
     * [suppressed], so `planned == 0 && suppressed > 0` — which the paragraph above defines as "nothing
     * changed" — would print while a money-critical MPC session ran. Required rather than defaulted, like
     * every other field here: a silent default is how a new counter goes unwired.
     */
    data class WatchSummary(
        val watched: Int,
        val observed: Int,
        val historyObserved: Int,
        val uncorrelated: Int,
        val planned: Int,
        val suppressed: Int,
        val demanded: Int,
    ) : LifecycleEvent

    /** A raw Steam status code was reported on `/trade-events`. */
    data class TradeStatusReported(val dealId: String, val source: String, val steamStatusCode: Int) : LifecycleEvent

    /**
     * A raw status report did **not** land: the batch call failed, the backend rejected this report
     * (`accepted=false`), or the response carried no result that can be matched to it.
     *
     * Its code is therefore NOT entered into the dedup baseline and will be re-detected next tick — which is
     * exactly why the failure has to be visible: a rejected or unmatched report is otherwise a silent
     * `reportsSent=0` cycle, indistinguishable from having nothing to say. [reason] is the backend's
     * rejection reason or a redacted, length-capped failure summary.
     */
    data class TradeStatusReportFailed(val dealId: String, val source: String, val steamStatusCode: Int, val reason: String? = null) :
        LifecycleEvent

    /**
     * A decisive transition's TLSN proof was generated **and** delivered to `/notary`. [verified] carries the
     * backend's verdict, and `false` is terminal: the bytes landed, the backend rejected them, and
     * resubmitting the identical proof cannot change the answer — so nothing retries it. Without this event
     * the two outcomes are indistinguishable from outside, because only the accepted one moves
     * [CycleCompleted.proofsSubmitted].
     *
     * [reason] is the backend's own rejection string, scrubbed and length-capped like every other remote text
     * on this stream. It existed on the wire and in `ProofResult` all along and was dropped one expression
     * before this event, which is why a deal that died to `"empty proof_payload"` — the backend naming the
     * exact defect — read here as an unexplained `verified=false`.
     *
     * [prover] names which implementation produced the payload: `"noop"` submits an empty one BY DESIGN (no
     * notary URL configured), `"tlsn"` ran a real MPC proof. Without it the two are indistinguishable, and
     * they call for opposite responses — configure the client, versus investigate the proof.
     */
    data class ProofSubmitted(
        val dealId: String,
        val source: String,
        val verified: Boolean,
        val reason: String? = null,
        val prover: String? = null,
        /**
         * Whether this proof answered a backend freshness mark rather than a status change
         * ([FreshProofDemanded]). Two reasons it has to be on the frame: `proofsSubmitted` is only
         * incremented in the verified branch, so a demand refused on a ladder reports `0` in every
         * [CycleCompleted]; and a demanded proof arrives with no accompanying report, which is otherwise the
         * signature of nothing having happened at all.
         */
        val demanded: Boolean = false,
    ) : LifecycleEvent

    /**
     * A decisive transition's proof never reached the backend — MPC generation threw, the notary handshake
     * failed, or `/notary` itself did.
     *
     * The deal's raw code is deliberately withheld from the dedup baseline so the transition is re-detected
     * next tick, which is exactly why the failure needs a voice: without one it reads as an unchanging
     * `proofsSubmitted=0` plus a [TradeStatusReported] that repeats every cycle for no stated reason — the
     * same "silent no-op tick" shape [SteamReadFailed] and [TradeStatusReportFailed] exist to break.
     *
     * [reason] is a redacted, length-capped summary, and it is the most specific signal the client can offer:
     * a browser cannot observe *why* a notary socket refused (every rejection surfaces as close 1006), so the
     * prover deliberately reports the whole class rather than guessing at a cause.
     */
    data class ProofFailed(
        val dealId: String,
        val source: String,
        val reason: String? = null,
        /** Whether the attempt answered a freshness mark — see [ProofSubmitted.demanded]. */
        val demanded: Boolean = false,
    ) : LifecycleEvent

    /**
     * The backend demanded a freshly-attested proof of this deal's trade, and this pass intends to answer it
     * (DMA-280). Emitted for every due demand **before** the mint gate, so it is on record even when the gate
     * then refuses — the [ProofSuppressed] that follows carries no mark.
     *
     * **It is the only frame in this stream that carries either wire value**, which is the whole justification
     * for a distinct event rather than a flag: an exported session log otherwise cannot answer "did the mark
     * reach this device, for which trade, and which mark" — the one question a stranded payout is investigated
     * with, and the shape of blindness this event stream has repeatedly had to be extended to break.
     * [tradeId] doubles as evidence the binding resolved to the backend's own id rather than a local
     * correlation.
     *
     * No `source`: the axis is a constant of a demand (`steam_trade_id` addresses one Steam endpoint), and the
     * frames that follow name it anyway. Not a problem signal on its own — what happened is the next frame.
     */
    data class FreshProofDemanded(val dealId: String, val tradeId: String, val proveAfter: String) : LifecycleEvent

    /**
     * A decisive transition's proof was DUE but deliberately not spent. Four reasons, and [reason] names which:
     * an identical proof was already **refused** (nothing to gain, and the report is withheld with it), one was
     * already **accepted** and is still inside its reuse window (the backend holds corroboration, so the report
     * goes out and only the MPC session is skipped), the **prover is parked** after repeated generation
     * failures (then [retryAfterSeconds] carries the deadline), or this cycle's **proving budget** is spent
     * because the next heartbeat is due. See the proof latches in
     * [com.dmarket.p2p.tracker.loop.TradeTrackerLoop], `NotaryConfig.acceptedProofTtlMs` and
     * [com.dmarket.p2p.tracker.policy.NotaryProofThrottle].
     *
     * Every reason means the deal is not moving — a transition only stays live while its report keeps being
     * refused — so this event is a problem signal in every form, not merely informational.
     *
     * The event exists because the suppression is otherwise perfectly invisible, and it is invisible in the
     * one state that looks exactly like a broken client: the backend keeps refusing the report with
     * `P2P_PROOF_REQUIRED`, the deal never settles, `proofsSubmitted` stays 0, and — unlike every other reason
     * a proof does not happen — there is no [ProofSubmitted] and no [ProofFailed] either. A reader of the
     * session log could not distinguish "we already tried and were told no" from "the prover was never
     * invoked", which are opposite findings: one is a verdict to take to the backend, the other is a client
     * bug. Diagnosing it took a browser console and a mental replay of two earlier cycles.
     *
     * Emitted every cycle the transition stays live, on purpose: it costs no network and it is the only thing
     * that says the loop is deliberately idle rather than stuck. [reason] is a fixed client-side string, not
     * remote text.
     *
     * [retryAfterSeconds] is set only for the parked-prover reason, and is the typed counterpart of
     * [SteamWriteDeferred]'s field of the same name: a host rendering "retrying in N s" must not have to parse
     * [reason] to find the number. `null` for every reason that has no deadline to report.
     */
    data class ProofSuppressed(val dealId: String, val source: String, val reason: String, val retryAfterSeconds: Int? = null) :
        LifecycleEvent

    /**
     * A status report was NOT sent this cycle because the proof for that exact transition has not verified.
     *
     * Proofs run before the reports they corroborate, because a backend enforcing `proof_required` refuses
     * the report until the proof lands — so sending it first was one guaranteed-refused round trip per cycle
     * per deal. The report is withheld, never dropped: the dedup baseline is only persisted for accepted
     * codes, so the transition is re-detected and re-attempted on the next cycle.
     *
     * The event exists because the alternative to a refused-report event is *no* event: without it a deal
     * waiting on a proof looks identical to a cycle that observed nothing, which is the single failure shape
     * this event stream has repeatedly had to be extended to break. [reason] is a fixed client-side string.
     */
    data class TradeStatusReportDeferred(val dealId: String, val source: String, val steamStatusCode: Int, val reason: String) :
        LifecycleEvent

    /**
     * A cycle ended in a throw rather than a verdict. The driver still re-arms its next wake — the point of
     * the event is that an aborted cycle used to be indistinguishable from a quiet one (on web it reached
     * only the console's final-resort handler, and it took the alarm re-arm down with it). [reason] is a
     * redacted, length-capped summary.
     */
    data class CycleFailed(val reason: String? = null) : LifecycleEvent

    /** A background credential refresh resolved. [axis] = `steam`/`marketplace`. */
    data class CredentialRefreshed(val axis: String, val ok: Boolean) : LifecycleEvent

    /** A re-login is required because a session is no longer authenticated. [axis] = `steam`/`marketplace`. */
    data class ReLoginNeeded(val axis: String) : LifecycleEvent

    /**
     * A DMarket C1 call failed with a **non-401** HTTP status — the request reached the gateway but it
     * returned [statusCode] (a deterministic 4xx such as 404, or a persistent 5xx), so the cycle can't
     * complete. Distinct from [ReLoginNeeded]/missing-connection (a login problem): the token is fine,
     * DMarket itself is erroring. Surfaces a "can't reach DMarket" state instead of a silent no-op tick.
     * [endpoint] names the failing call (e.g. `heartbeat`); [statusCode] is the HTTP status — both public.
     */
    data class MarketplaceServerError(val endpoint: String, val statusCode: Int) : LifecycleEvent

    /**
     * The Steam id the backend has linked to this DMarket account ([linkedSteamId]) disagrees with the
     * Steam id of the token the client holds ([tokenSteamId]) — a wrong-account session. The loop blocks
     * all Steam activity until a later heartbeat sees them agree. Both fields are public Steam ids (the
     * host shows a "log into the correct Steam account" prompt); no credential is carried.
     */
    data class LinkedSteamIdMismatch(val linkedSteamId: String, val tokenSteamId: String) : LifecycleEvent

    /**
     * A Steam **write** ([kind]: `create_offer` / `cancel_offer`) was refused before any request, because
     * the browser's Steam web session belongs to a different account than the token the client holds
     * ([tokenSteamId]).
     *
     * The other half of [LinkedSteamIdMismatch], on the axis that one cannot see: the write surfaces
     * authenticate with the ambient `steamLoginSecure` cookie, not with the token, so the two ids can
     * agree while the cookie is somebody else's. The signed-in account's own Steam id is deliberately
     * **not** carried — the block needs only that it is not ours, and whoever else is signed into the
     * browser is not this client's to report.
     */
    data class SteamSessionAccountMismatch(val kind: String, val tokenSteamId: String) : LifecycleEvent

    /** One cycle finished; mirrors `loop.TickOutcome`. */
    data class CycleCompleted(val directivesExecuted: Int, val reportsSent: Int, val proofsSubmitted: Int, val watching: Int) :
        LifecycleEvent
}
