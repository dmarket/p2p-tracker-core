package com.dmarket.p2p.tracker.port.marketplace

import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.marketplace.Deal
import com.dmarket.p2p.tracker.model.marketplace.DealActionResult
import com.dmarket.p2p.tracker.model.marketplace.DirectiveAck
import com.dmarket.p2p.tracker.model.marketplace.DirectiveOutcome
import com.dmarket.p2p.tracker.model.marketplace.HeartbeatRequest
import com.dmarket.p2p.tracker.model.marketplace.HeartbeatResponse
import com.dmarket.p2p.tracker.model.marketplace.InventoryAck
import com.dmarket.p2p.tracker.model.marketplace.InventoryReport
import com.dmarket.p2p.tracker.model.marketplace.ProofResult
import com.dmarket.p2p.tracker.model.marketplace.ProofSubmission
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusReport
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusResult

/**
 * The DMarket backend, exposed as the golden **C1 trade-tracker** REST endpoints the seller plugin
 * talks to via the exchange-gateway, base `/exchange/v1/p2p/ext/`. The backend derives the account
 * from the Bearer token, so **no method here sends an `account_id`**.
 *
 * Audit boundary, enforced structurally: **no method here accepts a Steam credential.** The Steam JWT
 * is device-only and can never be passed to the marketplace, by construction.
 *
 * Any method may throw [MarketplaceUnauthorizedException] when the DMarket session is missing or
 * invalid and could not be (re)established — the "missing connection" signal the loop surfaces.
 */
interface MarketplaceClient {
    /**
     * `POST /heartbeat` — presence (+`device_id`) in, `active_tracking[]` + `directives[]` +
     * `ttl_seconds` out. The sole presence + work-dispatch call; carries no inventory data.
     */
    suspend fun heartbeat(request: HeartbeatRequest): HeartbeatResponse

    /** `POST /trade-events` — batch raw Steam status codes (no proof); one result per report. */
    suspend fun reportTradeStatus(reports: List<TradeStatusReport>): List<TradeStatusResult>

    /**
     * `POST /notary` — a TLSN proof for a decisive transition (decisive set only). The verdict is the
     * backend's alone: a delivered `verified=false` is terminal for that transition, because
     * resubmitting the identical presentation cannot change the answer.
     *
     * A refusal by status (the deal closed or gone) throws [MarketplaceServerErrorException] with that status
     * and the gateway's [MarketplaceServerErrorException.errorCode], rather than folding it into `verified=false`.
     */
    suspend fun submitProof(proof: ProofSubmission): ProofResult

    /**
     * `POST /trade-actions` — report a **batch** of `create_offer`/`cancel_offer` directive outcomes; each
     * accepted result releases that directive's lease. One [DirectiveAck] per submitted outcome, matched by
     * `directive_id`; an outcome with no result in the response counts as unaccepted.
     *
     * Batched because a single cycle can execute many directives (one `create_offer` per counterparty chain
     * step), and a POST per outcome made the report volume scale with the write volume.
     */
    suspend fun reportDirectives(outcomes: List<DirectiveOutcome>): List<DirectiveAck>

    /** `POST /inventory` — the R6 inventory snapshot (present asset ids); the backend computes the stale diff. */
    suspend fun reportInventory(report: InventoryReport): InventoryAck

    /**
     * `POST /p2p/deals/{id}/accept` — the seller COMMIT. This is a **C2 (DMarket app)** action, exposed
     * here only as a thin host convenience; it is not part of the tracker's directive loop.
     * [DealActionResult.applied] is `false` if the action arrived too late.
     */
    suspend fun acceptDeal(id: DealId): DealActionResult

    /** `GET /p2p/deals/{id}` — a single deal snapshot (C2 read; host convenience). */
    suspend fun getDeal(id: DealId): Deal
}

/**
 * Thrown by a [MarketplaceClient] call on HTTP 401 when auth could not be (re)established — the DMarket
 * "missing connection" signal (the bearer token is absent, or invalid and un-refreshable). Distinct
 * from a transient network/5xx failure so the loop can set its missing-connection state rather than
 * silently no-op the cycle. Lives here (not with the Ktor client) because the loop must **catch** it,
 * keeping the loop → domain dependency direction intact.
 */
class MarketplaceUnauthorizedException : RuntimeException("DMarket request unauthorized; no usable session")

/**
 * Thrown by a [MarketplaceClient] call on a **non-401** HTTP failure — a deterministic 4xx the gateway
 * returns for a bad/absent route (e.g. 404, or 400/403/409) or a 5xx server error. Distinct from both
 * [MarketplaceUnauthorizedException] (a login problem the user can fix by re-authenticating) and a
 * transient transport/timeout failure, so the loop can surface a "DMarket is unreachable" state rather
 * than the misleading "you were logged out" prompt. [statusCode] is the HTTP status; [errorCode] the gateway's
 * machine code (`P2P_…`) when the call read one from the reply's body, `null` otherwise. Lives here (not
 * with the Ktor client) because the loop must **catch** it, keeping the loop → domain direction intact.
 */
class MarketplaceServerErrorException(val statusCode: Int, val errorCode: String? = null) :
    RuntimeException("DMarket request failed: HTTP $statusCode")

/**
 * Thrown by [MarketplaceClient.reportTradeStatus] when a batch sent as several requests (a body-size cap split
 * it) failed part-way: the first [deliveredCount] reports reached the backend and [results] answers exactly
 * those, one result per report in report order; the rest were never sent. [cause] is the failure that stopped it.
 *
 * Not a plain short result list, because the caller needs *delivery* and not only acceptance: an unproven
 * report spends its claim once the backend has heard it, and a report the backend never heard must not spend
 * one. A failure of the first request is not wrapped — nothing was delivered, so it propagates as is.
 */
class TradeStatusBatchInterruptedException(val deliveredCount: Int, val results: List<TradeStatusResult>, cause: Throwable) :
    RuntimeException("Trade status batch interrupted after $deliveredCount reports", cause)
