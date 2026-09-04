package com.dmarket.p2p.tracker.client

import io.ktor.client.engine.HttpClientEngine

/**
 * Returns the default [HttpClientEngine] for the current platform (OkHttp on JVM, Js/fetch on JS).
 *
 * @param credentialsInclude when `true`, the browser (JS) engine attaches the logged-in cookie
 *   session to cross-site requests (`fetch` `credentials:"include"`) — required for Steam's
 *   `IEconService`/community reads, which bind the web `access_token` to that session. Ignored on
 *   platforms whose HTTP client owns its own cookie jar (JVM/Android OkHttp).
 */
expect fun platformHttpEngine(credentialsInclude: Boolean = false): HttpClientEngine
