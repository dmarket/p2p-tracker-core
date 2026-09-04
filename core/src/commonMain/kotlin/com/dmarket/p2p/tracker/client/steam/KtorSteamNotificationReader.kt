package com.dmarket.p2p.tracker.client.steam

import com.dmarket.p2p.tracker.client.suppressResponseBodyCapture
import com.dmarket.p2p.tracker.config.SteamEndpointsConfig
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.steam.ReversalAttribution
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.port.steam.SteamNotificationReader
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlin.time.Instant

/**
 * Ktor-backed [SteamNotificationReader]: reads `ISteamNotificationService/GetSteamNotifications` and
 * resolves the reversal actor via the pure [ReversalAttribution] rule.
 *
 * Auth is the `access_token` query parameter (the [KtorSteamReadClient] convention), not an
 * Authorization header.
 *
 * **Both `include_read` and `include_hidden` are mandatory.** Reading a notification sets `read` and
 * deleting sets `hidden`; the actor survives both, but omitting either flag yields an empty response —
 * which is indistinguishable from "no notification" and would silently defeat attribution.
 *
 * **Privacy.** The endpoint has no server-side type filter, so the response carries the account's whole
 * retained notification history. This class is the only place that payload exists: it maps to the three
 * fields the rule needs, returns a single [SteamId] or `null`, and calls [suppressResponseBodyCapture] so
 * the body never reaches a host `NetworkObserver` (the same treatment as the session-scrape HTML). Nothing
 * downstream can observe unrelated notifications.
 *
 * A failed read returns `null` rather than throwing: Steam signs out whoever performed a rollback, so a
 * failure here is the *expected* branch, not an error worth propagating into the watch cycle.
 */
class KtorSteamNotificationReader(
    private val httpClient: HttpClient,
    private val endpoints: SteamEndpointsConfig = SteamEndpointsConfig(),
) : SteamNotificationReader {

    override suspend fun reversalInitiator(credential: SteamCredential, counterparty: SteamId?, modifiedAt: Instant?): SteamId? {
        // Undecidable before any request — don't touch the notification stream at all.
        if (counterparty == null || modifiedAt == null) return null
        val body = runCatching {
            httpClient.get("${endpoints.steamApiBaseUrl}${endpoints.getSteamNotificationsPath}") {
                parameter(endpoints.paramAccessToken, credential.token)
                parameter(endpoints.paramIncludeRead, true)
                parameter(endpoints.paramIncludeHidden, true)
                parameter(endpoints.paramLanguage, "english")
                suppressResponseBodyCapture()
            }.bodyAsText()
        }.getOrNull() ?: return null
        val notifications = runCatching { SteamReadResponses.notifications(body) }.getOrNull() ?: return null
        return ReversalAttribution.resolve(notifications, counterparty, modifiedAt)
    }
}
