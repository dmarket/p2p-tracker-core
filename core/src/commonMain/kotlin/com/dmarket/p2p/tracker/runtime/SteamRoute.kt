package com.dmarket.p2p.tracker.runtime

import com.dmarket.p2p.tracker.net.SteamProxy
import com.dmarket.p2p.tracker.port.host.Clock
import kotlin.time.Instant

/**
 * Where one tracker's Steam traffic goes, and whether it gets there: the proxy the host pushed with the Steam token
 * ([proxy]), which the tracker's Steam transports read at each connection they open, and what their last attempt
 * through it found ([tunnelled], [unreachable]).
 *
 * One per tracker, never shared: two trackers in one process each route through their own proxy.
 *
 * A report about a proxy other than the current one — a connection opened before the host pushed another — is
 * ignored, and pushing another proxy (or none) ends the problem with the old one: [isUnreachable] is about the
 * current proxy only.
 *
 * While [proxyRequired] there is no direct route: a transport or a prover that finds no proxy held sends nothing
 * ([isProxyMissing]). It is asked at each connection, since it turns on as the tracker runs: [required] is the host's
 * own `requireSteamProxy` or the session's pin, set by the first proxy it held.
 *
 * Holds the proxy as the session holds the token; [toString] says nothing of it.
 */
class SteamRoute(private val clock: Clock, private val required: () -> Boolean = { false }, private val current: () -> SteamProxy?) {
    /** A proxy no connection got through: what the last attempt said, and since when none has. */
    private class Outage(val proxy: SteamProxy, val detail: String, val since: Instant)

    // No @Volatile: JS is single-threaded; JVM callers share one scope, as for the session.
    private var outage: Outage? = null
    private val moveListeners = mutableListOf<() -> Unit>()

    /**
     * The proxy every Steam connection must go through now; `null` to connect directly, or, with [proxyRequired], not
     * to connect at all.
     */
    val proxy: SteamProxy? get() = current()

    /** Whether no Steam connection may go out directly now: every one goes through a proxy, or is not made. */
    val proxyRequired: Boolean get() = required()

    /** Whether a proxy is required and none is held: nothing reaches Steam until one is. */
    val isProxyMissing: Boolean get() = proxyRequired && current() == null

    /**
     * Calls [listener] each time a push moves the account to another proxy (or none, where that is allowed): a
     * transport ends what it has in flight on the old route, so nothing opened before a first proxy goes on directly
     * after it.
     */
    fun onProxyMoved(listener: () -> Unit) {
        moveListeners += listener
    }

    /** A push moved the account to another proxy: told to every [onProxyMoved] listener, at once. */
    fun proxyMoved() {
        moveListeners.forEach { runCatching { it() } }
    }

    /** Whether the last attempt through the current proxy failed. */
    val isUnreachable: Boolean get() = currentOutage() != null

    /** What the last failed attempt said, while [isUnreachable]: fixed words and a code, no address. */
    val detail: String? get() = currentOutage()?.detail

    /** Since when the current proxy has been unreachable, while it is. */
    val unreachableSince: Instant? get() = currentOutage()?.since

    /** A connection went through [through]: it is reachable. */
    fun tunnelled(through: SteamProxy) {
        if (through == current()) outage = null
    }

    /** No connection went through [through], for [detail] (fixed words and a code; never the address). */
    fun unreachable(through: SteamProxy, detail: String) {
        if (through != current()) return
        outage = Outage(through, detail, currentOutage()?.since ?: clock.now())
    }

    /** The outage of the proxy in use; one of a proxy since replaced, or dropped, is over. */
    private fun currentOutage(): Outage? = outage?.takeIf { it.proxy == current() }

    override fun toString(): String =
        "SteamRoute(proxy=${proxy?.let { "<redacted>" }}, required=$proxyRequired, unreachable=$isUnreachable, missing=$isProxyMissing)"
}
