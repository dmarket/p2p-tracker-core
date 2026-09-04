package com.dmarket.p2p.tracker.credential.marketplace

import com.dmarket.p2p.tracker.model.marketplace.MarketplaceCredential
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceCredentialProvider
import kotlinx.coroutines.CancellationException

/**
 * Adapts a host that **already owns DMarket token refresh** to [MarketplaceCredentialProvider], so this
 * library adds no second refresh authority.
 *
 * This is the whole mobile-reuse story, and it is deliberately tiny. The DMarket Android application's token
 * layer already exposes exactly the shape needed — a suspending call that returns a valid access token,
 * refreshing first if required, with a `forceRefresh` flag for the post-401 path, throwing when there is no
 * usable session. Wrapping that is a delegation, not a re-implementation: the app keeps its own storage,
 * its own single-flight mutex, its own rotation and its own 401 handling, and nothing here can race it.
 *
 * There is no platform code in this class, on purpose. Android and iOS wire it identically:
 *
 * ```kotlin
 * // Android (app side), with com.dmarket.dmarketmobile.token.manager.api.TokenManager:
 * val credentials = HostTokenMarketplaceCredentialProvider { force ->
 *     val data = tokenManager.getTokenData(forceRefresh = force)
 *     MarketplaceCredential(
 *         token = data.authorizationToken,
 *         expiresAt = Instant.fromEpochSeconds(data.authorizationTokenExpirationTimestamp),
 *     )
 * }
 * ```
 *
 * The host's "no session" signal is an exception (`TokenException` on Android). Per the
 * [MarketplaceCredentialProvider] contract this must become `null` plus
 * [lastRefreshFailedLoggedOut] = `true`, which returning `null` also expresses — so a host may either throw or
 * return `null`, whichever its own API already does.
 *
 * @param supplier produces a credential, refreshing when `force` is true. Return `null` (or throw) when no
 *   session is available; must not block on user interaction. A [MarketplaceTokenSupplier] rather than a bare
 *   lambda type so a host can implement it with a small class holding only its token manager — this object
 *   lives as long as the tracker, and a lambda written inline in a DI module pins that whole enclosing scope
 *   for the same lifetime.
 * @param treatFailureAsLoggedOut whether a *thrown* failure means "signed out" (the default, matching a host
 *   whose token API throws only when it has no usable token pair) or merely "try again later". Set this
 *   `false` for a host that also throws on transient IO, and have the supplier return `null` for the signed-out
 *   case instead — otherwise a network blip shows the user a sign-in prompt.
 */
class HostTokenMarketplaceCredentialProvider(
    private val treatFailureAsLoggedOut: Boolean = true,
    private val supplier: MarketplaceTokenSupplier,
) : MarketplaceCredentialProvider {

    override var lastRefreshFailedLoggedOut: Boolean = false
        private set

    override suspend fun current(): MarketplaceCredential? = ask(force = false)

    override suspend fun forceRefresh(): MarketplaceCredential? = ask(force = true)

    private suspend fun ask(force: Boolean): MarketplaceCredential? {
        val credential = try {
            supplier.supply(force)
        } catch (e: CancellationException) {
            // Structured concurrency: a cancelled scope is teardown, never a statement about the session.
            throw e
        } catch (_: Throwable) {
            lastRefreshFailedLoggedOut = treatFailureAsLoggedOut
            return null
        }
        lastRefreshFailedLoggedOut = credential == null
        return credential
    }
}

/**
 * Produces a DMarket access token, refreshing first if required — the host's own token layer, as this library
 * sees it.
 *
 * A `fun interface`, so it reads as a lambda at the call site while still letting a host declare a class that
 * holds only the one field it needs.
 */
fun interface MarketplaceTokenSupplier {
    /** @param force bypass the host's own freshness check (the post-401 path). */
    suspend fun supply(force: Boolean): MarketplaceCredential?
}
