package com.dmarket.p2p.tracker.client.steam

import com.dmarket.p2p.tracker.client.captureErrorBody
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.port.steam.CancelOfferResult
import com.dmarket.p2p.tracker.port.steam.SteamOfferCanceller
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.http.parameters

/**
 * The **web / browser-extension** actual of [SteamOfferCanceller]. It cancels a *sent* Steam trade
 * offer — and only that — by POSTing to `https://steamcommunity.com/tradeoffer/{id}/cancel`.
 *
 * The POST goes through the injected credentialed Steam [HttpClient] ([com.dmarket.p2p.tracker.client.createSteamHttpClient]) whose
 * browser engine sets fetch `credentials:"include"`, so the logged-in Steam cookie session is
 * attached. Steam's community endpoints require the `sessionid` cookie echoed in the form body; it is
 * read via `chrome.cookies`, minted first when absent (see [steamSessionId]).
 *
 * **Hard-rule enforcement:** the only URL this class ever builds is the fixed
 * `…/tradeoffer/{id}/cancel` template, and the port surface ([SteamOfferCanceller]) exposes only
 * cancel. There is no code path here that constructs a `mobileconf`, confirm, or send URL, so the
 * core structurally cannot send or confirm an offer.
 *
 * **Manifest requirements:** `host_permissions` for `https://steamcommunity.com` (cookie-bearing
 * POST + `sessionid` read) and the `"cookies"` permission.
 *
 * **The HTTP status alone does not decide the outcome.** Steam serves its `EResult` envelope
 * (`{"success":<EResult>}`) under a `500` as readily as under a `200`, so the body is read on every status
 * ([SteamOfferWire.readCancel]) and only the positive shape reads as a cancelled offer. An HTML page, a login
 * redirect or an empty body is refused: reporting it SUCCESS would leave the offer live on Steam.
 *
 * The [credential] is accepted for audit-consistency with the other Steam-facing ports; the cancel
 * itself is authorised by the ambient cookie session, not the IEconService token.
 */
class FetchSteamOfferCanceller(private val httpClient: HttpClient, private val communityBaseUrl: String = "https://steamcommunity.com") :
    SteamOfferCanceller {

    override suspend fun cancelOffer(credential: SteamCredential, offerId: OfferId): CancelOfferResult {
        val url = "$communityBaseUrl/tradeoffer/${offerId.value}/cancel"
        // No cookie, no request: nothing reached Steam, so the cancel is refused, never reported done.
        val sessionId = httpClient.steamSessionId(communityBaseUrl) ?: return CancelOfferResult.Refused("no Steam session cookie")
        return steamCancelAnswer {
            httpClient.submitForm(url = url, formParameters = parameters { append("sessionid", sessionId) }) {
                captureErrorBody(STEAM_CANCEL_ERROR_BODY_MAX)
            }
        }
    }
}
