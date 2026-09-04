package com.dmarket.p2p.tracker.debug

import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.model.steam.SteamTokenJwt
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import kotlin.time.Instant

/**
 * How the debug surface prints secrets: **redacted unless the caller explicitly asked otherwise.**
 *
 * The diagnostic probes exist to answer questions like "is the scraped token the right audience?",
 * "did the refresh actually replace the cookie?", "is this the same token as a minute ago?". Every one
 * of those is answered by the token's *claims*, its length, and an identity marker — none of them needs
 * the secret itself. So the default output carries a [fingerprint] instead, and the raw value appears
 * only through the opt-in entry point (`createDebugSessionRevealingSecrets`), which a reader of this
 * repo can see is not the one anything automated calls.
 *
 * This mirrors the audit rule the production path already follows —
 * [com.dmarket.p2p.tracker.port.host.NetworkObserver.redactSecrets] defaults to `true` and only this
 * unpublished module may turn it off — and makes it the default *here* too, instead of an exception
 * that happens to live in a dev module.
 */
object SecretRedaction {

    private const val FNV_64_OFFSET_BASIS = -3750763034362895579L // 0xcbf29ce484222325
    private const val FNV_64_PRIME = 1099511628211L
    private const val HEX = "0123456789abcdef"

    /**
     * A stable 16-hex-digit marker for [secret]: equal secrets fingerprint equally, so "did this
     * change?" and "is this the same token both places?" stay answerable without printing it.
     *
     * FNV-1a — a **non-cryptographic** hash, chosen because it needs no dependency and this is not a
     * confidentiality control: it is meant for high-entropy material (JWTs, session cookies), where the
     * fingerprint reveals nothing usable. Do not treat it as a safe way to publish a low-entropy secret
     * such as a PIN — that would be brute-forceable from the digest.
     */
    fun fingerprint(secret: String?): String? {
        if (secret == null) return null
        var hash = FNV_64_OFFSET_BASIS
        for (byte in secret.encodeToByteArray()) {
            hash = hash xor (byte.toLong() and 0xff)
            hash *= FNV_64_PRIME
        }
        val hex = CharArray(16)
        var rest = hash
        for (i in 15 downTo 0) {
            hex[i] = HEX[(rest and 0xfL).toInt()]
            rest = rest ushr 4
        }
        return hex.concatToString()
    }
}

/**
 * Writes `<name>Length` + `<name>Fingerprint` for [value], and the raw `<name>` **only** when [reveal]
 * is true. An absent value still writes both keys as null, so the key set never depends on presence.
 */
internal fun JsonObjectBuilder.putSecret(name: String, value: String?, reveal: Boolean) {
    put(name + "Length", value?.length)
    put(name + "Fingerprint", SecretRedaction.fingerprint(value))
    if (reveal) put(name, value)
}

/**
 * The Steam credential as diagnostics: identity, freshness and the decoded JWT claims — which is what
 * rules a wrong-scope or stale token in or out — with the token itself redacted per [reveal].
 *
 * [SteamCredential.toString] already redacts the token for exactly this reason; this keeps the JSON
 * projection consistent with it instead of being the one place that prints the secret.
 */
internal fun JsonObjectBuilder.putSteamCredential(cred: SteamCredential, now: Instant, reveal: Boolean) {
    put("steamId", cred.subjectSteamId.value)
    put("expiresAtIso", cred.expiresAt.toString())
    put("fresh", cred.isFresh(now))
    putSecret("token", cred.token, reveal)
    // Decoded JWT claims — confirm the scraped access_token is a valid, unexpired, web-audience token
    // (rules out "token is wrong-scope/stale" vs. a transport/cookie problem on the IEconService 403).
    // These describe the token; they are not the token, so they are printed either way.
    val claims = SteamTokenJwt.claimsOrNull(cred.token)
    put("jwtIssuer", claims?.iss)
    put("jwtAudience", claims?.aud?.joinToString(",")?.ifBlank { null })
    put("jwtExp", claims?.exp)
    put("jwtIat", claims?.iat)
    put("secondsUntilExp", claims?.exp?.let { it - now.epochSeconds })
}
