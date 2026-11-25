package com.dmarket.p2p.headless.proxy

import com.dmarket.p2p.tracker.net.STEAM_PROXY_UNREACHABLE_CODE
import com.dmarket.p2p.tracker.net.SteamProxy
import com.dmarket.p2p.tracker.runtime.SteamRoute
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.js.Js
import io.ktor.client.fetch.RequestInit
import org.w3c.fetch.Response
import kotlin.js.Promise

/**
 * The transport of one tracker's Steam calls, never its DMarket ones: [engine] sends each request through the proxy
 * [route] names when it is sent, or directly while it names none.
 *
 * Through a proxy, the request goes out on `undici`'s own `fetch` with an `Agent` whose every connection is a tunnel
 * through the proxy ([openSteamTunnel]), TLS to Steam inside it. Both come from the one `undici` release this package
 * pins: the `Agent` is not handed to Node's global `fetch`, whose bundled `undici` changes with the Node version (and
 * whose handler contract an `Agent` of another release can break), and which would ignore an option it did not know
 * — a request gone direct. A tunnel that cannot be opened fails the request with its error coded
 * `STEAM_PROXY_UNREACHABLE`, which the HTTP layer turns into a `SteamProxyUnreachableException`; nothing retries it
 * directly. Each tunnel opened, or not, is told to [route].
 *
 * `undici` is imported on the first call through a proxy, never when the package loads: importing it installs its own
 * `Agent` as Node's global dispatcher when none is set yet, which would move every `fetch` of the host's process — its
 * own, DMarket's — onto this package's `undici`. Whatever global dispatcher the import installs is put back
 * ([undiciModule]). An import that failed, or an `Agent` that could not be made, is not kept: the next request
 * tries again, and the proxy is not reported unreachable for it, since nothing was asked of the proxy.
 *
 * One `Agent` per transport, so per tracker: two trackers never share a connection, a pool or a proxy. When the host
 * pushes another proxy, or none, the old `Agent` is closed (its requests in flight let finish) before the next request
 * goes out. Without a proxy the request goes out on the global `fetch`, as before. [close] ends the `Agent` and its
 * sockets at once, and refuses every request after it.
 *
 * Every request is `cache: "no-store"` and `credentials: "include"`, the Steam engine's options on every platform.
 */
internal class SteamHttpTransport(private val route: SteamRoute, private val loadUndici: () -> Promise<dynamic> = ::undiciModule) {
    val engine: HttpClientEngine = Js.create {
        configureRequest {
            credentials = "include"
            cache = "no-store"
        }
        fetch = { input: String, init: RequestInit? -> send(input, init) }
    }

    // `{undici, agent}` for the current proxy, once `undici` has loaded: one promise, so a request dispatches in the
    // same reaction that hands it the `Agent`, ahead of a close a proxy change queues behind it. `dynamic`: no Kotlin
    // extension may be called on it.
    private var agent: Promise<dynamic>? = null
    private var agentProxy: SteamProxy? = null
    private var closed = false

    /** The tracker is stopping: its pooled tunnels go now, not at their keep-alive timeout. */
    fun close() {
        closed = true
        retire(destroy = true)
    }

    private fun send(input: String, init: RequestInit?): Promise<Response> {
        if (closed) return Promise.reject(IllegalStateException("the tracker is stopped"))
        val proxy = route.proxy
        if (proxy != agentProxy) {
            retire(destroy = false)
            agent = proxy?.let(::tunnellingAgentFor)
            agentProxy = proxy
        }
        if (proxy == null) return globalFetch(input, init)
        val loaded = checkNotNull(agent) { "an agent is made with its proxy" }
        return loaded.then(
            { tunnelling: dynamic ->
                val options: dynamic = js("Object").assign(js("Object").create(null), init)
                options.dispatcher = tunnelling.agent
                tunnelling.undici.fetch(input, options).unsafeCast<Promise<Response>>()
            },
            // `undici` did not load or the `Agent` could not be made: nothing was sent, and nothing goes direct. The
            // proxy was not asked, so the route hears nothing; the next request loads again.
            { _: Throwable ->
                if (agent === loaded) {
                    agent = null
                    agentProxy = null
                }
                Promise.reject(notSent(CLIENT_NOT_LOADED))
            },
        ).unsafeCast<Promise<Response>>()
    }

