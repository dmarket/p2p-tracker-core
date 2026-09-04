package com.dmarket.p2p.tracker.adapter.steam

import com.dmarket.p2p.tracker.port.SessionRefreshOutcome
import com.dmarket.p2p.tracker.port.steam.SteamSessionRefresher

/**
 * The default [SteamSessionRefresher]: does nothing and reports [SessionRefreshOutcome.NOT_NEEDED].
 *
 * Keeps platforms without a concrete actual compiling (mobile is deferred) and is the right default
 * for hosts whose session does not need client-side keep-alive. The web path wires
 * `FetchSteamSessionRefresher`.
 */
object NoOpSteamSessionRefresher : SteamSessionRefresher {
    override suspend fun refreshSession(force: Boolean): SessionRefreshOutcome = SessionRefreshOutcome.NOT_NEEDED
}
