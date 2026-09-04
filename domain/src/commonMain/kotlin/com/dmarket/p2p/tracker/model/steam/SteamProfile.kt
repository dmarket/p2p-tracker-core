package com.dmarket.p2p.tracker.model.steam

import com.dmarket.p2p.tracker.model.SteamId

/**
 * A Steam user's public profile as surfaced by the Steam Web API: display identity ([nickname],
 * the three [avatar URLs][avatarSmallUrl]) plus the account's [level].
 *
 * Sourced from `ISteamUser/GetPlayerSummaries/v2` (nickname + avatars — always public) and
 * `IPlayerService/GetSteamLevel/v1` ([level]). A **private** profile still exposes its nickname and
 * avatars, but hides its level, so [level] is `null` for private (or level-less) profiles — that is
 * a normal outcome, not an error. Carries no credential, so it is safe to hand across the JS/mobile
 * export boundary.
 */
data class SteamProfile(
    val steamId64: SteamId,
    val nickname: String,
    /** `avatar` — 32×32. */
    val avatarSmallUrl: String,
    /** `avatarmedium` — 64×64. */
    val avatarMediumUrl: String,
    /** `avatarfull` — 184×184. */
    val avatarFullUrl: String,
    /** `player_level`, or `null` when the profile is private / hides its level. */
    val level: Int?,
)
