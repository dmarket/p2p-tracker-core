package com.dmarket.p2p.tracker.client.steam

import com.dmarket.p2p.tracker.adapter.webext.webExtApi
import com.dmarket.p2p.tracker.adapter.webext.webExtCookieValue
import com.dmarket.p2p.tracker.client.HttpStatusException
import com.dmarket.p2p.tracker.client.captureErrorBody
import com.dmarket.p2p.tracker.game.Cs2GameAdapter
import com.dmarket.p2p.tracker.game.GameAdapter
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.model.steam.TradeDraft
import com.dmarket.p2p.tracker.model.steam.toAccountId
import com.dmarket.p2p.tracker.net.redactedSummary
import com.dmarket.p2p.tracker.port.steam.CreateOfferResult
import com.dmarket.p2p.tracker.port.steam.SteamOfferCreator
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.parameters
import kotlinx.coroutines.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.js.Promise

/**
 * The **web / browser-extension** actual of [SteamOfferCreator]. It POSTs a new Steam trade offer to
 * `https://steamcommunity.com/tradeoffer/new/send` (Steam's create endpoint) and **stops at**
 * `CreatedNeedsConfirmation` — it never confirms. The user confirms on the official Steam app.
 *
 * The POST goes through the injected credentialed Steam [HttpClient] ([com.dmarket.p2p.tracker.client.createSteamHttpClient]) whose
 * browser engine sets fetch `credentials:"include"`, so the logged-in Steam cookie session is
 * attached; the `sessionid` cookie is echoed in the form body (read via `chrome.cookies`).
 *
 * **Hard-rule enforcement:** the only URL this class builds is the fixed `…/tradeoffer/new/send`
 * create endpoint, and the flow stops at `CreatedNeedsConfirmation` — there is no code path here
 * that constructs a `mobileconf`, Guard-code, or confirm URL. Combined with the port surface (no
 * confirm method), the core can create an offer but structurally cannot confirm one.
 *
 * **Manifest requirements:** `host_permissions` for `https://steamcommunity.com` + the `"cookies"`
 * permission.
 *
 * **`Referer`/`Origin` anti-CSRF (this actual injects it):** `tradeoffer/new/send` rejects a
 * `chrome-extension://` `Origin` and a `Referer` that does not carry **this trade's** `partner` — Steam
 * validates the `Referer`'s `partner` against the POST body's, so a partner-less static `Referer` still
 * `403`s even with a valid session. `Referer` is a *forbidden fetch header*, so the request cannot set it;
 * instead [createOffer] installs a per-trade `declarativeNetRequest` session rule
 * (`Referer: …/tradeoffer/new/?partner=<accountid>&token=<token>`, `Origin: https://steamcommunity.com`)
 * around the send and tears it down after. Doing it here — the one POST every create path shares —
 * covers **both** FE-message- and directive-driven (alarm-loop) creates with the correct per-trade
 * `Referer`; a standing rule cannot, because it can't carry the per-trade `partner`. Requires the
 * `"declarativeNetRequest"` permission; a host running this inside a first-party `steamcommunity.com`
 * content-script context needs no rule (the DNR failure is swallowed and the create proceeds). The rule
 * matches the Ktor request the same way it matched raw `fetch` — both bottom out in `window.fetch`, so
 * the send is still an `xmlhttprequest`.
 *
 * @param adapter supplies the game's `appid` + inventory context id for the offer body (CS2 at v1).
 */
