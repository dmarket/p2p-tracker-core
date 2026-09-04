package com.dmarket.p2p.tracker.model

import com.dmarket.p2p.tracker.wire.trackerJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Thrown when a JWT cannot be decoded (wrong number of segments, invalid base64url, payload not JSON).
 *
 * **Never carries the offending input, nor the upstream cause.** The input here IS a token, and a
 * decoder's own message can quote it (kotlinx-serialization) or a chunk of it (V8's `SyntaxError`) —
 * while `stackTraceToString()`, which the host's crash reporter ships, walks the cause chain. Every
 * call site needs only "this token is unusable" and treats it as exactly that.
 */
internal class MalformedJwtException(message: String) : IllegalArgumentException(message)

/**
 * The shared, pure JWT payload decoder — the one copy of base64url + claims decoding in `:domain`.
 *
 * We **do not verify signatures** anywhere in this library: every token we decode was handed to us by
 * the platform that already authenticated the user (the browser's own cookie jar, the app's own token
 * store), so what we trust is the source, not the JWT's cryptographic integrity. We read expiry claims
 * only, to decide when to refresh.
 *
 * Uses a hand-rolled base64url decoder rather than `kotlin.io.encoding.Base64.UrlSafe`, which is still
 * experimental in Kotlin 2.x and would force `@OptIn(ExperimentalEncodingApi)` on every consumer. The
 * decoder is ~30 portable lines and identical on JVM and JS.
 *
 * Extracted from `com.dmarket.p2p.tracker.model.steam.SteamTokenJwt` (which now delegates here) when the
 * marketplace side needed the same primitive: the DMarket access token is a JWT too, and its real expiry
 * is the `exp` claim inside it — NOT the `expirationDate` of the cookie carrying it, which the web
 * frontend deliberately sets to the *refresh* token's 30-day expiry.
 */
internal object JwtPayload {

    private val json = trackerJson { ignoreUnknownKeys = true }

    /**
     * Decodes [token]'s payload segment to a [JsonObject].
     *
     * @throws MalformedJwtException if [token] has fewer than 2 dot-separated segments, or the payload
     *   segment is not valid base64url, or the decoded bytes are not a JSON object.
     */
    fun decode(token: String): JsonObject {
        val parts = token.split('.')
        if (parts.size < 2) {
            throw MalformedJwtException("JWT must have at least 2 dot-separated segments, got ${parts.size}")
        }
        val payloadJson = try {
            base64UrlDecodeToString(parts[1])
        } catch (e: MalformedJwtException) {
            throw e
        } catch (_: Exception) {
            throw MalformedJwtException("JWT payload segment is not valid base64url")
        }
        return try {
            json.parseToJsonElement(payloadJson).jsonObject
        } catch (_: Exception) {
            throw MalformedJwtException("JWT payload is not valid JSON")
        }
    }

    /** [decode], but `null` instead of throwing. */
    fun decodeOrNull(token: String): JsonObject? = runCatching { decode(token) }.getOrNull()

    /**
     * The `exp` claim in epoch seconds, or `null` when absent or non-numeric.
     *
     * A **quoted** number is accepted: the DMarket contract serialises int64 epochs as JSON strings elsewhere,
     * and tolerating it costs nothing while removing a class of "the token looks unreadable" incident.
     */
    fun expiresAtSecondsOrNull(payload: JsonObject): Long? = (payload["exp"] as? JsonPrimitive)?.longOrNull

    /**
     * Decodes a base64url-encoded string (RFC 4648 §5, alphabet `A–Za–z0–9-_`) to a UTF-8 string.
     * Tolerates missing `=` padding.
     */
    fun base64UrlDecodeToString(encoded: String): String {
        // base64url → standard base64 alphabet
        val base64 = encoded.replace('-', '+').replace('_', '/')

        // Re-pad to a multiple of 4
        val padded = when (base64.length % 4) {
            0 -> base64
            2 -> "$base64=="
            3 -> "$base64="
            else -> throw MalformedJwtException(
                "Invalid base64url segment length (${encoded.length} % 4 == 1)",
            )
        }

        return decodeBase64Bytes(padded).decodeToString()
    }

    private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private fun decodeBase64Bytes(padded: String): ByteArray {
        val output = ArrayList<Byte>(padded.length * 3 / 4)
        var buffer = 0
        var bitsCollected = 0

        for (ch in padded) {
            if (ch == '=') break
            val idx = BASE64_ALPHABET.indexOf(ch)
            if (idx < 0) throw MalformedJwtException("Invalid base64 character: '$ch'")
            buffer = (buffer shl 6) or idx
            bitsCollected += 6
            if (bitsCollected >= 8) {
                bitsCollected -= 8
                output.add(((buffer ushr bitsCollected) and 0xFF).toByte())
            }
        }

        return output.toByteArray()
    }
}
