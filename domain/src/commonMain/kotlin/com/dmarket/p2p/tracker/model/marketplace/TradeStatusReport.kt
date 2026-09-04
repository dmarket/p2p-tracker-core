package com.dmarket.p2p.tracker.model.marketplace

import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.SteamId
import kotlin.time.Instant

/**
 * Which Steam axis a [TradeStatusReport] came from — they share a numeric code space, so the backend
 * needs the source to disambiguate.
 *
 * - [OFFER] — `ETradeOfferState` (the offer lifecycle, 1-11).
 * - [HISTORY] — `ETradeStatus` (the transfer axis; reversal = `12`).
 */
enum class TradeStatusSource(val wireName: String) {
    OFFER("offer"),
    HISTORY("history"),
}

/**
 * One raw Steam status observation, reported (batched) on `POST /trade-events` (golden
 * `TradeStatusReport`). **No proof, no derived verdict** — just the
 * raw [steamStatusCode] keyed on [dealId]; the backend maps it and advances the deal. [clientTime] is
 * reconciled against `server_time` (≤5 min skew).
 */
data class TradeStatusReport(
    val dealId: DealId,
    val source: TradeStatusSource,
    val steamStatusCode: Int,
    val clientTime: Instant,
    /**
     * Who reversed the trade, on **history `12` reports only** — see
     * [com.dmarket.p2p.tracker.model.steam.ReversalAttribution]. `null` means "undecided", never a fallback
     * actor: the backend parks the deal with escrow untouched rather than inferring an actor from absence.
     *
     * Carried on the wire as `reversalInitiatorSteamId` (omitted when `null`, so an ordinary status report
     * is unchanged). The backend treats it as a **claim** — validated as a steamid64, admissible as an
     * input to attribution, never as authorization to forfeit anyone's money.
     */
    val reversalInitiatorSteamId: SteamId? = null,
    /**
     * End of this trade's Steam Trade-Protection window, on **history reports only** — never the offer axis,
     * which has no such field. Read from `time_settlement` on the correlated
     * [com.dmarket.p2p.tracker.model.steam.SteamTransfer]; `null` (absent, `0`, or non-positive) is omitted
     * rather than sent as epoch 0.
     *
     * Carried on the wire as `settlementTime`, an RFC3339 string. Deliberately **unvalidated here**: the
     * backend bounds it (`timestamp.within` ±30 days) and applies extend-only against the window it holds.
     * A client-side copy of that would only suppress values the backend would have accepted, and extend-only
     * needs a recorded window this side does not hold — so do not "fix" this by adding a bound.
     *
     * It rides every history report rather than only the decisive ones because Steam **clears**
     * `time_settlement` on the row it flips to `12`: a rollback structurally carries none, so an earlier
     * read is the only chance to capture the window at all.
     */
    val settlementTime: Instant? = null,
)

/**
 * The backend's per-report result within a `ReportTradeStatus` batch.
 *
 * [source] is which axis the result answers for, when the backend says so — `null` means "no opinion", and
 * the result then matches its report by [dealId] alone. It matters because one batch legitimately carries
 * both axes of the same deal: a result set cannot be zipped onto the request positionally (a short or
 * reordered response would mark one axis accepted off another's acknowledgement), so the pairing is done by
 * these fields — see [com.dmarket.p2p.tracker.engine.ReportAcknowledgement].
 */
data class TradeStatusResult(val dealId: DealId, val accepted: Boolean, val reason: String? = null, val source: TradeStatusSource? = null)

/**
 * A TLSN proof for a decisive transition, submitted on the separate `POST /notary` channel (golden
 * `SubmitProofRequest`). [proofPayload] is the opaque base64 of a
 * `postcard`-encoded `Presentation` produced by [com.dmarket.p2p.tracker.port.notary.NotaryProver]; the Steam
 * credential is redacted from it (audit boundary). There is no `proof_type` — the proven transcript
 * carries the Steam URL, so the backend reads which transition it covers. Rides only the decisive set
 * (offer `2`/`3`/`6`, history `12`) and only when the deal is `proof_required`.
 */
data class ProofSubmission(val dealId: DealId, val proofPayload: String)

/** The backend's `/notary` result for a submitted proof. */
data class ProofResult(val dealId: DealId, val verified: Boolean, val reason: String? = null)

/**
 * The result of the C2 seller-COMMIT call (`POST /p2p/deals/{id}/accept`). Kept as a thin host
 * convenience — COMMIT is a C2 (DMarket app) action, not part of the tracker's directive loop.
 * [applied] is `false` when the action arrived too late (e.g. the cancel window already closed).
 */
data class DealActionResult(val state: P2PDealState, val applied: Boolean, val reasonCode: String? = null)
