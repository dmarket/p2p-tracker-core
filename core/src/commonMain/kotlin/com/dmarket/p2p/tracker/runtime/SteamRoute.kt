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
 * Holds the proxy as the session holds the token; [toString] says nothing of it.
 */
class SteamRoute(private val clock: Clock, private val current: () -> SteamProxy?) {
    /** A proxy no connection got through: what the last attempt said, and since when none has. */
    private class Outage(val proxy: SteamProxy, val detail: String, val since: Instant)

    // No @Volatile: JS is single-threaded; JVM callers share one scope, as for the session.
    private var outage: Outage? = null

    /** The proxy every Steam connection must go through now; `null` to connect directly. */
    val proxy: SteamProxy? get() = current()

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

    override fun toString(): String = "SteamRoute(proxy=${proxy?.let { "<redacted>" }}, unreachable=$isUnreachable)"
}
