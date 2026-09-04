package com.dmarket.p2p.tracker.model.steam

import com.dmarket.p2p.tracker.model.JwtPayload
import com.dmarket.p2p.tracker.model.MalformedJwtException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.time.Instant

/**
 * Thrown when a Steam JWT token cannot be parsed (wrong number of segments, invalid base64url,
 * missing `exp` claim, etc.).
 */
class MalformedSteamTokenException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/**
 * Minimal, pure parser for Steam Web API JWTs (`data-loyalty_webapi_token`).
 *
 * We **do not verify the signature** — we trust the source (the user's own authenticated
 * steamcommunity.com session), not the JWT's cryptographic integrity.
 *
 * The base64url + JSON decoding itself lives in [JwtPayload] (shared with the marketplace token, which
 * is also a JWT); this object owns only the Steam-specific claim reading and the public exception type.
 */
object SteamTokenJwt {

    private data class Claims(val exp: Long? = null, val sub: String? = null)

    /**
     * Parses the `exp` claim of [token] and returns it as a [kotlin.time.Instant].
     *
     * @throws MalformedSteamTokenException if [token] is not a valid 3-segment JWT, the payload
     *   is not valid base64url / JSON, or the `exp` claim is absent or non-numeric.
     */
    fun parseExp(token: String): Instant {
        val claims = decodeClaims(token)
        val exp = claims.exp
            ?: throw MalformedSteamTokenException("JWT payload is missing the 'exp' claim")
        return Instant.fromEpochSeconds(exp)
    }

    /**
     * Returns the `sub` (subject) claim — the Steam ID string — or `null` if the token is
     * malformed or the claim is absent. Does not throw.
     */
    fun subjectOrNull(token: String): String? = runCatching { decodeClaims(token).sub }.getOrNull()

    /**
     * A read-only decode of the diagnostic JWT claims. `aud` may be a string or an array in the wire
     * payload; both are normalised to a list here. Used only to surface the live token's scope/expiry
     * in the debug harness (confirming it is a valid, unexpired web-audience token).
     */
    data class DecodedClaims(
        val exp: Long? = null,
        val iat: Long? = null,
        val sub: String? = null,
        val iss: String? = null,
        val aud: List<String> = emptyList(),
    )

    /**
     * Decodes the diagnostic claims (`exp`, `iat`, `sub`, `iss`, `aud`) or returns `null` if the
     * token is malformed. Does not throw. Signature is not verified (see class KDoc).
     */
    fun claimsOrNull(token: String): DecodedClaims? = runCatching {
        val payload = decodePayloadObject(token)
        DecodedClaims(
            exp = (payload["exp"] as? JsonPrimitive)?.longOrNull,
            iat = (payload["iat"] as? JsonPrimitive)?.longOrNull,
            sub = (payload["sub"] as? JsonPrimitive)?.contentOrNull,
            iss = (payload["iss"] as? JsonPrimitive)?.contentOrNull,
            aud = parseAud(payload["aud"]),
        )
    }.getOrNull()

    // ---- private -----------------------------------------------------------------------------------

    /** `aud` is either a single string or an array of strings per RFC 7519 §4.1.3 — normalise to a list. */
    private fun parseAud(element: JsonElement?): List<String> = when (element) {
        is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> listOfNotNull(element.contentOrNull)
        else -> emptyList()
    }

    /**
     * The payload object, with [JwtPayload]'s failures re-thrown as this object's own public exception
     * type so existing callers keep catching what they always caught.
     *
     * Neither [JwtPayload] nor this wrapper carries the upstream message or keeps the cause — see
     * [MalformedJwtException] for why that matters here.
     */
    private fun decodePayloadObject(token: String): JsonObject = try {
        JwtPayload.decode(token)
    } catch (e: MalformedJwtException) {
        throw MalformedSteamTokenException(e.message ?: "JWT payload is not decodable")
    }

    private fun decodeClaims(token: String): Claims {
        val payload = decodePayloadObject(token)
        return Claims(
            exp = JwtPayload.expiresAtSecondsOrNull(payload),
            sub = (payload["sub"] as? JsonPrimitive)?.contentOrNull,
        )
    }

    /**
     * Decodes a base64url-encoded string to a UTF-8 string. Retained as this object's public surface
     * (tests and `:debug-harness` use it); delegates to [JwtPayload.base64UrlDecodeToString].
     */
    internal fun base64UrlDecodeToString(encoded: String): String = try {
        JwtPayload.base64UrlDecodeToString(encoded)
    } catch (e: MalformedJwtException) {
        throw MalformedSteamTokenException(e.message ?: "not valid base64url")
    }
}
