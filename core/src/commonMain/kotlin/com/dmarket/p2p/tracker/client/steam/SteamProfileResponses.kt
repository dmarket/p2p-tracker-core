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

    /** `GetSteamLevel`: the `player_level`, or `null` for a private / level-less profile. */
    fun level(body: String): Int? = json.decodeFromString<GetSteamLevelWrapper>(body).response?.playerLevel
}
