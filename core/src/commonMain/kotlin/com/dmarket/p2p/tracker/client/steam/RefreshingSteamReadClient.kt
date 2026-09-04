package com.dmarket.p2p.tracker.client.steam

import com.dmarket.p2p.tracker.client.HttpStatusException
import com.dmarket.p2p.tracker.credential.steam.SteamCredentialProvider
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.model.steam.SteamOfferSnapshot
import com.dmarket.p2p.tracker.model.steam.SteamTransfer
import com.dmarket.p2p.tracker.port.steam.SteamReadClient
import io.ktor.http.HttpStatusCode

/**
 * [SteamReadClient] decorator that reacts to HTTP **401/403** responses by forcing a credential
 * refresh and retrying the original request **once**.
 *
 * **Why this is needed:** proactive freshness checks ([SteamCredential.isFresh]) key off the JWT's
 * own `exp` claim. A token that has been early-revoked or rotated server-side still has a future
 * `exp`, so proactive refresh alone will not catch it — the loop silently fails every tick for up to
 * ~24 h. This decorator catches the rejection, forces [SteamCredentialProvider.forceRefresh]
 * (re-scrapes a fresh token), and retries with the new token. A second rejection propagates as-is.
 *
 * **401 vs 403:** Steam's `IEconService` returns **403** ("verify your `key=` parameter") for a
 * rejected/rotated `access_token`, not 401, so both must trigger the refresh. The status is read from
 * [HttpStatusException] — every Steam read flows through the sanitized Ktor transport.
 */
class RefreshingSteamReadClient(private val delegate: SteamReadClient, private val provider: SteamCredentialProvider) : SteamReadClient {

    override suspend fun offerSnapshots(credential: SteamCredential, offerIds: Set<OfferId>): Map<OfferId, SteamOfferSnapshot> =
        withRefreshRetry(credential) { delegate.offerSnapshots(it, offerIds) }

    override suspend fun recentTransfers(credential: SteamCredential, maxTrades: Int): List<SteamTransfer> =
        withRefreshRetry(credential) { delegate.recentTransfers(it, maxTrades) }

    // ---- private -----------------------------------------------------------------------------------

    private suspend fun <T> withRefreshRetry(credential: SteamCredential, block: suspend (SteamCredential) -> T): T = try {
        block(credential)
    } catch (e: Exception) {
        if (isAuthRejection(e)) {
            // Force a credential refresh. If logged out (null), re-throw the original rejection.
            val fresh = provider.forceRefresh() ?: throw e
            block(fresh) // retry once — a second 401/403 propagates
        } else {
            throw e
        }
    }

    /** True if [e] is a Steam 401/403 surfaced by [HttpStatusException]. */
    private fun isAuthRejection(e: Exception): Boolean {
        val status = when (e) {
            is HttpStatusException -> e.statusCode
            else -> return false
        }
        return status == HttpStatusCode.Unauthorized.value || status == HttpStatusCode.Forbidden.value
    }
}
