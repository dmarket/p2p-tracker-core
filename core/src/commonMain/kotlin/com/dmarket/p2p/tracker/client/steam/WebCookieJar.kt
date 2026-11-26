package com.dmarket.p2p.tracker.client.steam

import com.dmarket.p2p.tracker.port.host.SteamCookieStore
import com.dmarket.p2p.tracker.port.host.SteamCookieStoreException
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The mobile Steam client's cookies, read from and written to the core's [SteamCookieStore] (the sealed vault, or the
 * platform's WebView store): the `Cookie` header of every request comes from the store, and every `Set-Cookie` in a
 * response goes back into it as raw lines. Which cookie reaches which URL is the store's to decide.
 *
 * Suspend all the way (the store is), so it never blocks a thread. A store that cannot be read throws, and so does
 * every call here that reads it: a failed read is never "no session". Values never reach a log or a `toString`.
 */
class WebCookieJar(private val store: SteamCookieStore) {
    private val mutex = Mutex()

    /** The `Cookie` header for [url], or `null` when the store sends none. */
    suspend fun cookieHeader(url: Url): String? = store.cookieHeader(url.toString())

    /** The value of the cookie [name] the store sends to [url], or `null`. */
    suspend fun value(url: Url, name: String): String? = store.value(url.toString(), name)

    /** Stores one cookie the core makes itself, host-only on [url]'s host ([SteamCookieStore.set]). */
    suspend fun set(url: Url, name: String, value: String, expiresAtEpochSeconds: Long?, httpOnly: Boolean) = mutex.withLock {
        store.set(url.toString(), name, value, expiresAtEpochSeconds, httpOnly)
    }

    /**
     * The value of the cookie [name] this jar sends to [url]; when there is none, [create]'s value, stored first for
     * [url]'s host at path `/` as a session cookie. Atomic against every other write of this jar, so two callers never
     * store two different values.
     */
    suspend fun valueOrPut(url: Url, name: String, create: () -> String): String = mutex.withLock {
        store.value(url.toString(), name)
            ?: create().also { value -> store.set(url.toString(), name, value, expiresAtEpochSeconds = null, httpOnly = false) }
    }

    /** Writes the cookies of [setCookieHeaders] (a response to [url]) into the store ([SteamCookieStore.store]). */
    suspend fun save(url: Url, setCookieHeaders: List<String>) {
        if (setCookieHeaders.isEmpty()) return
        mutex.withLock { store.store(url.toString(), setCookieHeaders) }
    }
}

/**
 * Sends the [WebCookieJar]'s cookies with every request and saves every `Set-Cookie` back into it. Installed on the
 * mobile Steam client in place of the engine's own cookie store, which stays off: cookies live in the core's store
 * only. Unlike Ktor's `HttpCookies` it logs nothing about cookies at any level.
 */
internal fun webCookiesPlugin(jar: WebCookieJar) = createClientPlugin("SteamWebCookies") {
    onRequest { request, _ ->
        val header = jar.cookieHeader(Url(request.url.buildString()))
        if (header == null) request.headers.remove(HttpHeaders.Cookie) else request.headers[HttpHeaders.Cookie] = header
    }
    onResponse { response ->
        try {
            jar.save(response.call.request.url, response.headers.getAll(HttpHeaders.SetCookie).orEmpty())
        } catch (_: SteamCookieStoreException) {
            // Steam has answered and its answer stands: a create it made must not read as one that never left (the
            // offer writers take this exception as "not sent"), nor as a failure. The cookies are lost; a refresh
            // verifies its own by reading the store back.
        }
    }
}
