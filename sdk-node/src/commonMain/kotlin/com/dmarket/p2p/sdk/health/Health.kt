package com.dmarket.p2p.sdk.health

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The closed vocabulary the SDK reports problems in — on `GET /v1/health` and in the logs, and nowhere else:
 * no problem code travels to DMarket.
 *
 * [blocking] problems stop the SDK from acting at all (no heartbeat, or no Steam action); the rest degrade it.
 */
enum class ProblemCode(val blocking: Boolean) {
    /** No Trading API key pushed since start: the SDK does not heartbeat. */
    DMARKET_KEY_MISSING(blocking = true),

    /** DMarket refused the pushed key (invalid, revoked or banned). */
    DMARKET_AUTH_FAILED(blocking = true),

    /** DMarket answers with server errors, or not at all. */
    DMARKET_UNREACHABLE(blocking = true),

    /** No Steam token pushed since start. */
    STEAM_TOKEN_MISSING(blocking = true),

    /** The held Steam token has expired: push a fresh one. */
    STEAM_TOKEN_EXPIRED(blocking = true),

    /** Less than four hours left on the held Steam token; under thirty minutes no create is started. */
    STEAM_TOKEN_EXPIRING(blocking = false),

    /** The Steam account of the token is not the one DMarket links to this key's account. */
    STEAM_WRONG_ACCOUNT(blocking = true),

    /** DMarket has not named a linked Steam account, so the SDK takes no Steam action. */
    STEAM_ACCOUNT_UNKNOWN(blocking = true),

    /** Steam refused the session (revoked, or logged out elsewhere). */
    STEAM_SESSION_REJECTED(blocking = true),

    /** Steam is throttling reads or offer creation. */
    STEAM_RATE_LIMITED(blocking = false),

    /** Steam could not be read. */
    STEAM_UNREACHABLE(blocking = false),

    /** Below DMarket's minimum SDK version. Raised once the heartbeat carries the backend's signal for it. */
    SDK_UPDATE_REQUIRED(blocking = true),

    /** The notary cannot be reached, so no proof can be made. */
    NOTARY_UNAVAILABLE(blocking = false),

    /** A proof for a deal could not be produced or delivered. */
    PROOF_FAILED(blocking = false),

    /** A `create_offer` or `cancel_offer` failed (item gone, trade hold, a Steam EResult). */
    DIRECTIVE_FAILED(blocking = false),
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
 * The answer of `GET /v1/health`. [status] is `down` when any blocking problem is open, `degraded` when only
 * others are, `ok` otherwise. Carries no secret and no part of one.
 */
@Serializable
data class Health(
    @SerialName("status") val status: String,
    @SerialName("problems") val problems: List<Problem>,
    @SerialName("steamTokenExpiresAt") val steamTokenExpiresAt: String? = null,
    @SerialName("lastHeartbeatAt") val lastHeartbeatAt: String? = null,
    @SerialName("version") val version: String,
)
