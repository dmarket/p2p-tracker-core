package com.dmarket.p2p.tracker.client.steam

import com.dmarket.p2p.tracker.game.GameAdapter
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.steam.TradeDraft
import com.dmarket.p2p.tracker.model.steam.toAccountId
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
        val offerId = (parsed["tradeofferid"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return CreateOfferResult.Failed("Steam create response missing tradeofferid")
        val needsConfirmation = parsed.isTrue("needs_mobile_confirmation") || parsed.isTrue("needs_email_confirmation")
        return if (needsConfirmation) CreateOfferResult.NeedsConfirmation(OfferId(offerId)) else CreateOfferResult.Created(OfferId(offerId))
    }

    /**
     * The refusal a 2xx cancel [body] carries, or `null` when it carries none.
     *
     * A cancelled offer is answered `{"tradeofferid":"<id>"}` with no `success` key, so only a **present**
     * non-OK `success` is a refusal; an absent or unparseable envelope stays a success, because inventing a
     * failure out of Steam's OK shape would strand the directive. Both spellings of OK are accepted — Steam
     * has been seen answering `true` where an int is documented. The message names the EResult and nothing
     * else: it becomes a directive outcome that is POSTed to DMarket.
     */
    fun cancelRefusal(body: String): String? {
        val success = parseObject(body)?.get("success")?.takeUnless { it is JsonNull } ?: return null
        val eresult = (success as? JsonPrimitive)?.content ?: success.toString()
        return if (eresult == "1" || eresult == "true") null else "Steam cancel refused with EResult $eresult"
    }

    private fun parseObject(body: String): JsonObject? = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()

    private fun JsonObject.isTrue(key: String): Boolean {
        val value: JsonElement? = get(key)
        return value is JsonPrimitive && !value.isString && value.booleanOrNull == true
    }
}
