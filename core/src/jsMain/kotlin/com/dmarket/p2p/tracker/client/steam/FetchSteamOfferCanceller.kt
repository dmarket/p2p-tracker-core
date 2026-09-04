package com.dmarket.p2p.tracker.client.steam

import com.dmarket.p2p.tracker.adapter.webext.webExtCookieValue
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.port.steam.SteamOfferCanceller
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.parameters

/**
 * The **web / browser-extension** actual of [SteamOfferCanceller]. It cancels a *sent* Steam trade
 * offer — and only that — by POSTing to `https://steamcommunity.com/tradeoffer/{id}/cancel`.
 *
 * The POST goes through the injected credentialed Steam [HttpClient] ([com.dmarket.p2p.tracker.client.createSteamHttpClient]) whose
 * browser engine sets fetch `credentials:"include"`, so the logged-in Steam cookie session is
 * attached. Steam's community endpoints require the `sessionid` cookie echoed in the form body; it is
 * read via `chrome.cookies`.
 *
 * **Hard-rule enforcement:** the only URL this class ever builds is the fixed
 * `…/tradeoffer/{id}/cancel` template, and the port surface ([SteamOfferCanceller]) exposes only
 * cancel. There is no code path here that constructs a `mobileconf`, confirm, or send URL, so the
 * core structurally cannot send or confirm an offer.
 *
 * **Manifest requirements:** `host_permissions` for `https://steamcommunity.com` (cookie-bearing
 * POST + `sessionid` read) and the `"cookies"` permission.
 *
 * **The HTTP status alone does not decide the outcome.** Steam's community endpoints answer some refusals
 * `200` with an `EResult` envelope (`{"success":<EResult>}`), so this returns normally only when the body
 * does not carry a non-OK `EResult` — see `failOnRefusal`. Anything else would report the directive
 * SUCCESS with the offer still live on Steam.
 *
 * The [credential] is accepted for audit-consistency with the other Steam-facing ports; the cancel
 * itself is authorised by the ambient cookie session, not the IEconService token.
 */
class FetchSteamOfferCanceller(private val httpClient: HttpClient, private val communityBaseUrl: String = "https://steamcommunity.com") :
    SteamOfferCanceller {

    override suspend fun cancelOffer(credential: SteamCredential, offerId: OfferId) {
        val url = "$communityBaseUrl/tradeoffer/${offerId.value}/cancel"
        // A missing cookie must THROW: returning silently would let the loop report the cancel as
        // SUCCESS while the offer is still live on Steam.
        val sessionId = readSessionId() ?: error("no Steam session cookie")
        // The shared Steam client sanitizes failures, so a non-OK Steam status (403/500) throws
        // HttpStatusException, and a transport error throws too — either way the loop treats the cancel as
        // failed (the offer is still live) and retries, rather than assuming it succeeded.
        val response = httpClient.submitForm(url = url, formParameters = parameters { append("sessionid", sessionId) })
        // …and a 2xx is not by itself a cancelled offer: Steam answers some refusals `200 {"success":<EResult>}`.
        // Accepting every 2xx would report the directive SUCCESS — which marks it handled and releases the
        // deal's create claim — with the offer still live on Steam.
        failOnRefusal(response.bodyAsText())
    }

    // ---- private -----------------------------------------------------------------------------------

    /**
     * Throws if a 2xx cancel [body] carries a **non-OK** Steam `EResult`.
     *
     * A cancelled offer is answered `{"tradeofferid":"<id>"}` — no `success` key at all — so only a
     * **present** and non-OK `success` is a refusal. An absent or unparseable envelope stays a success, as
     * before: inventing a failure out of Steam's OK shape would strand the directive and re-POST the cancel
     * on every re-lease.
     *
     * The message carries the EResult and nothing else — no URL, no body. That is deliberate for the same
     * reason [com.dmarket.p2p.tracker.client.HttpCallFailureException] exists: this string becomes a
     * directive outcome that is POSTed to DMarket, persisted, and handed to the web page.
     */
    private fun failOnRefusal(body: String) {
        val parsed: dynamic = try {
            JSON.parse<Any?>(body)
        } catch (_: Throwable) {
            null
        }
        val success: dynamic = if (parsed == null) null else parsed.success
        if (success == null || jsTypeOf(success) == "undefined") return
        // Compared as text so the check never rides JS loose equality (`1 == true`). Both spellings of OK
        // are accepted: Steam has been seen answering `true` where an int is documented (see SteamDtos.kt).
        val eresult = success.toString()
        if (eresult != "1" && eresult != "true") error("Steam cancel refused with EResult $eresult")
    }

    /** Reads the `sessionid` cookie for steamcommunity.com via the extension cookies API. */
    private suspend fun readSessionId(): String? = webExtCookieValue(communityBaseUrl, "sessionid")
}
