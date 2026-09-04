package com.dmarket.p2p.tracker.client.steam

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTOs for the Steam user-profile endpoints used by [KtorSteamProfileReader]:
 * `ISteamUser/GetPlayerSummaries/v2` and `IPlayerService/GetSteamLevel/v1`.
 *
 * Both wrap their body in `{ "response": { … } }` (like `IEconService`, see [SteamDtos]). For a
 * private profile `GetSteamLevel` returns `{ "response": {} }` — [PlayerLevelResponse.playerLevel]
 * is therefore nullable and maps to `SteamProfile.level = null`. Nickname and avatars from
 * `GetPlayerSummaries` are always public.
 */

// ---- GetPlayerSummaries -------------------------------------------------------------------------

@Serializable
data class GetPlayerSummariesWrapper(@SerialName("response") val response: GetPlayerSummariesResponse? = null)

@Serializable
data class GetPlayerSummariesResponse(@SerialName("players") val players: List<PlayerSummaryDto> = emptyList())

@Serializable
data class PlayerSummaryDto(
    @SerialName("steamid") val steamId: String,
    @SerialName("personaname") val personaName: String = "",
    @SerialName("avatar") val avatar: String = "",
    @SerialName("avatarmedium") val avatarMedium: String = "",
    @SerialName("avatarfull") val avatarFull: String = "",
)

// ---- GetSteamLevel ------------------------------------------------------------------------------

@Serializable
data class GetSteamLevelWrapper(@SerialName("response") val response: PlayerLevelResponse? = null)

/** `{ "response": { "player_level": N } }`; empty `response` (null [playerLevel]) for a private profile. */
@Serializable
data class PlayerLevelResponse(@SerialName("player_level") val playerLevel: Int? = null)
