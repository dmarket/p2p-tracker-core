package com.dmarket.p2p.tracker.notary

import com.dmarket.p2p.tracker.model.AssetId
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The form bodies Steam's two community write endpoints require, built purely.
 *
 * **Why these live in `:domain` rather than next to the write actuals.** A proven write is issued by the
 * prover, not by `FetchSteamOfferCreator` — TLSN requires the prover to be the TLS client — so the body has to
 * be constructible from a pure value, with no browser and no `js("…")` object literals. Building it here also
 * makes it table-testable, which the actual's version never was.
 *
 * ⚠️ **Two spellings of one body exist today.** `FetchSteamOfferCreator.postCreate` builds the same fields
 * with `JSON.stringify` over JS literals. They must agree, because a proven create that differs from the real
 * create proves a request Steam would have answered differently. Adopting this object in that actual is the
 * follow-up that removes the duplication; it is deliberately not part of this change, so the write path the
 * loop actually uses is untouched.
 *
 * **Encoding happens once, here.** [createOfferFormTemplate] emits an already-percent-encoded body with
 * placeholder tokens left literal, and the fragments the mapper substitutes into it
 * ([assetsFragment], [createParamsFragment]) come back encoded to match. Encoding at substitution time
 * instead would either double-encode the template or leave the substituted JSON's `&` and `=` free to split
 * the body into fields Steam never sent.
 */
object SteamWriteBody {

    /** Placeholder for the JSON asset array inside `json_tradeoffer`, filled from the binding. */
    const val ASSETS_PLACEHOLDER: String = "{assetsJson}"

    /** Placeholder for the `trade_offer_create_params` document, filled from the binding's trade token. */
    const val CREATE_PARAMS_PLACEHOLDER: String = "{createParamsJson}"

    /** Placeholder for the counterparty's 32-bit `accountid` — what the create `Referer` takes. */
    const val PARTNER_ACCOUNT_ID_PLACEHOLDER: String = "{partnerAccountId}"

    /**
     * Placeholder for the create `Referer`'s optional `&token=…` segment, filled by [tradeTokenParam].
     *
     * A pre-composed fragment rather than a bare value placeholder, because the parameter is **optional** — a
     * trade against a public inventory carries no token — and a template cannot express "this whole segment is
     * absent". Same shape as [ASSETS_PLACEHOLDER] for the same reason.
     */
    const val TRADE_TOKEN_PARAM_PLACEHOLDER: String = "{tradeTokenParam}"

    /**
     * The anti-CSRF `Referer` Steam's create endpoint validates against the POST body's `partner`.
     *
     * A template, filled by `SteamProofReadMapper` like any other — and the second spelling of a URL that
     * `FetchSteamOfferCreator.installAntiCsrfRule` also builds. Kept here beside the body it is cross-checked
     * against, because those two drifting apart is a `403` with a valid session and no other symptom.
     *
     * The account id here is the 32-bit form; the body's `partner` is the steamid64. Steam wants each in its
     * own place, and swapping them is that same silent `403`.
     */
    fun createOfferReferer(communityBaseUrl: String): String =
        "$communityBaseUrl/tradeoffer/new/?partner=$PARTNER_ACCOUNT_ID_PLACEHOLDER$TRADE_TOKEN_PARAM_PLACEHOLDER"

    /** The `Referer`'s `&token=…` segment, or empty when the deal carries no trade token. */
    fun tradeTokenParam(tradeToken: String?): String = tradeToken?.let { "&token=${formEncode(it)}" } ?: ""

