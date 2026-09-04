package com.dmarket.p2p.tracker.port.steam

import com.dmarket.p2p.tracker.model.steam.SteamCredential

/**
 * Re-acquires a fresh [SteamCredential] from an **already-authenticated** steamcommunity.com
 * session, *without any user interaction*. This is the silent, background half of credential
 * acquisition; the interactive half (presenting a login UI) is the host application's job — see
 * "Background vs interactive" below.
 *
 * The credential is the `data-loyalty_webapi_token` JWT embedded in the Steam Community HTML for
 * authenticated users. The long-lived secret is the platform's Steam **cookie session**, which we
 * never store and never transmit — each platform's actual reads it from wherever that session
 * already lives:
 * - **Web extension:** the browser's shared cookie jar (`fetch(..., credentials = "include")`).
 *   No open Steam tab is required, only a logged-in cookie session.
 * - **Android / iOS (deferred):** the app's own authenticated WebView / cookie store.
 *
 * **Background vs interactive (cross-platform contract):** this port only ever performs the silent
 * background attempt and **must not block waiting on a human**. When no authenticated session is
 * available it returns `null`; the core surfaces that as a re-login signal
 * (`SteamCredentialProvider.lastRefreshFailedLoggedOut` → `TradeTrackerLoop.needsReLogin`) and the
 * host application — extension popup, Android Activity, or iOS view — is responsible for presenting
 * the Steam login UI. Once the user signs in (re-establishing the cookie session), the next
 * background `scrape()` succeeds automatically; no explicit hand-off is required.
 *
 * Return contract:
 * - Returns the fresh [SteamCredential] on success.
 * - Returns `null` if no authenticated session is available (expected "needs login" signal).
 * - **Throws** on transient infrastructure errors (network failure, unexpected HTML shape) so the
 *   caller can distinguish "needs login" (a UI prompt) from "try again later" (no prompt).
 *
 * Hard audit boundary: the scraped token is stored **on the device only** and is never transmitted
 * to the DMarket backend. No port in this codebase accepts a [SteamCredential] as a marketplace
 * request argument — see `MarketplaceClient`.
 */
interface SteamSessionScraper {
    suspend fun scrape(): SteamCredential?
}
