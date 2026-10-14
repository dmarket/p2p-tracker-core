package com.dmarket.p2p.tracker.credential.steam

import com.dmarket.p2p.tracker.config.TrackerConfig
import com.dmarket.p2p.tracker.loop.LoopConfig
import com.dmarket.p2p.tracker.loop.TradeTrackerLoop
import com.dmarket.p2p.tracker.model.LifecycleEvent
import com.dmarket.p2p.tracker.model.RuntimeSurface
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.TrackerMode
import com.dmarket.p2p.tracker.model.marketplace.HeartbeatResponse
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.port.host.EventObserver
import com.dmarket.p2p.tracker.support.FakeClock
import com.dmarket.p2p.tracker.support.FakeDeviceIdStore
import com.dmarket.p2p.tracker.support.FakeMarketplaceClient
import com.dmarket.p2p.tracker.support.FakeScheduler
import com.dmarket.p2p.tracker.support.FakeSteamReadClient
import com.dmarket.p2p.tracker.support.steamAccessToken
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class PushedSteamSessionTest {
    private val clock = FakeClock()
    private var linked: SteamId? = null
    private val session = PushedSteamSession(clock) { linked }

    private fun token(sub: String = MINE, lifetimeHours: Int = 24): String = steamAccessToken(sub, clock.now() + lifetimeHours.hours)

    private suspend fun held(token: String): SteamCredential = assertIs<SteamPushResult.Held>(session.push(token)).credential

    // ---- holding a token ---------------------------------------------------------------------------

    @Test
    fun the_first_token_is_held_on_trust_before_any_account_is_named() = runTest {
        val credential = held(token())

        assertEquals(SteamId(MINE), credential.subjectSteamId)
        assertEquals(credential, session.credential)
        assertTrue(session.ready())
    }

    @Test
    fun a_token_the_library_does_not_take_is_refused_without_quoting_it_and_the_held_one_stays() = runTest {
        val mine = held(token())
        val refresh = steamAccessToken(MINE, clock.now() + 24.hours, aud = listOf("web", "renew"))

        val refused = assertIs<SteamPushResult.Invalid>(session.push(refresh))
        assertEquals("a refresh token is never accepted", refused.reason)
        assertTrue(refresh.substringAfter('.') !in refused.toString(), "$refused")
        assertEquals(mine, session.credential)
    }

    @Test
    fun a_token_of_another_account_is_refused_and_never_evicts_the_held_one() = runTest {
        val mine = held(token())
        session.heartbeatReturned()

        // Named by DMarket…
        linked = SteamId(MINE)
        assertEquals(SteamPushResult.WrongAccount(SteamId(THEIRS), SteamId(MINE)), session.push(token(THEIRS)))
        // …or not yet: the held token's account stands until DMarket says otherwise.
        linked = null
        assertEquals(SteamPushResult.WrongAccount(SteamId(THEIRS), null), session.push(token(THEIRS)))
        assertEquals(mine, session.credential)
    }

    @Test
    fun the_account_dmarket_names_wins_over_a_held_token_of_another() = runTest {
        held(token(THEIRS))
        linked = SteamId(MINE)
        session.heartbeatReturned()

        val right = held(token())
        assertEquals(right, session.credential)
    }

    @Test
    fun the_linked_account_counts_only_once_a_heartbeat_for_the_current_dmarket_account_came_back() = runTest {
        // The loop names an account, but no heartbeat for this DMarket account has come back yet: on trust.
        linked = SteamId(THEIRS)
        held(token())

        session.heartbeatReturned()
        assertEquals(SteamPushResult.WrongAccount(SteamId(MINE), SteamId(THEIRS)), session.push(token()))
    }

    @Test
    fun a_new_dmarket_account_does_not_inherit_the_previous_accounts_linked_id() = runTest {
        linked = SteamId(MINE)
        session.heartbeatReturned()
        val mine = held(token())

        // The key changed; the loop still remembers MINE until the new account's first heartbeat is back.
        session.forgetLinkedAccount()
        linked = SteamId(MINE)
        assertEquals(
            SteamPushResult.WrongAccount(SteamId(THEIRS), null),
            session.push(token(THEIRS)),
            "until the new account names one, the held token's account stands",
        )
        assertEquals(mine, session.credential)

        // The new account's heartbeat names THEIRS: now their token replaces the held one.
        linked = SteamId(THEIRS)
        session.heartbeatReturned()
        assertEquals(SteamId(THEIRS), held(token(THEIRS)).subjectSteamId)
    }

    @Test
    fun an_expired_token_is_still_reported_but_no_longer_handed_to_the_loop() = runTest {
        val provider = SteamCredentialProvider(session.vault, session.scraper, clock)
        held(token(lifetimeHours = 1))
        clock.advance(2.hours)

        assertFalse(session.ready())
        assertTrue(session.credential != null, "the host still sees that a token is held, and when it expired")
        assertNull(session.scraper.scrape())
        assertNull(provider.current())
        assertTrue(provider.lastRefreshFailedLoggedOut, "the loop asks for a fresh push")
    }

    @Test
    fun the_loops_cache_cannot_write_or_clear_what_is_held() = runTest {
        val mine = held(token())
        session.vault.clearSteamCredential()
        session.vault.writeSteamCredential(SteamCredential("other", SteamId(THEIRS), clock.now() + 1.hours))

        assertEquals(mine, session.vault.readSteamCredential())
    }

    // ---- what the loop sees --------------------------------------------------------------------------

    @Test
    fun a_pushed_token_reaches_the_credential_provider_at_once() = runTest {
        val provider = SteamCredentialProvider(session.vault, session.scraper, clock)
        held(token(lifetimeHours = 24))
        assertEquals(clock.now() + 24.hours, provider.current()?.expiresAt)

        // The cached token has a day left; the provider must not wait for it to go stale.
        val renewed = held(token(lifetimeHours = 30))
        assertEquals(renewed, provider.current())
    }

    @Test
    fun the_loop_acts_on_a_pushed_token_for_the_newly_linked_account_on_its_next_cycle() = runTest {
        val marketplace = FakeMarketplaceClient(HeartbeatResponse(ttlSeconds = 60, linkedSteamId = SteamId(MINE)))
        lateinit var loop: TradeTrackerLoop
        val steam = PushedSteamSession(clock) { loop.linkedSteamId }
        val mismatches = mutableListOf<LifecycleEvent.LinkedSteamIdMismatch>()
        loop = TradeTrackerLoop(
            config = LoopConfig(
                "1.0.0",
                RuntimeSurface.Headless,
                TrackerMode.Background,
                TrackerConfig.defaults(),
                requireLinkedSteamId = true,
            ),
            marketplace = marketplace,
            steamReader = FakeSteamReadClient(),
            credentials = SteamCredentialProvider(steam.vault, steam.scraper, clock),
            scheduler = FakeScheduler(),
            clock = clock,
            deviceId = FakeDeviceIdStore(),
            eventObserver = EventObserver { event ->
                if (event is LifecycleEvent.HeartbeatSent) steam.heartbeatReturned()
                if (event is LifecycleEvent.LinkedSteamIdMismatch) mismatches += event
            },
        )
        assertIs<SteamPushResult.Held>(steam.push(token()))
        loop.runOnce()
        assertEquals(SteamId(MINE), marketplace.heartbeatsSent.last().steamId)

        // DMarket now links another Steam account: the held token is the wrong one, and every Steam action stops.
        marketplace.heartbeatResponse = HeartbeatResponse(ttlSeconds = 60, linkedSteamId = SteamId(THEIRS))
        loop.forceHeartbeatNow()
        loop.runOnce()
        assertTrue(loop.linkedSteamIdMismatch)
        assertEquals(1, mismatches.size)

        // That account's token is held, and the very next cycle runs on it — the first token still has hours left.
        assertIs<SteamPushResult.Held>(steam.push(token(THEIRS)))
        loop.forceHeartbeatNow()
        loop.runOnce()
        assertEquals(SteamId(THEIRS), marketplace.heartbeatsSent.last().steamId)
        assertFalse(loop.linkedSteamIdMismatch)
    }

    // ---- secrets -------------------------------------------------------------------------------------

    @Test
    fun no_result_and_no_held_state_prints_the_token() = runTest {
        val mine = token()
        val theirs = token(THEIRS)
        val results = listOf(session.push(mine), session.push(theirs), session.push("not-a-token"))

        val printed = results.map { it.toString() } + session.credential.toString()
        for (text in printed) {
            assertTrue(mine.substringAfter('.') !in text && theirs.substringAfter('.') !in text, "a token leaked: $text")
        }
    }

    private companion object {
        const val MINE = "76561198000000001"
        const val THEIRS = "76561198000000099"
    }
}
