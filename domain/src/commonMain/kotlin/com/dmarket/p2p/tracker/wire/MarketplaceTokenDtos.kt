package com.dmarket.p2p.tracker.wire

import com.dmarket.p2p.tracker.model.marketplace.MarketplaceTokenPair
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Wire DTOs for the DMarket token-refresh endpoint — `POST {base}/marketplace-api/v1/refresh-token`.
 *
 * **Different service, different shape rules from `Dtos.kt`.** The C1 tracker endpoints under
 * `/exchange/v1/p2p/ext/` are protojson and therefore lowerCamelCase; this one is `marketplace-api` and its
 * keys are **PascalCase** (`RefreshToken`, `AuthToken`, …), with the two expiries as int64 epoch **seconds
 * serialised as JSON strings**. Verified against three independent producers of the same call: the web
 * frontend's `TokenRefreshService`, the swagger `marketplaceRefreshTokenPairsRequest/Response`, and the
 * DMarket Android client's `RefreshTokenPairBodyEntity` / `RefreshTokenPairEntity`.
 */

@Serializable
data class RefreshTokenRequestDto(@SerialName("RefreshToken") val refreshToken: String) {
    /** Redacted: this single field is a ~30-day bearer for the whole account. */
    override fun toString(): String = "RefreshTokenRequestDto(refreshToken=<redacted>)"
}

@Serializable
data class RefreshTokenResponseDto(
    @SerialName("AuthToken") val authToken: String? = null,
    @SerialName("AuthTokenExpiresAt") val authTokenExpiresAt: String? = null,
    @SerialName("RefreshToken") val refreshToken: String? = null,
    @SerialName("RefreshTokenExpiresAt") val refreshTokenExpiresAt: String? = null,
    /**
     * Error code on a **200** response. The DMarket APIs answer some failures this way rather than with a
     * status, which is why [toPairOrNull] treats a token-less body as a refusal instead of a success.
     */
    @SerialName("Code") val code: String? = null,
    @SerialName("Message") val message: String? = null,
) {
    /**
     * Redacted: carries the freshly minted pair. [code] is kept — it is the whole diagnostic value of
     * logging this — and [message] is dropped, since a server-supplied string is not something to
     * interpolate into logs that leave the device.
     */
    override fun toString(): String = "RefreshTokenResponseDto(authToken=${present(authToken)}, " +
        "authTokenExpiresAt=$authTokenExpiresAt, refreshToken=${present(refreshToken)}, " +
        "refreshTokenExpiresAt=$refreshTokenExpiresAt, code=$code)"

    private fun present(value: String?): String = if (value.isNullOrBlank()) "absent" else "<redacted>"
}

/**
 * The response as a domain pair, or `null` when it does not actually carry one — an error `Code`, or a
 * body missing either token. Callers must treat `null` as a refusal, never as a no-op success.
 */
fun RefreshTokenResponseDto.toPairOrNull(): MarketplaceTokenPair? {
    val access = authToken?.takeIf { it.isNotBlank() } ?: return null
    val refresh = refreshToken?.takeIf { it.isNotBlank() } ?: return null
    // `AuthTokenExpiresAt` is intentionally dropped: the access token's expiry is read from the token itself
    // (see [MarketplaceTokenPair]). It stays on the DTO, where it is part of the wire shape and is reported by
    // `toString()`.
    return MarketplaceTokenPair(
        accessToken = access,
        refreshToken = refresh,
        refreshTokenExpiresAt = refreshTokenExpiresAt.toEpochSecondsInstantOrNull(),
    )
}

/** Epoch **seconds** carried as a JSON string. `null`/blank/unparseable → `null` ("can't tell"). */
private fun String?.toEpochSecondsInstantOrNull(): Instant? = this?.trim()?.toLongOrNull()?.let { Instant.fromEpochSeconds(it) }
