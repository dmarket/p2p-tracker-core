package com.dmarket.p2p.tracker.client.steam

import com.dmarket.p2p.tracker.game.GameAdapter
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.steam.TradeDraft
import com.dmarket.p2p.tracker.model.steam.toAccountId
import com.dmarket.p2p.tracker.port.steam.CancelOfferResult
import com.dmarket.p2p.tracker.port.steam.CreateOfferResult
import io.ktor.http.Parameters
import io.ktor.http.parameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The wire shape of the two Steam community writes, stated once for every runtime that sends them: the form a
 * create POSTs, the `Referer` Steam's anti-CSRF check wants for it, and how either answer is read. Pure — the
 * transport (a browser's ambient cookie session, or explicit headers) is the caller's.
 *
 * Only the create and cancel endpoints have a shape here. There is no confirm, `mobileconf` or Guard request
 * anywhere in this file, so nothing built from it can reach one.
 */
internal object SteamOfferWire {

    /** The form `…/tradeoffer/new/send` takes: [sessionId] double-submitted, the items to give, none to take. */
    fun createForm(sessionId: String, draft: TradeDraft, adapter: GameAdapter): Parameters {
        val side = { assets: List<JsonObject> ->
            buildJsonObject {
                putJsonArray("assets") { assets.forEach { add(it) } }
                putJsonArray("currency") {}
                put("ready", false)
            }
        }
        val give = draft.assetsToGive.map { asset ->
            buildJsonObject {
                put("appid", adapter.game.appId)
                put("contextid", adapter.inventoryContextId.toString())
                put("amount", 1)
                put("assetid", asset.value)
            }
        }
        val tradeOffer = buildJsonObject {
            put("newversion", true)
            put("version", 2)
            put("me", side(give))
            put("them", side(emptyList()))
        }
        val createParams = buildJsonObject { draft.tradeToken?.let { put("trade_offer_access_token", it) } }
        return parameters {
            append("sessionid", sessionId)
            append("serverid", "1")
            append("partner", draft.partner.value)
            append("tradeoffermessage", "")
            append("json_tradeoffer", tradeOffer.toString())
            append("trade_offer_create_params", createParams.toString())
        }
    }

    /**
     * The `Referer` a create must carry: Steam matches its `partner` (the 32-bit account id) against the
     * body's `partner` (the steamid64) and refuses the POST when they differ or it is missing.
     */
    fun createReferer(communityBaseUrl: String, draft: TradeDraft): String = buildString {
        append("$communityBaseUrl/tradeoffer/new/")
        draft.partner.toAccountId()?.let { accountId ->
            append("?partner=").append(accountId)
            draft.tradeToken?.let { append("&token=").append(it) }
        }
    }

    /**
     * Reads a 2xx create answer. A `tradeofferid` with a mobile or email confirmation pending is the expected
     * outcome, [CreateOfferResult.NeedsConfirmation]; without one the offer is already live.
     */
    fun parseCreate(body: String): CreateOfferResult {
        val parsed = parseObject(body) ?: return CreateOfferResult.Failed("Steam create response is not JSON")
        if (parsed.eresult() == ERESULT_TIMEOUT) return CreateOfferResult.Failed(CREATE_TIMEOUT)
        val offerId = (parsed["tradeofferid"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return CreateOfferResult.Failed("Steam create response missing tradeofferid")
        val needsConfirmation = parsed.isTrue("needs_mobile_confirmation") || parsed.isTrue("needs_email_confirmation")
        return if (needsConfirmation) CreateOfferResult.NeedsConfirmation(OfferId(offerId)) else CreateOfferResult.Created(OfferId(offerId))
    }

    /**
     * A create Steam answered with a non-2xx [status], carrying [errorBody] (redacted and capped, or `null`). An
     * EResult `16` (Timeout) is [CREATE_TIMEOUT] on any status: whether Steam made the offer is as unknown as after no
     * answer at all, so it reads as one (its `timeout` is a transport marker) and is settled from the sent offers.
     */
    fun createRefusal(status: Int, errorBody: String?): CreateOfferResult.Failed {
        if (errorBody?.let(::parseObject)?.eresult() == ERESULT_TIMEOUT) return CreateOfferResult.Failed(CREATE_TIMEOUT)
        return CreateOfferResult.Failed("Steam create returned HTTP $status${errorBody?.let { ": $it" }.orEmpty()}")
    }

    /** A create Steam answered EResult `16`: worded so the default transport markers (`timeout`) read it as unanswered. */
    const val CREATE_TIMEOUT = "Steam create answered EResult 16 (Timeout)"

    private fun JsonObject.eresult(): String? =
        get("success")?.takeUnless { it is JsonNull }?.let { (it as? JsonPrimitive)?.content ?: it.toString() }

    /**
     * Reads a cancel's answer: its HTTP [status] and its [body] (`null` when none could be read). The body is read on
     * every status, and the first rule that matches wins:
     *
     * 1. a `strError` is a refusal, on any status — it carries no `success` key, so it is checked first;
     * 2. a `success` decides, on any status: `1` or `true` landed, `11` is [CancelOfferResult.NotLive], `16` is
     *    [CancelOfferResult.Unanswered], anything else is refused with the number;
     * 3. a 2xx carrying `tradeofferid` landed — the positive shape, not merely no negative one;
     * 4. anything else is refused: an HTML page, a login redirect or a truncated body never reads as a cancel.
     *
     * No response at all is the caller's [CancelOfferResult.Unanswered]. Messages name the status and the EResult
     * and nothing else, except a `strError`, which is capped: each becomes a directive outcome POSTed to DMarket.
     */
    fun readCancel(status: Int, body: String?): CancelOfferResult {
        val parsed = body?.let(::parseObject)
        (parsed?.get("strError") as? JsonPrimitive)?.takeIf { it.isString }?.let { strError ->
            return CancelOfferResult.Refused("Steam cancel refused (HTTP $status): ${strError.content.take(STR_ERROR_MAX)}")
        }
        parsed?.get("success")?.takeUnless { it is JsonNull }?.let { success ->
            val eresult = (success as? JsonPrimitive)?.content ?: success.toString()
            return when (eresult) {
                "1", "true" -> CancelOfferResult.Cancelled
                ERESULT_INVALID_STATE -> CancelOfferResult.NotLive
                ERESULT_TIMEOUT -> CancelOfferResult.Unanswered("Steam cancel answered EResult 16 (Timeout)")
                else -> CancelOfferResult.Refused("Steam cancel refused with EResult $eresult")
            }
        }
        val offerId = (parsed?.get("tradeofferid") as? JsonPrimitive)?.takeIf { it.isString }
        if (status in 200..299 && offerId != null) return CancelOfferResult.Cancelled
        return CancelOfferResult.Refused("Steam cancel answer is not one of Steam's (HTTP $status)")
    }

    /** Steam's `k_EResultInvalidState`: nothing left to cancel. */
    private const val ERESULT_INVALID_STATE = "11"

    /** Steam's `k_EResultTimeout`: the write may or may not have landed. */
    private const val ERESULT_TIMEOUT = "16"

    /** Cap on a `strError` carried into a cancel's outcome: Steam's refusals are one sentence. */
    private const val STR_ERROR_MAX = 160

    private fun parseObject(body: String): JsonObject? = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()

    private fun JsonObject.isTrue(key: String): Boolean {
        val value: JsonElement? = get(key)
        return value is JsonPrimitive && !value.isString && value.booleanOrNull == true
    }
}
