package com.dmarket.p2p.tracker.credential.marketplace

import com.dmarket.p2p.tracker.model.marketplace.MarketplaceCredential
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceCredentialProvider
import kotlinx.coroutines.CancellationException

/**
 * Adapts a host that **owns the DMarket session** — the Android and iOS apps — to [MarketplaceCredentialProvider],
 * so this library adds no second refresh authority. It keeps no token and calls no refresh endpoint: every call asks
 * the host's [MarketplaceTokenSupplier], and the host's own single-flight refresh, storage, rotation and logout stay
 * the only ones.
 *
 * - [current] asks for the token to use now (`supply(null)`); the host refreshes first by its own expiry rule.
 * - [forceRefresh] runs after DMarket refused a token with a 401 and passes that token on (`supply(rejected)`); the
 *   host refreshes only while it is still its current token, and otherwise answers with the newer one it holds.
 *
 * The host's "no session" signal is `null` or a throw (`TokenException` on Android). Per the
 * [MarketplaceCredentialProvider] contract either becomes `null` plus [lastRefreshFailedLoggedOut] = `true`, and the
 * tracker stops sending DMarket requests until a later call gets a token.
 *
 * @param supplier the host's token layer. A [MarketplaceTokenSupplier] rather than a bare lambda type so a host can
 *   implement it with a small class holding only its token manager — this object lives as long as the tracker, and a
 *   lambda written inline in a DI module pins that whole enclosing scope for the same lifetime.
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

    override suspend fun current(): MarketplaceCredential? = ask(rejectedToken = null)

    override suspend fun forceRefresh(rejectedToken: String?): MarketplaceCredential? = ask(rejectedToken)

    private suspend fun ask(rejectedToken: String?): MarketplaceCredential? {
        val credential = try {
            supplier.supply(rejectedToken)
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
 * The host app's DMarket token layer, as the tracker sees it. The same contract on Android and iOS.
 *
 * `supply(null)`: the token to use now. Refresh first if the app's own rule says it is about to expire.
 *
 * `supply(rejected)`: DMarket answered 401 to a request carrying `rejected`. Refresh only if `rejected` is still the
 * current token, inside the app's single-flight refresh; if it is not (the app refreshed meanwhile), return the
 * current token without refreshing. If the app's own rule says a refused token ends the session (Android: a current
 * token no 2xx ever confirmed), end it and return `null`.
 *
 * Return `null` (or throw) when there is no session; the tracker then stops and refreshes nothing itself. Never block
 * on user interaction.
 *
 * Mapping on each app:
 * - **Android**: `TokenDataHolder.getTokenData(forceRefresh = rejected != null, failedToken = rejected)`, which already
 *   holds the rule above. The public `TokenManager.getTokenData(forceRefresh)` passes no `failedToken`, so the app
 *   exposes one that does (DMA-663 2/4) and wraps it here, mapping `TokenException` to a throw or `null`.
 * - **iOS**: a new `async` method on `AuthHandler` that reuses its single-flight refresh (`pendingRequests` /
 *   `performRefreshToken`): with no rejected token it returns the current one, refreshing first when
 *   `shouldUpdateToken`; with one it refreshes only if `rejected` equals the current access token, as its own retry
 *   check does; a 401 from `/refresh-token` logs out and returns `nil`. Kotlin/Native exports this interface as an
 *   Objective-C protocol whose `supply` Swift implements as `async` (not built here: the iOS target is deferred).
 */
fun interface MarketplaceTokenSupplier {
    /** @param rejectedToken the token DMarket refused with a 401, or `null` for the token to use now. */
    suspend fun supply(rejectedToken: String?): MarketplaceCredential?
}
