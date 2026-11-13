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
 * The host's "no session" signal is `null`: it becomes `null` plus [lastRefreshFailedLoggedOut] = `true`, and the
 * tracker stops sending DMarket requests until a later call gets a token. A *throw* is read by
 * [treatFailureAsLoggedOut]: the mobile tracker (`MobileTrackerPlatform`) sets it `false`, so there a throw is a
 * temporary failure that returns `null` for this call with [lastRefreshFailedLoggedOut] `false` and
 * [lastFailureTransient] `true`. The loop then counts a DMarket connection failure (after two in a row the host sees
 * `DM_CONNECTION_ERROR`), shows no sign-in prompt, and asks again on a later cycle. On iOS the Keychain on a locked
 * phone throws instead of returning `null`.
 *
 * @param supplier the host's token layer. A [MarketplaceTokenSupplier] rather than a bare lambda type so a host can
 *   implement it with a small class holding only its token manager — this object lives as long as the tracker, and a
 *   lambda written inline in a DI module pins that whole enclosing scope for the same lifetime.
 * @param treatFailureAsLoggedOut whether a *thrown* failure means "signed out" (this class's default, for a host whose
 *   token API throws only when it has no usable token pair) or merely "try again later". The mobile tracker passes
 *   `false`: its hosts return `null` for the signed-out case and throw on anything transient, so a network blip
 *   never shows the user a sign-in prompt.
 */
class HostTokenMarketplaceCredentialProvider(
    private val treatFailureAsLoggedOut: Boolean = true,
    private val supplier: MarketplaceTokenSupplier,
) : MarketplaceCredentialProvider {

    override var lastRefreshFailedLoggedOut: Boolean = false
        private set

    override var lastFailureTransient: Boolean = false
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
            lastFailureTransient = !treatFailureAsLoggedOut
            return null
        }
        lastFailureTransient = false
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
 * Return `null` when there is no session; the tracker then stops and refreshes nothing itself. On mobile a throw is
 * not "no session" but a temporary failure (network, a locked Keychain): the tracker keeps the session, shows no
 * sign-in prompt, counts it as a DMarket connection failure and asks again on a later cycle. Never block on user
 * interaction.
 *
 * Mapping on each app:
 * - **Android**: `TokenDataHolder.getTokenData(forceRefresh = rejected != null, failedToken = rejected)`, which already
 *   holds the rule above. The public `TokenManager.getTokenData(forceRefresh)` passes no `failedToken`, so the app
 *   exposes one that does (DMA-663 2/4) and wraps it here, mapping a `TokenException` for a missing session to `null` and an IO failure to a throw.
 * - **iOS**: a new `async` method on `AuthHandler` that reuses its single-flight refresh (`pendingRequests` /
 *   `performRefreshToken`): with no rejected token it returns the current one, refreshing first when
 *   `shouldUpdateToken`; with one it refreshes only if `rejected` equals the current access token, as its own retry
 *   check does; a 401 from `/refresh-token` logs out and returns `nil`; a Keychain read refused on a locked phone throws. Kotlin/Native exports this interface as an
 *   Objective-C protocol whose `supply` Swift implements as `async` (not built here: the iOS target is deferred).
 */
fun interface MarketplaceTokenSupplier {
    /** @param rejectedToken the token DMarket refused with a 401, or `null` for the token to use now. */
    suspend fun supply(rejectedToken: String?): MarketplaceCredential?
}
