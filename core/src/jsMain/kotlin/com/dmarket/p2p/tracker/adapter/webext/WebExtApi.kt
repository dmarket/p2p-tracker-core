package com.dmarket.p2p.tracker.adapter.webext

import kotlinx.coroutines.await
import kotlin.js.Promise

/**
 * Returns the WebExtension API namespace, preferring `browser` (Firefox) over `chrome`.
 *
 * Both browsers' async APIs are used here in **promise** form (`await`ed by callers). On Firefox only
 * `browser.*` is guaranteed promise-based — `chrome.*` is the Chrome-compat callback alias — so we
 * prefer `browser` when it exists. On Chrome there is no `browser` global, so we fall back to
 * `chrome` (which is promise-based under MV3). The `browser.runtime` guard avoids selecting a stray
 * unrelated global named `browser`.
 *
 * Node test environments define neither global; tests stub `globalThis.chrome`, and the absence of
 * `browser` means this helper resolves to the stubbed `chrome` — so existing stubs stay valid.
 */
internal fun webExtApi(): dynamic = js("(typeof browser !== 'undefined' && browser.runtime ? browser : chrome)")

/**
 * One cookie's value from the extension's jar for [url], or `null` when absent, blank, or unreadable.
 *
 * Lives beside [webExtApi] because it is the same call every Steam-facing class here needs and the
 * `browser`-vs-`chrome` caveat above is the whole reason it is not a one-liner. It was open-coded in four
 * places (both write actuals, the session gateway, and the notary's cookie source), each carrying its own copy
 * of that reasoning — so a change to it had four homes.
 *
 * Value only: a caller needing the expiry (the session refresher's self-gate) reads the cookie object itself
 * through `SteamWebSessionGateway.readCookie`, which returns a `WebCookie`.
 */
internal suspend fun webExtCookieValue(url: String, name: String): String? = runCatching {
    val details: dynamic = js("({})")
    details.url = url
    details.name = name

    @Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")
    val cookie: dynamic = webExtApi().cookies.get(details).unsafeCast<Promise<dynamic>>().await()
    (cookie?.value as? String)?.takeIf { it.isNotBlank() }
}.getOrNull()
