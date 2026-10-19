package com.dmarket.p2p.tracker.credential.steam

import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.model.steam.SteamSessionCookie
import com.dmarket.p2p.tracker.port.notary.SteamProofCookieSource
import com.dmarket.p2p.tracker.port.notary.SteamProofSession

/**
 * The Steam web session a cookie-authenticated proven read sends, built from an access token rather than read
 * from a browser's jar: `steamLoginSecure` from [credential], `sessionid` from [sessionId] — exactly what the
 * tracker's header-carrying Steam writes send. `null` while no live credential is held, which fails a
 * cookie-authenticated read before any MPC starts.
 *
 * For a host with no browser: the tracker's own [PushedSteamSession] ([PushedSteamSession.proofCookies]), or a
 * prover process that was handed the token with its proof.
 */
class HeaderSteamProofCookieSource(private val credential: () -> SteamCredential?, private val sessionId: String) : SteamProofCookieSource {
    override suspend fun currentSession(): SteamProofSession? = credential()?.let {
        val loginSecure = SteamSessionCookie(it.subjectSteamId, it.token, it.expiresAt).cookieValue()
        SteamProofSession(cookieHeader = "steamLoginSecure=$loginSecure; sessionid=$sessionId", sessionId = sessionId)
    }

    override fun toString(): String = "HeaderSteamProofCookieSource(sessionId=<redacted>)"
}