    /**
     * `tradeoffer/new/send`'s `application/x-www-form-urlencoded` body, as a template.
     *
     * Field-for-field what Steam's create endpoint expects, in the order the reference sends them:
     * `sessionid`, `serverid=1`, `partner` (steamid64 — the 32-bit accountid form is only ever used in the
     * `Referer`), an empty `tradeoffermessage`, and the two JSON documents. `newversion: true` / `version: 2`
     * are Steam's own protocol constants.
     *
     * `them` is always empty and `ready` is always `false`: this client only ever *offers* items, and it stops
     * at `CreatedNeedsConfirmation` — the user confirms on the official Steam app. There is no code path here,
     * or anywhere in this codebase, that builds a confirm request.
     */
    fun createOfferFormTemplate(): String {
        // Assembled as a string rather than through `buildJsonObject` because the asset array is a
        // PLACEHOLDER, not a value — `formEncode` has to see `{assetsJson}` intact, and a JSON writer would
        // quote it into a string literal. The fragments substituted into it are built with the writer (see
        // `assetsFragment`), so the only hand-written JSON here is structure with no user data in it.
        val jsonTradeOffer = """{"newversion":true,"version":2,""" +
            """"me":{"assets":[$ASSETS_PLACEHOLDER],"currency":[],"ready":false},""" +
            """"them":{"assets":[],"currency":[],"ready":false}}"""
        return listOf(
            "sessionid" to SESSION_ID_PLACEHOLDER,
            "serverid" to "1",
            "partner" to PARTNER_STEAM_ID,
            "tradeoffermessage" to "",
            "json_tradeoffer" to jsonTradeOffer,
            "trade_offer_create_params" to CREATE_PARAMS_PLACEHOLDER,
        ).joinToString("&") { (name, value) -> "$name=${formEncode(value)}" }
    }

    /**
     * `tradeoffer/{id}/cancel`'s body — `sessionid` and nothing else.
     *
     * The offer being cancelled is named by the **path**, which is why a cookie-authenticated write discloses
     * its request target: that disclosure is the whole binding.
     */
    fun cancelOfferFormTemplate(): String = "sessionid=$SESSION_ID_PLACEHOLDER"

    /** The encoded JSON asset array for [ASSETS_PLACEHOLDER]. Empty list ⇒ empty fragment (a valid `[]`). */
    fun assetsFragment(assets: List<AssetId>, appId: Int, contextId: Int): String = formEncode(
        assets.joinToString(",") { asset ->
            buildJsonObject {
                put("appid", appId)
                put("contextid", contextId.toString())
                put("amount", 1)
                put("assetid", asset.value)
            }.toString()
        },
    )

    /** The encoded `trade_offer_create_params` document for [CREATE_PARAMS_PLACEHOLDER]. */
    fun createParamsFragment(tradeToken: String?): String = formEncode(
        buildJsonObject { tradeToken?.let { put("trade_offer_access_token", it) } }.toString(),
    )

    /**
     * Percent-encode a form value, leaving `{placeholder}` tokens intact so the mapper and the IO edge can
     * still find their slots.
     *
     * Hand-rolled because `:domain` is zero-dependency by design and has no URL library. Deliberately
     * conservative — anything outside the unreserved set is escaped — so a Steam-supplied trade token or an
     * asset id cannot smuggle an `&` or `=` into a neighbouring field.
     */
    private fun formEncode(value: String): String = buildString {
        var index = 0
        while (index < value.length) {
            val placeholder = PLACEHOLDERS.firstOrNull { value.startsWith(it, index) }
            if (placeholder != null) {
                append(placeholder)
                index += placeholder.length
                continue
            }
            val char = value[index]
            when {
                char.isUnreserved() -> append(char)
                char == ' ' -> append('+')
                // Escaped through the stdlib encoder rather than a hand-rolled one. Taking the code unit and
                // splitting it by hand produced CESU-8 for anything outside the BMP — a surrogate pair became
                // two 3-byte sequences, so an astral character in a Steam-supplied trade token was encoded as
                // something Steam would not decode back. `encodeToByteArray` gets pairs right, and taking the
                // whole remaining run at once means it sees both halves together.
                else -> {
                    val end = value.nextBoundary(index)
                    value.substring(index, end).encodeToByteArray().forEach { byte ->
                        append('%').append((byte.toInt() and 0xFF).hex())
                    }
                    index = end
                    continue
                }
            }
            index++
        }
    }

    /**
     * End of the run starting at [index] that must be percent-encoded together: a surrogate pair counts as one
     * character, everything else as one code unit.
     */
    private fun String.nextBoundary(index: Int): Int =
        if (this[index].isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()) index + 2 else index + 1

    private fun Char.isUnreserved(): Boolean = this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this in "-_.~"

    private fun Int.hex(): String = toString(16).uppercase().padStart(2, '0')

    /** Every token [formEncode] must pass through untouched. */
    private val PLACEHOLDERS: List<String> = listOf(
        SESSION_ID_PLACEHOLDER,
        ASSETS_PLACEHOLDER,
        CREATE_PARAMS_PLACEHOLDER,
        PARTNER_STEAM_ID,
        PARTNER_ACCOUNT_ID_PLACEHOLDER,
        TRADE_TOKEN_PARAM_PLACEHOLDER,
    )
}
