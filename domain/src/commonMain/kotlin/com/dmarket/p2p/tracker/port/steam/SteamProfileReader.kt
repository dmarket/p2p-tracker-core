package com.dmarket.p2p.tracker.port.steam

import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.model.steam.SteamProfile

/**
 * Read-only access to Steam users' **public profile** info — nickname, avatars, and account level —
 * via the Steam Web API (`ISteamUser/GetPlayerSummaries` + `IPlayerService/GetSteamLevel`).
 *
 * Like [SteamReadClient], every call is authorised by the device-local [SteamCredential] (the
 * scraped web-session `access_token`), and this port only ever *reads*. It never accepts or returns
 * a marketplace credential, keeping the Steam↔DMarket audit boundary intact.
 *
 * Not yet confirmed against live Steam that these two endpoints accept the web-session token as
 * `access_token` (rather than a classic `key=` Web API key) — the shapes here are unit-tested but
 * pending real-world verification, matching the convention on the other Steam read clients.
 */
interface SteamProfileReader {

    /**
     * The [SteamProfile] for a single [steamId64]. Throws [UserNotFoundException] if Steam does not
     * know the id (empty `players`), [InvalidSteamId64Exception] if [steamId64] is malformed. A
     * private profile is returned normally with [SteamProfile.level] `= null`.
     */
    suspend fun getUserProfile(credential: SteamCredential, steamId64: SteamId): SteamProfile

    /**
     * Profiles for a batch of ids. Summaries are fetched in batched requests (≤100 ids each) and
     * levels in parallel (concurrency-limited). Ids Steam does not know are **omitted** from the
     * result (no exception — unlike [getUserProfile]); the result preserves input order and is
     * deduped. Throws [InvalidSteamId64Exception] if any id is malformed (validated before any call).
     */
    suspend fun getUserProfiles(credential: SteamCredential, steamId64s: List<SteamId>): List<SteamProfile>
}

/**
 * Thrown by [SteamProfileReader.getUserProfile] when Steam returns no player for the requested id
 * (an empty `players` array) — the id is unknown / nonexistent.
 */
class UserNotFoundException(steamId64: String) : NoSuchElementException("No Steam user found for steamID64 '$steamId64'")

/**
 * Thrown when Steam rejects the profile request with HTTP 403 — the `access_token` is invalid,
 * rotated, or lacks access. (Steam's Web API returns 403, not 401, for a bad token.)
 */
class SteamProfileAuthException(cause: Throwable? = null) :
    IllegalStateException("Steam rejected the profile request (403) — access_token invalid or rotated", cause)
