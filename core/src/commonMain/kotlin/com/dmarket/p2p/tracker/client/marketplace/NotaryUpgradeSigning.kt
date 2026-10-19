package com.dmarket.p2p.tracker.client.marketplace

import com.dmarket.p2p.tracker.notary.NotaryUpgradeHeaders
import com.dmarket.p2p.tracker.notary.NotaryUpgradeSigner

/**
 * Signs the notary's WebSocket upgrade with the Trading API key [authenticator] holds: `GET` of the notary URL's
 * path with an empty body, the same scheme as every other DMarket call ([TradingApiSignature]). The gateway checks
 * that signature before the upgrade reaches the notary, and the notary takes the account from it.
 *
 * The path is signed as the client sends it, trailing slash included (`/provenance/v1/`): that is the path the
 * gateway sees before it strips the route's prefix.
 *
 * Every [sign] signs afresh, at the clock's current second; a signature is never reused.
 */
class TradingApiNotaryUpgradeSigner(private val authenticator: TradingApiAuthenticator, notaryUrl: String) : NotaryUpgradeSigner {
    private val request = MarketplaceRequest(method = "GET", pathAndQuery = notaryUpgradePath(notaryUrl), body = "")

    override suspend fun sign(): NotaryUpgradeHeaders {
        val headers = authenticator.headersFor(request)
        check(headers.isNotEmpty()) { "no Trading API key is held to sign the notary upgrade" }
        return NotaryUpgradeHeaders(headers)
    }
}

/** The path and query of [notaryUrl], as signed: `/` when it has no path, never the fragment. */
internal fun notaryUpgradePath(notaryUrl: String): String {
    val afterScheme = notaryUrl.substringAfter("://", missingDelimiterValue = notaryUrl).substringBefore('#')
    val start = afterScheme.indexOfFirst { it == '/' || it == '?' }
    if (start < 0) return "/"
    val rest = afterScheme.substring(start)
    return if (rest.startsWith("?")) "/$rest" else rest
}
