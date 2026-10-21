package com.dmarket.p2p.sdk.health

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How much a problem stops. [wireName] is what `health` reports; [rank] orders them, worst last. */
enum class Severity(val wireName: String, val rank: Int) {
    /** Nothing is blocked yet. */
    INFO("info", 0),

    /** The SDK heartbeats, but some or all Steam work or proofs are blocked. */
    DEGRADED("degraded", 1),

    /** The SDK cannot heartbeat as the account, so DMarket counts it absent. `GET /v1/health` answers 503. */
    DOWN("down", 2),
}

/**
 * The closed vocabulary the SDK reports problems in — on `GET /v1/health` and in the logs, and nowhere else:
 * no problem code travels to DMarket.
 *
 * There is no code for an unknown Steam binding: it is unknown only for a cause one of these already names, and
 * every heartbeat that answers carries the linked account.
 */
enum class ProblemCode(val severity: Severity) {
    /** No Trading API key pushed since start: the SDK does not heartbeat. */
    DMARKET_KEY_MISSING(Severity.DOWN),

    /** A pushed key signs for another DMarket account and was refused; the held key still serves its own. */
    DMARKET_KEY_ACCOUNT_CHANGED(Severity.DEGRADED),

    /** DMarket refused the pushed key (invalid, revoked or banned). */
    DMARKET_AUTH_FAILED(Severity.DOWN),

    /** The host's clock is off DMarket's by more than a minute: past the signing window every signed call is refused. */
    CLOCK_SKEW(Severity.DOWN),

    /** No heartbeat has come back for five minutes: a network error, a timeout or a server error. */
    DMARKET_UNREACHABLE(Severity.DOWN),

    /** The DMarket account has no Steam account linked: link one on DMarket. */
    STEAM_NOT_LINKED(Severity.DOWN),

    /** No Steam token pushed since start. */
    STEAM_TOKEN_MISSING(Severity.DEGRADED),

    /** The held Steam token has expired: push a fresh one. */
    STEAM_TOKEN_EXPIRED(Severity.DEGRADED),

    /** Less than four hours left on the held Steam token; under thirty minutes no create is started. */
    STEAM_TOKEN_EXPIRING(Severity.DEGRADED),

    /** The Steam account of the token is not the one DMarket links to this key's account. */
    STEAM_WRONG_ACCOUNT(Severity.DEGRADED),

    /** Steam refused the session (revoked, or logged out elsewhere). */
    STEAM_SESSION_REJECTED(Severity.DEGRADED),

    /** Steam is throttling reads or offer creation. */
    STEAM_RATE_LIMITED(Severity.DEGRADED),

    /** Steam could not be read. */
    STEAM_UNREACHABLE(Severity.DEGRADED),

    /** Below DMarket's minimum SDK version. The SDK still finishes the deals it has. */
    SDK_UPDATE_REQUIRED(Severity.DEGRADED),

    /** DMarket has scheduled a minimum SDK version this one is below. */
    SDK_UPDATE_DUE(Severity.INFO),

    /** The notary cannot be reached, or is busy (a 503 before the upgrade, a close 1013 after it): proofs back off. */
    NOTARY_UNAVAILABLE(Severity.DEGRADED),

    /** The notary did not admit the session (a 403, or a close 4029 at the account's cap): proofs wait a minute. */
    NOTARY_NOT_ADMITTED(Severity.DEGRADED),

    /** A prover process was killed: out of memory, or past its deadline. The SDK lives and proves again later. */
    PROVER_KILLED(Severity.DEGRADED),

    /** The container's memory limit leaves less than one proof's peak beside the SDK. */
    PROVER_MEMORY_LOW(Severity.DEGRADED),

    /** A proof for a deal could not be produced or delivered. */
    PROOF_FAILED(Severity.DEGRADED),

    /** A `create_offer` or `cancel_offer` failed (item gone, trade hold, a Steam EResult). */
    DIRECTIVE_FAILED(Severity.DEGRADED),

    /**
     * DMarket leases offer writes to this SDK, which runs with `steamWrites: false`: they lapse to another device. A
     * leased cancel is named until its next issue is due, as DMarket issues it only now and then.
     */
    STEAM_WRITES_DISABLED(Severity.DEGRADED),

    /**
     * DMarket leased a `cancel_offer` when there was no usable Steam token to run it: the offer stays open on Steam,
     * where the seller could still confirm it. Named until the cancel runs, which a pushed token does at DMarket's next
     * issue of it, or for 14 days, when the offer lapses on Steam.
     */
    STEAM_CANCEL_PENDING(Severity.DEGRADED),

    /**
     * DMarket requires a proof for a tracked deal and this SDK has no prover (`prover.enabled: false`, or its files were
     * not found): the deal's decisive report is held back, and the deal does not advance until its deadline. Named until
     * the report goes or DMarket no longer tracks the deal.
     */
    PROVER_DISABLED(Severity.DEGRADED),
}

/** A deal a problem holds up. [deadline] is the time it lapses, when the SDK knows it. */
@Serializable
data class AffectedDeal(@SerialName("dealId") val dealId: String, @SerialName("deadline") val deadline: String? = null)

@Serializable
data class Problem(
    @SerialName("code") val code: ProblemCode,
    @SerialName("severity") val severity: String,
    @SerialName("since") val since: String,
    @SerialName("message") val message: String,
    @SerialName("affectedDeals") val affectedDeals: List<AffectedDeal> = emptyList(),
)

/**
 * The answer of `GET /v1/health`. [status] is the worst severity among the open problems: `down`, `degraded`, or
 * `ok` with none or with `info` ones only. Carries no secret and no part of one.
 */
@Serializable
data class Health(
    @SerialName("status") val status: String,
    @SerialName("problems") val problems: List<Problem>,
    @SerialName("steamTokenExpiresAt") val steamTokenExpiresAt: String? = null,
    @SerialName("lastHeartbeatAt") val lastHeartbeatAt: String? = null,
    @SerialName("version") val version: String,
) {
    /** Whether the SDK counts as absent to DMarket: `GET /v1/health` answers 503, for a container health check. */
    val isDown: Boolean get() = status == Severity.DOWN.wireName

    companion object {
        const val OK: String = "ok"
    }
}
