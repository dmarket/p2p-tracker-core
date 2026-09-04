package com.dmarket.p2p.tracker.credential.steam

import com.dmarket.p2p.tracker.wire.trackerJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The normalised result of parsing Steam's web-session refresh response (`/jwt/ajaxrefresh`).
 *
 * Two shapes are seen in the wild and both are tolerated (the exact field names are the one
 * MEDIUM-confidence item pending a real-traffic capture — see the plan):
 * - [Transfers] — `finalizelogin`-style `{ steamid, transfer_info: [{ url, params: { nonce, auth }}] }`.
 * - [Flat] — `ajaxrefresh`-style `{ success, login_url, steamID, nonce, auth }` — ONE domain's transfer,
 *   the one matching the `redir` the request asked for.
 *
 * Anything missing the pieces needed to drive `settoken` (incl. "not logged in") parses to [Unusable].
 */
sealed interface SteamRefreshResponse {
    data class Transfers(val steamId: String, val transfers: List<Transfer>) : SteamRefreshResponse

    /**
     * [loginUrl] is the `login_url` Steam returns for the requested `redir` domain — the settoken endpoint
     * this transfer is scoped to. `null` when the response omits it (then the caller falls back to the
     * redir domain's own settoken path).
     *
     * [fields] is every top-level scalar of the response, verbatim. Steam's own page does
     * `settoken(Object.assign(response, {prior: g_wapit}))` — it echoes the WHOLE refresh response back
     * rather than a hand-picked subset — so keeping the raw fields lets the caller be faithful to a shape
     * this endpoint has already proven to be strict about.
     */
    data class Flat(
        val steamId: String,
        val nonce: String,
        val auth: String,
        val loginUrl: String? = null,
        val fields: Map<String, String> = emptyMap(),
    ) : SteamRefreshResponse {
        /** Redacted — see [Transfer.toString]. [fields] is the whole `ajaxrefresh` response verbatim. */
        override fun toString(): String = "Flat(steamId=$steamId, nonce=<redacted>, auth=<redacted>, " +
            "loginUrl=$loginUrl, fields=<redacted ${fields.size} fields: ${fields.keys.sorted()}>)"
    }

    data object Unusable : SteamRefreshResponse

    /** One per-domain `settoken` job: POST [url] with `{steamID, nonce, auth}`. */
    data class Transfer(val url: String, val nonce: String, val auth: String) {
        /**
         * Redacted: `nonce` and `auth` are the single-use secrets that mint a Steam session, and this type
         * is interpolated wherever a refresh is diagnosed. The url stays — that is what makes a redacted
         * line still useful. ([Transfers] is covered transitively, via its list of these.)
         */
        override fun toString(): String = "Transfer(url=$url, nonce=<redacted>, auth=<redacted>)"
    }
}

/**
 * Pure parser for the refresh response. Reads `steamid` from [JsonPrimitive.content] (the raw token)
 * rather than as a number, so the 64-bit Steam id never loses precision. Never throws.
 */
object SteamRefreshResponseParser {
    private val json = trackerJson {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(body: String?): SteamRefreshResponse {
        if (body.isNullOrBlank()) return SteamRefreshResponse.Unusable
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return SteamRefreshResponse.Unusable

        val steamId = root.stringOrNull("steamid") ?: root.stringOrNull("steamID") ?: ""

        // transfer_info[] shape: array of { url, params: { nonce, auth } }.
        val transferInfo = root["transfer_info"] as? JsonArray
        if (transferInfo != null) {
            val transfers = transferInfo.mapNotNull { element ->
                val entry = element as? JsonObject ?: return@mapNotNull null
                val url = entry.stringOrNull("url") ?: return@mapNotNull null
                val params = entry["params"] as? JsonObject ?: return@mapNotNull null
                val nonce = params.stringOrNull("nonce") ?: return@mapNotNull null
                val auth = params.stringOrNull("auth") ?: return@mapNotNull null
                SteamRefreshResponse.Transfer(url, nonce, auth)
            }
            if (steamId.isNotBlank() && transfers.isNotEmpty()) {
                return SteamRefreshResponse.Transfers(steamId, transfers)
            }
        }

        // Flat shape: top-level nonce + auth.
        val nonce = root.stringOrNull("nonce")
        val auth = root.stringOrNull("auth")
        if (steamId.isNotBlank() && nonce != null && auth != null) {
            val fields = root.entries.mapNotNull { (key, value) ->
                (value as? JsonPrimitive)?.let { key to it.content }
            }.toMap()
            return SteamRefreshResponse.Flat(steamId, nonce, auth, root.stringOrNull("login_url"), fields)
        }
        return SteamRefreshResponse.Unusable
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.content.ifBlank { null }
    }
}

/**
 * Steam's `login/settoken` reply. `{"result":1}` is `EResult.OK` — the domain accepted the transfer and
 * its `Set-Cookie` is the fresh session. Anything else (an error result, an unparseable body, no body) is
 * a rejected transfer, which is the difference between a session that came back and one that only looks
 * like it did on the domain the caller happens to verify.
 */
internal object SetTokenResult {
    private val json = trackerJson {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun accepted(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        val result = runCatching { json.parseToJsonElement(body).jsonObject["result"] }.getOrNull() as? JsonPrimitive
        return result?.content == "1"
    }
}
