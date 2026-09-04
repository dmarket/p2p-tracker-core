package com.dmarket.p2p.tracker.client.marketplace

import com.dmarket.p2p.tracker.adapter.webext.webExtApi
import com.dmarket.p2p.tracker.config.MarketplaceScrapeConfig
import com.dmarket.p2p.tracker.model.marketplace.MarketplaceTokenPair
import com.dmarket.p2p.tracker.model.marketplace.StoredMarketplaceTokens
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceTokenStore
import kotlinx.coroutines.async
import kotlinx.coroutines.await
import kotlinx.coroutines.coroutineScope
import kotlin.js.Promise
import kotlin.time.Instant

/** The browser globals used to bridge the site's cookie encoding — see [FetchMarketplaceCookieTokenStore]. */
external fun encodeURIComponent(value: String): String

external fun decodeURIComponent(encoded: String): String

/**
 * The **web** [MarketplaceTokenStore]: the DMarket token pair as it lives in the browser cookie jar, read and
 * written through the `cookies` extension API.
 *
 * This store is **shared with the dmarket.com single-page app**, which is what makes it unusual:
 *
 * ### Reading
 * `cookies.get` — not `cookies.getAll`. Chrome gates `getAll` (and `cookies.onChanged`) on a host permission
 * for a URL derived from the **cookie** — `(secure ? https : http)://<Domain without leading dot>/` — while
 * `cookies.get`, which takes an explicit `url`, does not. A session cookie scoped to a parent domain
 * therefore vanishes from `getAll` in a build that has not been granted that parent domain, and using
 * `getAll` here would turn a missing dev-environment grant into a confident, wrong "the user is signed out".
 *
 * ### The access cookie's expiry is a lie
 * The site gives **both** cookies the *refresh* token's ~30-day expiry on purpose (so an idle user is not
 * signed out), so `expirationDate` says nothing about the ~24 h access token. This store therefore reports no
 * access expiry at all and lets the shared algorithm read it from the token's own `exp` claim. The refresh
 * cookie's `expirationDate` *is* truthful for the refresh token, so that one is reported.
 *
 * ### Values are percent-encoded
 * The site writes cookies through `document.cookie` with `encodeURIComponent`, and reads them back with a
 * decode; the extension API is raw. So every value is decoded on read and encoded on write — otherwise a
 * token containing `+`, `/` or `=` would be presented to the refresh endpoint as `%2B%2F%3D` and refused,
 * which the refusal latch would then remember as "signed out".
 *
 * ### Writing is how the *site* keeps working
 * The refresh endpoint rotates the refresh token and does not `Set-Cookie` — the site's own JavaScript writes
 * both cookies from the response body. So [write] must put both back, and it:
 * - mirrors the attributes of the record it read (`domain`/`hostOnly`, `path`, `secure`, `sameSite`) rather
 *   than inventing them, because a mismatched `domain` creates a *second* record with the same name and the
 *   page then reads whichever the browser happens to hand it;
 * - writes the **refresh** cookie first, so a teardown between the two writes leaves "old access + new
 *   refresh" — a state the site can repair itself — rather than the reverse;
 * - gives both cookies the refresh token's expiry, byte-for-byte as the site does;
 * - verifies by reading back and comparing decoded values, since "the write was accepted" and "the value is
 *   there now" are different claims when another writer exists.
 *
 * **Manifest requirements:** the `cookies` permission and a `host_permissions` entry for the site origin.
 * `otherActorLikelyActive` additionally needs `tabs`.
 */