class FetchSteamOfferCreator(
    private val httpClient: HttpClient,
    private val communityBaseUrl: String = "https://steamcommunity.com",
    private val adapter: GameAdapter = Cs2GameAdapter(),
) : SteamOfferCreator {

    /**
     * Serializes concurrent creates: the anti-CSRF rewrite uses a single fixed [ANTI_CSRF_RULE_ID], so
     * two overlapping `createOffer` calls (e.g. an FE-triggered `createTrade` interleaving with a
     * directive-driven `runCreate` at a suspension point on the single-threaded worker) would clobber
     * each other's per-trade `Referer` and one's teardown would drop the rule mid-flight for the other →
     * Steam `403`. Creates are rare, so serializing them is free and correct.
     */
    private val createMutex = Mutex()

    override suspend fun createOffer(credential: SteamCredential, draft: TradeDraft): CreateOfferResult = createMutex.withLock {
        val url = "$communityBaseUrl/tradeoffer/new/send"
        val sessionId = readSessionId() ?: return@withLock CreateOfferResult.Failed("no Steam session cookie")
        // Install the per-trade anti-CSRF header rewrite (Referer carries this trade's partner+token),
        // then always tear it down. Every create — FE-message- AND directive-driven — funnels through
        // here (serialized by createMutex), so this one install point covers all paths. See installAntiCsrfRule.
        installAntiCsrfRule(draft)
        try {
            runCatching { postCreate(url, sessionId, draft) }
                .getOrElse { CreateOfferResult.Failed(it.redactedSummary()) }
        } finally {
            removeAntiCsrfRule()
        }
    }

    // ---- private -----------------------------------------------------------------------------------

    /**
     * `tradeoffer/new/send` enforces an anti-CSRF check that a service-worker request cannot satisfy on
     * its own: its `Origin` is `chrome-extension://…` and `Referer` is a *forbidden fetch header*, so
     * without a first-party `Origin` **and** a `Referer` carrying **this trade's** `partner` (+ `token`),
     * Steam replies `403` even with a valid session. Steam validates that the `Referer`'s `partner`
     * matches the POST body's `partner` — a partner-less static `Referer` is **not** sufficient. Because
     * a leased `create_offer` directive fires from the loop's own alarm (not a user gesture), the rewrite
     * cannot live in a host message handler; installing it here — around the one POST every create path
     * shares — is what covers both the FE-message and directive-driven creates with the correct per-trade
     * `Referer`. Best-effort: a DNR failure (e.g. a content-script first-party host that has no
     * `declarativeNetRequest`) is swallowed so the create still proceeds.
     */
    private suspend fun installAntiCsrfRule(draft: TradeDraft) {
        val referer = buildString {
            append("$communityBaseUrl/tradeoffer/new/")
            // The shared conversion, not a local copy of the magic base: Steam wants the 32-bit accountid here
            // and the steamid64 in the body, and having two spellings of that arithmetic is how they get swapped.
            draft.partner.toAccountId()?.let { accountId ->
                append("?partner=").append(accountId)
                draft.tradeToken?.let { append("&token=").append(it) }
            }
        }
        val header = { name: String, value: String ->
            val h: dynamic = js("({operation:'set'})")
            h.header = name
            h.value = value
            h
        }
        val rule: dynamic = js("({priority:1,action:{type:'modifyHeaders'},condition:{}})")
        rule.id = ANTI_CSRF_RULE_ID
        rule.action.requestHeaders = arrayOf(header("referer", referer), header("origin", communityBaseUrl))
        rule.condition.urlFilter = "||steamcommunity.com/tradeoffer/new/send"
        rule.condition.resourceTypes = arrayOf("xmlhttprequest", "other")

        val update: dynamic = js("({})")
        update.removeRuleIds = arrayOf(ANTI_CSRF_RULE_ID)
        update.addRules = arrayOf(rule)
        runCatching {
            @Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")
            webExtApi().declarativeNetRequest.updateSessionRules(update).unsafeCast<Promise<dynamic>>().await()
        }
    }

    /** Drop the create rewrite; best-effort so a teardown failure never breaks the cycle. */
    private suspend fun removeAntiCsrfRule() {
        val update: dynamic = js("({})")
        update.removeRuleIds = arrayOf(ANTI_CSRF_RULE_ID)
        runCatching {
            @Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")
            webExtApi().declarativeNetRequest.updateSessionRules(update).unsafeCast<Promise<dynamic>>().await()
        }
    }

    private suspend fun readSessionId(): String? = webExtCookieValue(communityBaseUrl, "sessionid")

    private suspend fun postCreate(url: String, sessionId: String, draft: TradeDraft): CreateOfferResult {
        // Build the Steam json_tradeoffer / create-params objects in JS, then serialize them to the
        // form's string fields (Ktor sends the same application/x-www-form-urlencoded body).
        val me: dynamic = js("({assets:[],currency:[],ready:false})")
        val assets = js("([])")
        draft.assetsToGive.forEach { asset ->
            val a: dynamic = js("({amount:1})")
            a.appid = adapter.game.appId
            a.contextid = adapter.inventoryContextId.toString()
            a.assetid = asset.value
            assets.push(a)
        }
        me.assets = assets
        val them: dynamic = js("({assets:[],currency:[],ready:false})")
        val jsonTradeOffer: dynamic = js("({newversion:true,version:2})")
        jsonTradeOffer.me = me
        jsonTradeOffer.them = them

        val createParams: dynamic = js("({})")
        draft.tradeToken?.let { createParams.trade_offer_access_token = it }

        val response = try {
            httpClient.submitForm(
                url = url,
                formParameters = parameters {
                    append("sessionid", sessionId)
                    append("serverid", "1")
                    append("partner", draft.partner.value)
                    append("tradeoffermessage", "")
                    append("json_tradeoffer", JSON.stringify(jsonTradeOffer))
                    append("trade_offer_create_params", JSON.stringify(createParams))
                },
            ) {
                // Steam's own {"strError":…}/EResult text IS the diagnosis for a failed create, so this is
                // the one request in the core that opts in to carrying its error body. Bounded and scrubbed
                // by the transport (see captureErrorBody) because this string does not stay local: it
                // becomes the directive outcome POSTed to DMarket, is persisted, and reaches the web page.
                captureErrorBody(STEAM_CREATE_ERROR_BODY_MAX)
            }
        } catch (e: HttpStatusException) {
            return CreateOfferResult.Failed(
                "Steam create returned HTTP ${e.statusCode}${e.errorBody?.let { ": $it" }.orEmpty()}",
            )
        }

        val parsed: dynamic = JSON.parse(response.bodyAsText())
        val offerId = parsed?.tradeofferid as? String
            ?: return CreateOfferResult.Failed("Steam create response missing tradeofferid")
        val needsMobile = parsed.needs_mobile_confirmation == true || parsed.needs_email_confirmation == true
        return if (needsMobile) {
            CreateOfferResult.NeedsConfirmation(OfferId(offerId))
        } else {
            CreateOfferResult.Created(OfferId(offerId))
        }
    }

    private companion object {
        /** Session-rule id for the create anti-CSRF rewrite; owned here now (no longer a standing sw.js rule). */
        const val ANTI_CSRF_RULE_ID = 1

        /**
         * Cap on the redacted Steam error body kept on a failed create. Enough for an EResult /
         * `{"strError":…}` envelope, far short of a Steam HTML error page — this string is POSTed to
         * DMarket, persisted, and handed to the web page.
         */
        const val STEAM_CREATE_ERROR_BODY_MAX = 512
    }
}