    private fun tunnellingAgentFor(proxy: SteamProxy): Promise<dynamic> = loadUndici().then { undici: dynamic ->
        val pair: dynamic = js("({})")
        pair.undici = undici
        pair.agent = tunnellingAgent(
            undici,
            { host: String, port: Int -> openSteamTunnel(proxy, host, port) },
            { route.tunnelled(proxy) },
            { detail: String -> route.unreachable(proxy, detail) },
        )
        pair
    }

    private fun retire(destroy: Boolean) {
        val old = agent
        agent = null
        agentProxy = null
        old?.then(
            { retired: dynamic -> (if (destroy) retired.agent.destroy() else retired.agent.close()).catch { _: dynamic -> } },
            { _: Throwable -> },
        )
    }

    private companion object {
        const val CLIENT_NOT_LOADED = "the proxy client could not be loaded"
    }
}

/**
 * The rejection of a request through the proxy that was not sent, shaped as Node's `fetch` shapes a failed one — a
 * `TypeError: fetch failed` with the reason as its `cause` — and coded `STEAM_PROXY_UNREACHABLE`, so the HTTP layer
 * reads it as the proxy's, never as Steam not answering.
 */
private fun notSent(detail: String): Throwable = js(
    "(function (detail, code) { const cause = new Error(detail); cause.code = code; return new TypeError('fetch failed', { cause }); })",
)(detail, STEAM_PROXY_UNREACHABLE_CODE).unsafeCast<Throwable>()

/**
 * The `undici` module, imported once, on first use. Its import installs an `Agent` of its own as Node's global
 * dispatcher (under both of `undici`'s global symbols) when none is set yet; such an `Agent` is put back to what was
 * there before, so Node's own `fetch` sets up its own dispatcher as it would have. A dispatcher Node set meanwhile is
 * left alone. A failed import is not kept: the next call imports again.
 */
internal fun undiciModule(): Promise<dynamic> = importUndiciOnce()

private val importUndiciOnce: () -> Promise<dynamic> = undiciLoader { js("import('undici')").unsafeCast<Promise<dynamic>>() }

/** [undiciModule] over [importModule], which a test can make fail. */
internal fun undiciLoader(importModule: () -> Promise<dynamic>): () -> Promise<dynamic> = js(
    """
    (function (importModule) {
      let loading = null;
      return function () {
        if (loading === null) {
          const keys = [Symbol.for('undici.globalDispatcher.1'), Symbol.for('undici.globalDispatcher.2')];
          const before = keys.map((key) => globalThis[key]);
          const attempt = importModule().then((module) => {
            const undici = module.default && module.default.Agent ? module.default : module;
            keys.forEach((key, i) => {
              const now = globalThis[key];
              if (now !== before[i] && now instanceof undici.Dispatcher) globalThis[key] = before[i];
            });
            return undici;
          });
          loading = attempt;
          attempt.catch(() => {
            if (loading === attempt) loading = null;
          });
        }
        return loading;
      };
    })
    """,
)(importModule).unsafeCast<() -> Promise<dynamic>>()

/** The Steam engine of [SteamHttpTransport], for a caller that never stops it. */
internal fun steamHttpEngine(route: SteamRoute): HttpClientEngine = SteamHttpTransport(route).engine

private fun globalFetch(input: String, init: RequestInit?): Promise<Response> =
    js("globalThis.fetch")(input, init).unsafeCast<Promise<Response>>()

/**
 * An `undici` `Agent` whose connector opens a tunnel ([open]) for every connection and, for `https:`, runs `undici`'s
 * own TLS connector over it (what its `ProxyAgent` does, with the tunnel's failure coded and worded by [open]).
 */
private val tunnellingAgent: dynamic = js(
    """
    (function (undici, open, onTunnel, onFailure) {
      const tls = undici.buildConnector({});
      return new undici.Agent({
        connect(opts, callback) {
          const https = opts.protocol === 'https:';
          const port = Number(opts.port) || (https ? 443 : 80);
          open(opts.hostname, port).then((socket) => {
            try {
              onTunnel();
              if (!https) {
                socket.resume();
                callback(null, socket);
                return;
              }
              tls({ ...opts, port, httpSocket: socket }, callback);
            } catch (error) {
              socket.destroy();
              callback(error, null);
            }
          }, (error) => {
            onFailure(error.message);
            callback(error, null);
          });
        },
      });
    })
    """,
)
