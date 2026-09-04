package com.dmarket.p2p.tracker.client

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.js.Js

/**
 * The browser fetch-based engine. When [credentialsInclude] is set, the underlying `window.fetch`
 * is configured with `credentials:"include"` (attaching the logged-in Steam cookie session) and
 * `cache:"no-store"` (Steam reads/writes must reflect live state, never a cached response). This
 * uses the JS-engine fetch-options API added in **Ktor 3.2.0**
 * ([io.ktor.client.engine.js.JsClientEngineConfig.configureRequest]) — the capability that lets the
 * Steam calls use Ktor instead of a raw-`window.fetch` bypass.
 *
 * **Consumer manifest requirement:** the extension must hold `host_permissions` (or granted
 * `optional_host_permissions`) for `https://api.steampowered.com`, `https://steamcommunity.com`,
 * `https://login.steampowered.com` and `https://store.steampowered.com`, plus a logged-in Steam
 * cookie session — otherwise the browser will not attach cookies to these cross-site requests and
 * Steam answers 403.
 */
actual fun platformHttpEngine(credentialsInclude: Boolean): HttpClientEngine = if (credentialsInclude) {
    Js.create {
        configureRequest {
            credentials = "include"
            cache = "no-store"
        }
    }
} else {
    Js.create()
}
