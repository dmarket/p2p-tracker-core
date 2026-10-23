package com.dmarket.p2p.tracker.client.steam

import com.dmarket.p2p.tracker.client.HttpStatusException
import com.dmarket.p2p.tracker.net.redactedSummary
import com.dmarket.p2p.tracker.port.steam.CancelOfferResult
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlin.coroutines.cancellation.CancellationException

/**
 * Cap on the redacted body a non-2xx cancel keeps for [SteamOfferWire.readCancel]: enough for Steam's EResult
 * envelope or a `strError`, far short of an HTML error page, which reads as refused either way.
 */
internal const val STEAM_CANCEL_ERROR_BODY_MAX = 512

/**
 * What the cancel [post] got back, read on every status. The request must opt in with
 * `captureErrorBody(STEAM_CANCEL_ERROR_BODY_MAX)`, or a non-2xx carries no body to read. Anything thrown other
 * than a status — a timeout, a refused connection, a failed fetch — is no response at all: [CancelOfferResult.Unanswered].
 */
internal suspend fun steamCancelAnswer(post: suspend () -> HttpResponse): CancelOfferResult = try {
    val response = post()
    SteamOfferWire.readCancel(response.status.value, runCatching { response.bodyAsText() }.getOrNull())
} catch (e: CancellationException) {
    throw e
} catch (e: HttpStatusException) {
    SteamOfferWire.readCancel(e.statusCode, e.errorBody)
} catch (e: Throwable) {
    CancelOfferResult.Unanswered(e.redactedSummary())
}
