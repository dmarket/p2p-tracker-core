package com.dmarket.p2p.tracker.client.steam

import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.model.steam.SteamProfile
import com.dmarket.p2p.tracker.port.steam.SteamProfileReader
import com.dmarket.p2p.tracker.port.steam.UserNotFoundException
import com.dmarket.p2p.tracker.support.FakeClock
import com.dmarket.p2p.tracker.support.fakeSteamCredential
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class CachingSteamProfileReaderTest {

    private val credential: SteamCredential = fakeSteamCredential()
    private val ttl = 5.minutes

    private fun id(n: Int) = SteamId("7656119800000${1000 + n}")
    private fun profile(id: SteamId) = SteamProfile(id, "nick-${id.value.takeLast(4)}", "a", "m", "f", 1)

    /** Delegate that records call counts / requested ids and only "knows" [known]. */
    private class SpyReader(val known: Set<SteamId>, val make: (SteamId) -> SteamProfile) : SteamProfileReader {
        var singleCalls = 0
        var batchCalls = 0
        val requestedBatches = mutableListOf<List<SteamId>>()

        override suspend fun getUserProfile(credential: SteamCredential, steamId64: SteamId): SteamProfile {
            singleCalls++
            if (steamId64 !in known) throw UserNotFoundException(steamId64.value)
            return make(steamId64)
        }

        override suspend fun getUserProfiles(credential: SteamCredential, steamId64s: List<SteamId>): List<SteamProfile> {
            batchCalls++
            requestedBatches += steamId64s
            return steamId64s.filter { it in known }.map(make)
        }
    }

    @Test
    fun cache_miss_then_hit_calls_the_delegate_once() = runTest {
        val spy = SpyReader(setOf(id(1)), ::profile)
        val reader = CachingSteamProfileReader(spy, FakeClock(), ttl)

        val first = reader.getUserProfile(credential, id(1))
        val second = reader.getUserProfile(credential, id(1))

        assertEquals(first, second)
        assertEquals(1, spy.singleCalls, "second lookup should hit the cache")
    }

    @Test
    fun re_fetches_after_the_ttl_expires() = runTest {
        val spy = SpyReader(setOf(id(1)), ::profile)
        val clock = FakeClock()
        val reader = CachingSteamProfileReader(spy, clock, ttl)

        reader.getUserProfile(credential, id(1))
        clock.advance(ttl + 1.seconds)
        reader.getUserProfile(credential, id(1))

        assertEquals(2, spy.singleCalls, "an expired entry should be re-fetched")
    }

    @Test
    fun different_ids_do_not_collide() = runTest {
        val spy = SpyReader(setOf(id(1), id(2)), ::profile)
        val reader = CachingSteamProfileReader(spy, FakeClock(), ttl)

        val one = reader.getUserProfile(credential, id(1))
        val two = reader.getUserProfile(credential, id(2))

        assertEquals(id(1), one.steamId64)
        assertEquals(id(2), two.steamId64)
        assertEquals(2, spy.singleCalls)
    }

    @Test
    fun batch_fetches_only_the_missing_ids_and_preserves_order() = runTest {
        val spy = SpyReader(setOf(id(1), id(2), id(3)), ::profile)
        val reader = CachingSteamProfileReader(spy, FakeClock(), ttl)

        reader.getUserProfile(credential, id(2)) // warm the cache for id(2)
        val batch = reader.getUserProfiles(credential, listOf(id(1), id(2), id(3)))

        assertEquals(listOf(id(1), id(2), id(3)), batch.map { it.steamId64 }, "order = input order")
        assertEquals(1, spy.batchCalls)
        assertEquals(listOf(listOf(id(1), id(3))), spy.requestedBatches, "id(2) was cached, so only 1 & 3 fetched")
    }

    @Test
    fun unknown_id_is_not_cached_and_keeps_propagating() = runTest {
        val spy = SpyReader(emptySet(), ::profile)
        val reader = CachingSteamProfileReader(spy, FakeClock(), ttl)

        assertFailsWith<UserNotFoundException> { reader.getUserProfile(credential, id(9)) }
        assertFailsWith<UserNotFoundException> { reader.getUserProfile(credential, id(9)) }
        assertEquals(2, spy.singleCalls, "a not-found result must not be cached")
    }
}
