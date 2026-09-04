// PHASE 3 SCAFFOLD — reference only, NOT compiled until the Android target is enabled in
// build-logic/.../dmarket.kmp.library.gradle.kts (androidTarget()). KMP ignores this source set until then;
// it is linted by spotless but not type-checked.
//
// READ THIS BEFORE WRITING ANY ANDROID DMARKET-AUTH CODE. There is deliberately no adapter here that names
// the app's own `TokenManager`: that type lives in the application repository, and the dependency runs
// app → library, never the reverse. The reusable piece is `HostTokenMarketplaceCredentialProvider` in
// commonMain, which is compiled and unit-tested today; this file exists only to put the wiring in front of
// whoever opens `androidMain` looking for it. The same snippet is in README.md, which is where the
// application author will actually read it.
package com.dmarket.p2p.tracker.credential.marketplace

/**
 * How an Android host wires DMarket auth — **by delegating to the token layer it already has**.
 *
 * The application owns a complete refresh mechanism (`:token_manager_api` / `:token_manager_impl`): a
 * mutex-serialised `getToken(forceRefresh)` over its own encrypted storage, its own rotation of the token
 * pair against `POST /marketplace-api/v1/refresh-token`, and its own 401 handling. This library must add no
 * second refresh authority for that host — two authorities rotating one shared credential is strictly worse
 * than either alone — so it does not construct `DefaultMarketplaceCredentialProvider` on Android at all.
 *
 * ```kotlin
 * // In the application's DI module (Koin), where TokenManager is already a singleton:
 * val marketplaceCredentials: MarketplaceCredentialProvider =
 *     HostTokenMarketplaceCredentialProvider { force ->
 *         // TokenException (no usable pair) → the default `treatFailureAsLoggedOut = true` turns this into
 *         // null + lastRefreshFailedLoggedOut, which is the library's "show the sign-in prompt" signal.
 *         val data = tokenManager.getTokenData(forceRefresh = force)
 *         MarketplaceCredential(
 *             token = data.authorizationToken,
 *             expiresAt = Instant.fromEpochSeconds(data.authorizationTokenExpirationTimestamp),
 *         )
 *     }
 *
 * // …then hand it to the loop factory, and let the marketplace client authenticate through it:
 * val marketplace = KtorMarketplaceClient(
 *     httpClient = androidMarketplaceClient(hostOkHttp),
 *     baseUrl = baseUrl,
 *     authenticator = CredentialMarketplaceAuthenticator(marketplaceCredentials),
 * )
 * ```
 *
 * Two notes for whoever does this:
 * - The app attaches `Authorization` **per Retrofit method** as a raw JWT (no `Bearer ` prefix); it has no
 *   interceptor that injects it. So `TransportManagedMarketplaceAuthenticator` — which attaches nothing on
 *   the assumption that the transport does — is the wrong choice, and an earlier version of this library's
 *   documentation said otherwise. Use `CredentialMarketplaceAuthenticator` as above.
 * - `tokenManager.tokenStateFlow` is a better signal than polling for the host's own UI, but the library
 *   does not need it: `lastRefreshFailedLoggedOut` is derived from each call's outcome.
 */
private const val ANDROID_MARKETPLACE_CREDENTIALS_NOTE = "see the KDoc above; there is no code to compile here"
