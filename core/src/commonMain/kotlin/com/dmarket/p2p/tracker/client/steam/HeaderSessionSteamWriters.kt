package com.dmarket.p2p.tracker.client.steam

import com.dmarket.p2p.tracker.client.HttpStatusException
import com.dmarket.p2p.tracker.client.captureErrorBody
import com.dmarket.p2p.tracker.game.Cs2GameAdapter
import com.dmarket.p2p.tracker.game.GameAdapter
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.model.steam.SteamSessionCookie
import com.dmarket.p2p.tracker.model.steam.TradeDraft
import com.dmarket.p2p.tracker.net.redactedSummary
import com.dmarket.p2p.tracker.port.steam.CancelOfferResult
import com.dmarket.p2p.tracker.port.steam.CreateOfferResult
import com.dmarket.p2p.tracker.port.steam.SteamOfferCanceller
import com.dmarket.p2p.tracker.port.steam.SteamOfferCreator
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.parameters

/**
 * The Steam session a header-carrying write sends: the `steamLoginSecure` cookie built from the [SteamCredential]
 * the loop hands the write, and the [sessionId] double-submit value, sent both as the `sessionid` cookie and in
 * the form. Steam only checks that the two agree, so a host with no browser generates its own.
 */
private fun HttpRequestBuilder.steamSession(credential: SteamCredential, sessionId: String, communityBaseUrl: String, referer: String) {
    val loginSecure = SteamSessionCookie(credential.subjectSteamId, credential.token, credential.expiresAt).cookieValue()
    header(HttpHeaders.Cookie, "steamLoginSecure=$loginSecure; sessionid=$sessionId")
    header(HttpHeaders.Referrer, referer)
    header(HttpHeaders.Origin, communityBaseUrl)
}

/**
 * [SteamOfferCreator] for a runtime with no browser session, where the request carries the session itself:
 * the `steamLoginSecure` and `sessionid` cookies and the anti-CSRF `Referer`/`Origin` are explicit headers, set
 * per request from the credential the loop passes in. The browser cannot set any of them and keeps
 * [FetchSteamOfferCreator]-style ambient cookies; the form and the answer are the same ([SteamOfferWire]).
 *
 * **Hard-rule enforcement:** the only URL built here is the fixed `…/tradeoffer/new/send`, and the flow stops
 * at `CreatedNeedsConfirmation`. No confirm, `mobileconf` or Guard request exists in this file.
 */
class HeaderSessionSteamOfferCreator(
    private val httpClient: HttpClient,
    private val sessionId: String,
    private val communityBaseUrl: String = "https://steamcommunity.com",
    private val adapter: GameAdapter = Cs2GameAdapter(),
) : SteamOfferCreator {

    override suspend fun createOffer(credential: SteamCredential, draft: TradeDraft): CreateOfferResult = runCatching {
        val response = httpClient.submitForm(
            url = "$communityBaseUrl/tradeoffer/new/send",
            formParameters = SteamOfferWire.createForm(sessionId, draft, adapter),
        ) {
            steamSession(credential, sessionId, communityBaseUrl, SteamOfferWire.createReferer(communityBaseUrl, draft))
            // Steam's own {"strError":…}/EResult text IS the diagnosis for a failed create, and the throttle
            // classifies on it; bounded and scrubbed by the transport, because it becomes a directive outcome.
            captureErrorBody(STEAM_CREATE_ERROR_BODY_MAX)
        }
        SteamOfferWire.parseCreate(response.bodyAsText())
    }.getOrElse { e ->
        if (e is HttpStatusException) {
            SteamOfferWire.createRefusal(e.statusCode, e.errorBody)
        } else {
            CreateOfferResult.Failed(e.redactedSummary())
        }
    }

    private companion object {
        /** Same cap as the browser create: enough for an EResult envelope, far short of an HTML error page. */
        const val STEAM_CREATE_ERROR_BODY_MAX = 512
    }
}

/**
 * [SteamOfferCanceller] for a runtime with no browser session — see [HeaderSessionSteamOfferCreator] for how the
 * session travels. Cancels a sent offer and nothing else: the only URL built here is the fixed
 * `…/tradeoffer/{id}/cancel`.
 *
 * Steam's answer is read on every HTTP status ([SteamOfferWire.readCancel]); no answer at all is
 * [CancelOfferResult.Unanswered]. Only the positive shape reads as a cancelled offer, so the loop never reports a
 * cancel SUCCESS with the offer still live.
 */
class HeaderSessionSteamOfferCanceller(
    private val httpClient: HttpClient,
    private val sessionId: String,
    private val communityBaseUrl: String = "https://steamcommunity.com",
) : SteamOfferCanceller {

    override suspend fun cancelOffer(credential: SteamCredential, offerId: OfferId): CancelOfferResult = steamCancelAnswer {
        val url = "$communityBaseUrl/tradeoffer/${offerId.value}/cancel"
        httpClient.submitForm(url = url, formParameters = parameters { append("sessionid", sessionId) }) {
            steamSession(credential, sessionId, communityBaseUrl, referer = "$communityBaseUrl/tradeoffer/${offerId.value}/")
            captureErrorBody(STEAM_CANCEL_ERROR_BODY_MAX)
        }
    }
}
