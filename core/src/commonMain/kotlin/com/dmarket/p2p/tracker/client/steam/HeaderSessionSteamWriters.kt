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
import com.dmarket.p2p.tracker.runtime.newSteamSessionId
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.parameters

/**
 * How a header-carrying write carries the Steam session: the `steamLoginSecure` and `sessionid` cookies, and the
 * `sessionid` double-submitted in the form. Steam only checks that the cookie and the form agree.
 */
internal sealed interface SteamWriteSession {
    /** The `sessionid` this write sends in its form. */
    suspend fun sessionId(): String

    /** Adds this write's session cookies to the request, if the client does not send them itself. */
    fun HttpRequestBuilder.cookies(credential: SteamCredential, sessionId: String)

    /**
     * Headless: the `steamLoginSecure` cookie is built from the [SteamCredential] the loop hands the write, and the
     * host's [sessionId] goes as the `sessionid` cookie, both in an explicit `Cookie` header.
     */
    class Fixed(private val sessionId: String) : SteamWriteSession {
        override suspend fun sessionId(): String = sessionId

        override fun HttpRequestBuilder.cookies(credential: SteamCredential, sessionId: String) {
            val loginSecure = SteamSessionCookie(credential.subjectSteamId, credential.token, credential.expiresAt).cookieValue()
            header(HttpHeaders.Cookie, "steamLoginSecure=$loginSecure; sessionid=$sessionId")
        }
    }

    /**
     * Mobile: the session is the core's vault. The client's [VaultCookieJar] plugin sends its `steamLoginSecure` and
     * `sessionid` for the community host, so the write adds no `Cookie` of its own and each cookie goes once. The
     * form's `sessionid` is the vault's; with none there, a fresh one is stored first, so the cookie the plugin sends
     * is the same value. The credential is not sent: the vault's session is the one the wrong-account guard checked.
     */
    class Vault(private val jar: VaultCookieJar, communityBaseUrl: String) : SteamWriteSession {
        private val communityUrl = Url("$communityBaseUrl/")

        override suspend fun sessionId(): String = jar.valueOrPut(communityUrl, SESSION_ID_COOKIE, ::newSteamSessionId)

        override fun HttpRequestBuilder.cookies(credential: SteamCredential, sessionId: String) = Unit
    }
}

private const val SESSION_ID_COOKIE = "sessionid"

private fun HttpRequestBuilder.steamSession(
    session: SteamWriteSession,
    credential: SteamCredential,
    sessionId: String,
    communityBaseUrl: String,
    referer: String,
) {
    with(session) { cookies(credential, sessionId) }
    header(HttpHeaders.Referrer, referer)
    header(HttpHeaders.Origin, communityBaseUrl)
}

/**
 * [SteamOfferCreator] for a runtime with no browser session, where the request carries the session itself: the
 * anti-CSRF `Referer`/`Origin` are explicit headers, and the `steamLoginSecure` and `sessionid` cookies come from the
 * host's `sessionId` and the credential the loop passes in (headless) or from the core's vault through the client's
 * [VaultCookieJar] (mobile). The browser cannot set any of them and keeps [FetchSteamOfferCreator]-style ambient
 * cookies; the form and the answer are the same ([SteamOfferWire]).
 *
 * No mutex, unlike the browser's: every header here belongs to its own request, so concurrent creates share nothing
 * but the vault's `sessionid`, which [VaultCookieJar.valueOrPut] reads or creates atomically. Which creates run
 * together is the loop's ([com.dmarket.p2p.tracker.engine.CreateChainPlanner], the deal write guard, the throttle).
 *
 * **Hard-rule enforcement:** the only URL built here is the fixed `…/tradeoffer/new/send`, and the flow stops
 * at `CreatedNeedsConfirmation`. No confirm, `mobileconf` or Guard request exists in this file.
 */
class HeaderSessionSteamOfferCreator internal constructor(
    private val httpClient: HttpClient,
    private val session: SteamWriteSession,
    private val communityBaseUrl: String,
    private val adapter: GameAdapter,
) : SteamOfferCreator {

    /** Headless: [sessionId] and the credential's `steamLoginSecure` go in an explicit `Cookie` header. */
    constructor(
        httpClient: HttpClient,
        sessionId: String,
        communityBaseUrl: String = "https://steamcommunity.com",
        adapter: GameAdapter = Cs2GameAdapter(),
    ) : this(httpClient, SteamWriteSession.Fixed(sessionId), communityBaseUrl, adapter)

    /** Mobile: the session is [cookies]' vault; [httpClient] must be built with the same jar. */
    constructor(
        httpClient: HttpClient,
        cookies: VaultCookieJar,
        communityBaseUrl: String = "https://steamcommunity.com",
        adapter: GameAdapter = Cs2GameAdapter(),
    ) : this(httpClient, SteamWriteSession.Vault(cookies, communityBaseUrl), communityBaseUrl, adapter)

    override suspend fun createOffer(credential: SteamCredential, draft: TradeDraft): CreateOfferResult = runCatching {
        val sessionId = session.sessionId()
        val response = httpClient.submitForm(
            url = "$communityBaseUrl/tradeoffer/new/send",
            formParameters = SteamOfferWire.createForm(sessionId, draft, adapter),
        ) {
            steamSession(session, credential, sessionId, communityBaseUrl, SteamOfferWire.createReferer(communityBaseUrl, draft))
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
class HeaderSessionSteamOfferCanceller internal constructor(
    private val httpClient: HttpClient,
    private val session: SteamWriteSession,
    private val communityBaseUrl: String,
) : SteamOfferCanceller {

    /** Headless: [sessionId] and the credential's `steamLoginSecure` go in an explicit `Cookie` header. */
    constructor(
        httpClient: HttpClient,
        sessionId: String,
        communityBaseUrl: String = "https://steamcommunity.com",
    ) : this(httpClient, SteamWriteSession.Fixed(sessionId), communityBaseUrl)

    /** Mobile: the session is [cookies]' vault; [httpClient] must be built with the same jar. */
    constructor(
        httpClient: HttpClient,
        cookies: VaultCookieJar,
        communityBaseUrl: String = "https://steamcommunity.com",
    ) : this(httpClient, SteamWriteSession.Vault(cookies, communityBaseUrl), communityBaseUrl)

    override suspend fun cancelOffer(credential: SteamCredential, offerId: OfferId): CancelOfferResult = steamCancelAnswer {
        val sessionId = session.sessionId()
        val url = "$communityBaseUrl/tradeoffer/${offerId.value}/cancel"
        httpClient.submitForm(url = url, formParameters = parameters { append(SESSION_ID_COOKIE, sessionId) }) {
            steamSession(session, credential, sessionId, communityBaseUrl, referer = "$communityBaseUrl/tradeoffer/${offerId.value}/")
            captureErrorBody(STEAM_CANCEL_ERROR_BODY_MAX)
        }
    }
}
