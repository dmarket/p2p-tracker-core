package com.dmarket.p2p.tracker.client.steam

import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.model.steam.SteamProfile
import com.dmarket.p2p.tracker.port.host.Clock
import com.dmarket.p2p.tracker.port.steam.SteamProfileReader
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * [SteamProfileReader] decorator adding an in-memory, per-[SteamId] TTL cache to cut Steam Web API
 * quota use (the 100k-calls/day limit). Profiles are cached for [ttl] and re-fetched once expired,
 * with expiry judged against the injected [Clock] (so tests advance a `FakeClock` instead of waiting).
 *
 * The [Mutex] guards only the map — not the delegate network call — so concurrent fetches for
 * *different* ids run in parallel; the trade-off is that two concurrent misses for the *same* id may
 * both fetch (a benign duplicate, not a correctness issue). Follows the
 * [MarketplaceCredentialProvider][com.dmarket.p2p.tracker.credential.marketplace.MarketplaceCredentialProvider]
 * cache shape. Unknown ids are never cached: [getUserProfile] lets [UserNotFoundException] propagate
 * and [getUserProfiles] simply omits them, so a later lookup re-checks Steam.
 */
class CachingSteamProfileReader(private val delegate: SteamProfileReader, private val clock: Clock, private val ttl: Duration) :
    SteamProfileReader {

    private data class Entry(val profile: SteamProfile, val expiresAt: Instant)

    private val mutex = Mutex()
    private val cache = mutableMapOf<SteamId, Entry>()

    override suspend fun getUserProfile(credential: SteamCredential, steamId64: SteamId): SteamProfile {
        cached(steamId64)?.let { return it }
        val profile = delegate.getUserProfile(credential, steamId64)
        store(listOf(profile))
        return profile
    }

    override suspend fun getUserProfiles(credential: SteamCredential, steamId64s: List<SteamId>): List<SteamProfile> {
        val orderedIds = steamId64s.distinct()

        val hits = mutableMapOf<SteamId, SteamProfile>()
        val misses = mutableListOf<SteamId>()
        val now = clock.now()
        mutex.withLock {
            for (id in orderedIds) {
                val entry = cache[id]
                if (entry != null && now < entry.expiresAt) hits[id] = entry.profile else misses += id
            }
        }

        val fetched = if (misses.isEmpty()) emptyList() else delegate.getUserProfiles(credential, misses)
        store(fetched)

        val byId = hits + fetched.associateBy { it.steamId64 }
        return orderedIds.mapNotNull { byId[it] }
    }

    // ---- private -----------------------------------------------------------------------------------

    private suspend fun cached(id: SteamId): SteamProfile? = mutex.withLock {
        cache[id]?.takeIf { clock.now() < it.expiresAt }?.profile
    }

    private suspend fun store(profiles: List<SteamProfile>) {
        if (profiles.isEmpty()) return
        val expiresAt = clock.now() + ttl
        mutex.withLock { profiles.forEach { cache[it.steamId64] = Entry(it, expiresAt) } }
    }
}