class FetchMarketplaceCookieTokenStore(private val scrapeConfig: MarketplaceScrapeConfig = MarketplaceScrapeConfig()) :
    MarketplaceTokenStore {

    /**
     * Attributes of the cookie records last seen, so a write can mirror them even when the record has just
     * been deleted underneath us (which is exactly when restoring the pair matters most). Falls back to a
     * conservative template derived from the configured site URL.
     */
    private var template: CookieTemplate? = null

    override suspend fun read(): StoredMarketplaceTokens? = coroutineScope {
        // Concurrent: two independent extension-API round trips, and this runs at least once per cycle plus
        // once per marketplace request. The two WRITES in `write` stay strictly ordered — that ordering is
        // load-bearing — but nothing orders the reads.
        val accessAsync = async { readCookie(scrapeConfig.cookieName) }
        val refreshAsync = async { readCookie(scrapeConfig.refreshCookieName) }
        val access = accessAsync.await()
        val refresh = refreshAsync.await()
        (refresh ?: access)?.let { template = it.template }
        StoredMarketplaceTokens(
            accessToken = access?.value,
            refreshToken = refresh?.value,
            // The refresh cookie's own expiry IS truthful for the refresh token (unlike the access cookie's).
            refreshTokenExpiresAt = refresh?.expiresAtEpochSeconds?.let { Instant.fromEpochSeconds(it) },
        )
    }

    override suspend fun write(tokens: MarketplaceTokenPair): MarketplaceTokenStore.WriteOutcome {
        val attrs = template ?: defaultTemplate()
        // Both entries carry the REFRESH token's expiry — the site does the same, deliberately, so that a
        // user who does nothing for a day is not signed out by an expired access cookie.
        val expiry = tokens.refreshTokenExpiresAt?.epochSeconds?.toDouble()

        // Refresh first: a teardown between the two writes must not leave a fresh access token beside a
        // superseded refresh token.
        val wroteRefresh = setCookie(scrapeConfig.refreshCookieName, tokens.refreshToken, expiry, attrs)
        val wroteAccess = setCookie(scrapeConfig.cookieName, tokens.accessToken, expiry, attrs)
        if (!wroteRefresh || !wroteAccess) return MarketplaceTokenStore.WriteOutcome.BLIND

        // Verify: another writer may have overwritten us between the set and now. Concurrent for the same
        // reason as `read` — only the sets above need an order.
        val (backAccess, backRefresh) = coroutineScope {
            val a = async { readCookie(scrapeConfig.cookieName)?.value }
            val r = async { readCookie(scrapeConfig.refreshCookieName)?.value }
            a.await() to r.await()
        }
        if (backAccess == null || backRefresh == null) return MarketplaceTokenStore.WriteOutcome.BLIND
        return if (backAccess == tokens.accessToken && backRefresh == tokens.refreshToken) {
            MarketplaceTokenStore.WriteOutcome.WRITTEN
        } else {
            MarketplaceTokenStore.WriteOutcome.LOST_RACE
        }
    }

    /**
     * True when a tab on the marketplace host is open — its SPA refreshes the session itself, so rotating
     * behind it only invites a collision. Matched on any path and any subdomain, like the old refresher's
     * tab lookup. Requires the `tabs` permission; a failure reports `false` (never decline forever).
     */
    override suspend fun otherActorLikelyActive(): Boolean {
        val host = scrapeConfig.refreshUrl.substringAfter("://").substringBefore("/")
        if (host.isBlank()) return false
        return try {
            val api: dynamic = webExtApi()
            val details: dynamic = js("({})")
            details.url = arrayOf("*://$host/*", "*://*.$host/*")

            @Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")
            val tabs: dynamic = api.tabs.query(details).unsafeCast<Promise<dynamic>>().await()
            ((tabs?.length as? Int) ?: 0) > 0
        } catch (_: Throwable) {
            false
        }
    }

    // ---- private -----------------------------------------------------------------------------------

    private class CookieTemplate(val url: String, val domain: String?, val path: String, val secure: Boolean, val sameSite: String?)

    private class CookieRecord(val value: String, val expiresAtEpochSeconds: Long?, val template: CookieTemplate)

    private suspend fun readCookie(name: String): CookieRecord? = try {
        val api: dynamic = webExtApi()
        val details: dynamic = js("({})")
        details.url = scrapeConfig.refreshUrl
        details.name = name

        @Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")
        val cookie: dynamic = api.cookies.get(details).unsafeCast<Promise<dynamic>>().await()
        val raw = cookie?.value as? String
        if (raw.isNullOrBlank()) {
            null
        } else {
            val hostOnly = (cookie.hostOnly as? Boolean) ?: false
            CookieRecord(
                value = decodeOrSelf(raw),
                expiresAtEpochSeconds = (cookie.expirationDate as? Double)?.toLong(),
                template = CookieTemplate(
                    url = scrapeConfig.refreshUrl,
                    // A host-only record must be re-written host-only: passing `domain` would create a
                    // second, domain-scoped record with the same name.
                    domain = if (hostOnly) null else (cookie.domain as? String),
                    path = (cookie.path as? String) ?: "/",
                    secure = (cookie.secure as? Boolean) ?: true,
                    sameSite = cookie.sameSite as? String,
                ),
            )
        }
    } catch (_: Throwable) {
        null
    }

    private suspend fun setCookie(name: String, value: String, expiry: Double?, attrs: CookieTemplate): Boolean = try {
        val api: dynamic = webExtApi()
        val details: dynamic = js("({})")
        details.url = attrs.url
        details.name = name
        details.value = encodeURIComponent(value)
        details.path = attrs.path
        details.secure = attrs.secure
        attrs.domain?.let { details.domain = it }
        attrs.sameSite?.let { details.sameSite = it }
        expiry?.let { details.expirationDate = it }

        @Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")
        val written: dynamic = api.cookies.set(details).unsafeCast<Promise<dynamic>>().await()
        written != null
    } catch (_: Throwable) {
        false
    }

    /**
     * Conservative attributes for a record we have never seen: the configured site origin, root path, secure.
     * `domain` is deliberately omitted — a host-only cookie is the narrower of the two options, and guessing a
     * parent domain would risk creating a shadowing duplicate.
     */
    private fun defaultTemplate() = CookieTemplate(
        url = scrapeConfig.refreshUrl,
        domain = null,
        path = "/",
        secure = scrapeConfig.refreshUrl.startsWith("https://"),
        sameSite = null,
    )

    private fun decodeOrSelf(raw: String): String = try {
        decodeURIComponent(raw)
    } catch (_: Throwable) {
        // A value containing a stray `%` is not valid percent-encoding; the site's own reader has the same
        // fallback, so mirroring it keeps the two in agreement.
        raw
    }
}
