package com.dmarket.p2p.tracker.client.steam

import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.wire.trackerJson
import kotlinx.serialization.json.Json

/**
 * Pure parsing of the Steam user-profile response bodies, shared by [KtorSteamProfileReader] on
 * every platform (the [SteamReadResponses] pattern). Keeps the wire→domain mapping in one tested,
 * platform-agnostic place; the only per-platform difference is how the bytes are fetched.
 */
internal object SteamProfileResponses {

    val json: Json = trackerJson { ignoreUnknownKeys = true }

    /** `GetPlayerSummaries`: each returned player keyed by its [SteamId] (Steam omits unknown ids). */
    fun players(body: String): Map<SteamId, PlayerSummaryDto> {
        val players = json.decodeFromString<GetPlayerSummariesWrapper>(body).response?.players ?: return emptyMap()
        return players
            .filter { it.steamId.isNotBlank() }
            .associateBy { SteamId(it.steamId) }
    }

    /** `GetPlayerLinkDetails`: persona names keyed by [SteamId]; unknown ids and blank names are left out. */
    fun personaNames(body: String): Map<SteamId, String> =
        json.decodeFromString<GetPlayerLinkDetailsWrapper>(body).response?.accounts.orEmpty()
            .mapNotNull { it.publicData }
            .filter { it.steamId.isNotBlank() && it.personaName.isNotBlank() }
            .associate { SteamId(it.steamId) to it.personaName }

    /** `GetSteamLevel`: the `player_level`, or `null` for a private / level-less profile. */
    fun level(body: String): Int? = json.decodeFromString<GetSteamLevelWrapper>(body).response?.playerLevel
}
