package com.dmarket.p2p.tracker.port.host

import com.dmarket.p2p.tracker.model.steam.SteamCookie

/**
 * Device-local secure storage for the Steam web session's cookies on mobile: after the import that follows a Steam
 * sign-in in the app, the only source of Steam cookies. Every Steam request takes its `Cookie` header from here and
 * every `Set-Cookie` Steam answers with is written back, so a refreshed `steamLoginSecure` (or a rotated
 * `steamRefresh_steam`) lives in one place.
 *
 * A port of its own rather than more methods on [CredentialVault]: the web keeps its cookies in the browser and has
 * no use for this, so its vault stays as it is. Same security contract as [CredentialVault]: encrypted at rest with
 * OS-backed key material, never transmitted to DMarket.
 */
interface SteamCookieVault {
    /** Every stored cookie; empty when there is no session. */
    suspend fun readSteamCookies(): List<SteamCookie>

    /** Replaces every stored cookie with [cookies]. */
    suspend fun writeSteamCookies(cookies: List<SteamCookie>)

    /** Erases every stored cookie. */
    suspend fun clearSteamCookies()
}
