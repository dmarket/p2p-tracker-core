package com.dmarket.p2p.tracker.credential.marketplace

import com.dmarket.p2p.tracker.adapter.host.InMemoryMarketplaceRefreshStateStore
import com.dmarket.p2p.tracker.client.HttpStatusException
import com.dmarket.p2p.tracker.model.marketplace.MarketplaceTokenPair
import com.dmarket.p2p.tracker.model.marketplace.StoredMarketplaceTokens
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceRefreshRejectedException
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceRefreshStateStore
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceTokenStore
import com.dmarket.p2p.tracker.support.FakeClock
import com.dmarket.p2p.tracker.support.FakeMarketplaceTokenRefreshClient
import com.dmarket.p2p.tracker.support.FakeMarketplaceTokenStore
import com.dmarket.p2p.tracker.support.jwtExpiringAt
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The shared DMarket refresh algorithm.
 *
 * The headline case is the one the previous implementation got wrong: the site gives the **access** cookie
 * the **refresh** token's ~30-day expiry, so anything that derives freshness from the cookie sees a fresh
 * token for a month and never refreshes. Every test here therefore builds the access token as a real JWT with
 * its own `exp`, and the store's `refreshTokenExpiresAt` far in the future — the shape that exists in the
 * field.
 */
class DefaultMarketplaceCredentialProviderTest {

    private val clock = FakeClock()
    private val now: Instant get() = clock.now()

    private fun provider(
        store: FakeMarketplaceTokenStore,
        refreshClient: FakeMarketplaceTokenRefreshClient,
        state: MarketplaceRefreshStateStore = InMemoryMarketplaceRefreshStateStore(),
        deferWhileOtherActorActive: Boolean = false,
    ) = DefaultMarketplaceCredentialProvider(
        store = store,
        refreshClient = refreshClient,
        clock = clock,
        config = DefaultMarketplaceCredentialProvider.Config(
            usableSkew = 60.seconds,
            refreshHeadroom = 10.minutes,
            refreshTokenMinLife = 60.seconds,
            minRefreshInterval = 60.seconds,
            deferWhileOtherActorActive = deferWhileOtherActorActive,
        ),
        state = state,
    )

    /** A store in the shape the browser actually holds: access JWT expiring at [accessExp], 30-day refresh. */
    private fun store(accessExp: Instant?, refreshToken: String? = "refresh-1") = FakeMarketplaceTokenStore(
        stored = StoredMarketplaceTokens(
            accessToken = accessExp?.let { jwtExpiringAt(it) },
            refreshToken = refreshToken,
            refreshTokenExpiresAt = now + 30.days,
        ),
    )

    private fun pair(accessExp: Instant, refreshToken: String = "refresh-2") = MarketplaceTokenPair(
        accessToken = jwtExpiringAt(accessExp),
        refreshToken = refreshToken,
        refreshTokenExpiresAt = now + 30.days,
    )

    // ---- the headline regression ---------------------------------------------------------------

    @Test
    fun refreshes_an_expired_access_token_even_though_its_cookie_lives_for_30_more_days() = runTest {
        // Exactly the field shape: the JWT died 2h ago, the cookie carrying it is good for a month.
        val s = store(accessExp = now - 2.hours)
        val client = FakeMarketplaceTokenRefreshClient(nextPair = pair(now + 24.hours))
        val credential = provider(s, client).current()

        assertEquals(1, client.presented.size, "must refresh: the cookie's expiry says nothing about the token")
        assertEquals("refresh-1", client.presented.single())
        assertNotNull(credential)
        assertEquals(s.stored?.accessToken, credential.token, "the rotated token must be what is handed out")
    }

    // ---- the two thresholds -------------------------------------------------------------------

    @Test
    fun a_token_with_more_life_than_the_headroom_costs_no_request() = runTest {
        val client = FakeMarketplaceTokenRefreshClient()
        val credential = provider(store(accessExp = now + 30.minutes), client).current()
        assertTrue(client.presented.isEmpty(), "30 min left is inside the 10 min headroom → no refresh")
        assertNotNull(credential)
    }

    @Test
    fun a_token_inside_the_headroom_but_still_usable_is_refreshed_exactly_once() = runTest {
        // 9 minutes left: past the 10-minute refresh trigger, far above the 60-second usable floor. The old
        // arrangement gated on the 60s skew, so this case never refreshed at all.
        val client = FakeMarketplaceTokenRefreshClient(nextPair = pair(now + 24.hours))
        provider(store(accessExp = now + 9.minutes), client).current()
        assertEquals(1, client.presented.size)
    }

    @Test
    fun eleven_minutes_of_life_does_not_refresh_but_nine_does() = runTest {
        val quiet = FakeMarketplaceTokenRefreshClient(nextPair = pair(now + 24.hours))
        provider(store(accessExp = now + 11.minutes), quiet).current()
        assertTrue(quiet.presented.isEmpty())

        val busy = FakeMarketplaceTokenRefreshClient(nextPair = pair(now + 24.hours))
        provider(store(accessExp = now + 9.minutes), busy).current()
        assertEquals(1, busy.presented.size)
    }

    @Test
    fun an_unreadable_access_token_is_refreshed_rather_than_trusted() = runTest {
        val s = FakeMarketplaceTokenStore(
            stored = StoredMarketplaceTokens("not-a-jwt", "refresh-1", now + 30.days),
        )
        val client = FakeMarketplaceTokenRefreshClient(nextPair = pair(now + 24.hours))
        provider(s, client).current()
        assertEquals(1, client.presented.size, "a token whose life we cannot establish must not be trusted")
    }

    // ---- zero-network paths -------------------------------------------------------------------

    @Test
    fun an_empty_store_reports_logged_out_and_spends_nothing() = runTest {
        val s = FakeMarketplaceTokenStore(stored = StoredMarketplaceTokens(null, null, null))
        val client = FakeMarketplaceTokenRefreshClient()
        val p = provider(s, client)
        assertNull(p.current())
        assertTrue(p.lastRefreshFailedLoggedOut)
        assertTrue(client.presented.isEmpty())
    }

    @Test
    fun a_spent_refresh_token_reports_logged_out_and_spends_nothing() = runTest {
        val s = FakeMarketplaceTokenStore(
            stored = StoredMarketplaceTokens(jwtExpiringAt(now - 1.hours), "refresh-1", now + 10.seconds),
        )
        val client = FakeMarketplaceTokenRefreshClient()
        val p = provider(s, client)
        assertNull(p.current())
        assertTrue(p.lastRefreshFailedLoggedOut)
        assertTrue(client.presented.isEmpty(), "a refresh token inside its own floor is not worth a request")
    }

    @Test
    fun no_refresh_token_reports_logged_out_and_spends_nothing() = runTest {
        val client = FakeMarketplaceTokenRefreshClient()
        val p = provider(store(accessExp = now - 1.hours, refreshToken = null), client)
        assertNull(p.current())
        assertTrue(p.lastRefreshFailedLoggedOut)
        assertTrue(client.presented.isEmpty())
    }

    // ---- success ------------------------------------------------------------------------------

    @Test
    fun a_successful_refresh_stores_the_whole_pair_and_clears_the_logged_out_flag() = runTest {
        val s = store(accessExp = now - 1.hours)
        val fresh = pair(now + 24.hours, refreshToken = "refresh-rotated")
        val client = FakeMarketplaceTokenRefreshClient(nextPair = fresh)
        val p = provider(s, client)

        val credential = p.current()

        assertEquals(1, s.written.size, "both halves are written as one pair")
        assertEquals("refresh-rotated", s.written.single().refreshToken)
        assertEquals(fresh.accessToken, credential?.token)
        assertFalse(p.lastRefreshFailedLoggedOut)
    }

    @Test
    fun the_expired_access_token_is_still_sent_as_authorization_on_the_refresh_call() = runTest {
        // The web frontend does exactly this on its own post-401 refresh, and it is the only shape proven to
        // work against the gateway.
        val s = store(accessExp = now - 1.hours)
        val client = FakeMarketplaceTokenRefreshClient(nextPair = pair(now + 24.hours))
        provider(s, client).current()
        assertNotNull(client.accessTokensSent.single())
    }

    // ---- collisions ---------------------------------------------------------------------------

    @Test
    fun a_store_that_became_fresh_while_we_waited_costs_no_request() = runTest {
        val s = store(accessExp = now - 1.hours)
        val client = FakeMarketplaceTokenRefreshClient(nextPair = pair(now + 24.hours))
        // Simulate the site's SPA rotating between the first read and the locked re-read: the fake store hands
        // out a fresh pair from its second read onwards.
        var reads = 0
        val racing = object : MarketplaceTokenStore {
            override suspend fun read(): StoredMarketplaceTokens? {
                reads++
                return if (reads <= 1) {
                    s.stored
                } else {
                    StoredMarketplaceTokens(
                        accessToken = jwtExpiringAt(now + 24.hours),
                        refreshToken = "refresh-from-site",
                        refreshTokenExpiresAt = now + 30.days,
                    )
                }
            }

            override suspend fun write(tokens: MarketplaceTokenPair) = MarketplaceTokenStore.WriteOutcome.WRITTEN
        }
        val p = DefaultMarketplaceCredentialProvider(
            store = racing,
            refreshClient = client,
            clock = clock,
            config = DefaultMarketplaceCredentialProvider.Config(
                usableSkew = 60.seconds,
                refreshHeadroom = 10.minutes,
                refreshTokenMinLife = 60.seconds,
                minRefreshInterval = 60.seconds,
            ),
        )
        assertNotNull(p.current())
        assertTrue(client.presented.isEmpty(), "the locked re-read must skip the request entirely")
    }

    @Test
    fun a_write_that_lost_the_race_adopts_what_the_store_now_holds() = runTest {
        val siteToken = jwtExpiringAt(now + 20.hours)
        val stale = StoredMarketplaceTokens(jwtExpiringAt(now - 1.hours), "refresh-1", now + 30.days)
        val siteWon = StoredMarketplaceTokens(siteToken, "refresh-1", now + 30.days)
        // The site's write lands between our POST and our own write: the store still reports our refresh token
        // (so this is not the "another writer rotated" branch), but the read-back after our write shows theirs.
        var wrote = false
        val racing = object : MarketplaceTokenStore {
            override suspend fun read(): StoredMarketplaceTokens = if (wrote) siteWon else stale

            override suspend fun write(tokens: MarketplaceTokenPair): MarketplaceTokenStore.WriteOutcome {
                wrote = true
                return MarketplaceTokenStore.WriteOutcome.LOST_RACE
            }
        }
        val p = DefaultMarketplaceCredentialProvider(
            store = racing,
            refreshClient = FakeMarketplaceTokenRefreshClient(nextPair = pair(now + 24.hours)),
            clock = clock,
            config = DefaultMarketplaceCredentialProvider.Config(
                usableSkew = 60.seconds,
                refreshHeadroom = 10.minutes,
                refreshTokenMinLife = 60.seconds,
                minRefreshInterval = 60.seconds,
            ),
        )

        val credential = p.current()

        assertEquals(siteToken, credential?.token, "the other writer's value is at least as new — take it")
        assertFalse(p.lastRefreshFailedLoggedOut)
    }

    @Test
    fun a_blind_write_is_transient_and_never_a_logged_out_verdict() = runTest {
        val s = store(accessExp = now - 1.hours)
        s.writeOutcome = MarketplaceTokenStore.WriteOutcome.BLIND
        val fresh = pair(now + 24.hours)
        val p = provider(s, FakeMarketplaceTokenRefreshClient(nextPair = fresh))

        val credential = p.current()

        assertEquals(fresh.accessToken, credential?.token, "the minted token is still usable in memory")
        assertFalse(p.lastRefreshFailedLoggedOut, "a client that cannot write is not a signed-out user")
    }

    // ---- refusal, retry, and the latch --------------------------------------------------------

    @Test
    fun a_corroborated_refusal_reports_logged_out() = runTest {
        val s = store(accessExp = now - 1.hours)
        val client = FakeMarketplaceTokenRefreshClient(failWith = MarketplaceRefreshRejectedException(401))
        val p = provider(s, client)

        assertNull(p.current())
        assertTrue(p.lastRefreshFailedLoggedOut)
        assertEquals(1, client.presented.size, "no retry when the store still holds the refused token")
    }

    @Test
    fun losing_the_server_race_to_the_site_adopts_its_pair_instead_of_rotating_again() = runTest {
        // The site's SPA refreshed first, so the server refused ours and the jar now holds a FRESH pair. Since
        // rotation voids the predecessor, that pair is the only live one — rotating again would void the token
        // the site has just started using, manufacturing the very sign-out we are trying to avoid.
        val s = store(accessExp = now - 1.hours)
        val siteAccess = jwtExpiringAt(now + 24.hours)
        val client = object : FakeMarketplaceTokenRefreshClient() {
            override suspend fun refresh(refreshToken: String, accessTokenOrNull: String?): MarketplaceTokenPair {
                presented += refreshToken
                s.stored = StoredMarketplaceTokens(siteAccess, "refresh-from-site", now + 30.days)
                throw MarketplaceRefreshRejectedException(401)
            }
        }
        val p = provider(s, client)

        val credential = p.current()

        assertEquals(siteAccess, credential?.token, "the site's fresh token must be adopted as-is")
        assertEquals(1, client.presented.size, "exactly one attempt — no second rotation")
        assertFalse(p.lastRefreshFailedLoggedOut)
    }

    @Test
    fun a_refusal_of_a_superseded_token_retries_once_with_the_new_one() = runTest {
        val s = store(accessExp = now - 1.hours)
        var calls = 0
        val client = object : FakeMarketplaceTokenRefreshClient() {
            override suspend fun refresh(refreshToken: String, accessTokenOrNull: String?): MarketplaceTokenPair {
                presented += refreshToken
                calls++
                if (calls == 1) {
                    // The site rotates while our first request is in flight.
                    s.stored = StoredMarketplaceTokens(jwtExpiringAt(now - 1.hours), "refresh-site", now + 30.days)
                    throw MarketplaceRefreshRejectedException(401)
                }
                return pair(now + 24.hours)
            }
        }
        val p = provider(s, client)

        assertNotNull(p.current())
        assertEquals(listOf("refresh-1", "refresh-site"), client.presented)
        assertFalse(p.lastRefreshFailedLoggedOut)
    }

    @Test
    fun the_latch_makes_a_second_wake_free_and_a_new_token_un_latches_it() = runTest {
        val state = InMemoryMarketplaceRefreshStateStore()
        val s = store(accessExp = now - 1.hours)
        val refusing = FakeMarketplaceTokenRefreshClient(failWith = MarketplaceRefreshRejectedException(401))

        assertNull(provider(s, refusing, state).current())
        assertEquals(1, refusing.presented.size)

        // A fresh provider instance, as a respawned worker would build — the latch lives in the store.
        val second = FakeMarketplaceTokenRefreshClient(failWith = MarketplaceRefreshRejectedException(401))
        val p2 = provider(s, second, state)
        assertNull(p2.current())
        assertTrue(second.presented.isEmpty(), "a refused token must not be re-presented on every wake")
        assertTrue(p2.lastRefreshFailedLoggedOut)

        // The site signs back in → different refresh token → the latch no longer matches.
        s.stored = StoredMarketplaceTokens(jwtExpiringAt(now - 1.hours), "refresh-new", now + 30.days)
        val third = FakeMarketplaceTokenRefreshClient(nextPair = pair(now + 24.hours))
        assertNotNull(provider(s, third, state).current())
        assertEquals(1, third.presented.size)
    }

    @Test
    fun a_latched_provider_returns_null_rather_than_a_dead_token() = runTest {
        // Load-bearing: the loop's guard and the 401 authenticator are null checks, so handing back the dead
        // token would turn one missing-connection verdict into 1 + maxRetries rejected requests per wake.
        val state = InMemoryMarketplaceRefreshStateStore()
        val s = store(accessExp = now - 1.hours)
        provider(s, FakeMarketplaceTokenRefreshClient(failWith = MarketplaceRefreshRejectedException(401)), state)
            .current()

        val p = provider(s, FakeMarketplaceTokenRefreshClient(), state)
        assertNull(p.current(), "the access token is long dead — it must not be offered")
        assertNull(p.forceRefresh(), "forceRefresh must also refuse, so refreshOnUnauthorized() returns false")
    }

    // ---- transient failures -------------------------------------------------------------------

    @Test
    fun a_transient_failure_keeps_a_still_usable_token_and_no_logged_out_verdict() = runTest {
        val s = store(accessExp = now + 5.minutes) // inside the headroom, still well above the usable floor
        val client = FakeMarketplaceTokenRefreshClient(
            failWith = HttpStatusException(503, "POST", "https://api.example/refresh-token", null, null),
        )
        val p = provider(s, client)

        val credential = p.current()

        assertNotNull(credential, "5 minutes of life is still sendable")
        assertFalse(p.lastRefreshFailedLoggedOut, "a 503 says nothing about the session")
    }

    @Test
    fun a_transient_failure_on_a_dead_token_returns_null_without_claiming_logged_out() = runTest {
        val s = store(accessExp = now - 1.hours)
        val client = FakeMarketplaceTokenRefreshClient(
            failWith = HttpStatusException(404, "POST", "https://api.example/refresh-token", null, null),
        )
        val p = provider(s, client)

        assertNull(p.current(), "a dead token must not be sent")
        assertFalse(p.lastRefreshFailedLoggedOut, "a 404 route is broken infrastructure, not a signed-out user")
    }

    @Test
    fun repeated_transient_failures_stop_being_retried() = runTest {
        val state = InMemoryMarketplaceRefreshStateStore()
        val s = store(accessExp = now - 1.hours)
        var attempts = 0
        repeat(8) {
            val client = object : FakeMarketplaceTokenRefreshClient() {
                override suspend fun refresh(refreshToken: String, accessTokenOrNull: String?): MarketplaceTokenPair {
                    attempts++
                    throw HttpStatusException(404, "POST", "https://api.example/refresh-token", null, null)
                }
            }
            provider(s, client, state).current()
        }
        assertEquals(5, attempts, "a permanently broken endpoint must stop costing a request per wake")
    }

    // ---- rate limit and deference -------------------------------------------------------------

    @Test
    fun two_calls_inside_the_minimum_interval_refresh_once() = runTest {
        val state = InMemoryMarketplaceRefreshStateStore()
        val s = store(accessExp = now - 1.hours)
        val client = FakeMarketplaceTokenRefreshClient(nextPair = pair(now + 24.hours))
        val p = provider(s, client, state)

        p.current()
        // Put the store back into "needs refresh" so only the rate limit can stop the second attempt.
        s.stored = StoredMarketplaceTokens(jwtExpiringAt(now - 1.hours), "refresh-1", now + 30.days)
        p.current()

        assertEquals(1, client.presented.size, "our own rotations must be spaced")
    }

    @Test
    fun a_forced_refresh_ignores_the_rate_limit() = runTest {
        val state = InMemoryMarketplaceRefreshStateStore()
        val s = store(accessExp = now - 1.hours)
        val client = FakeMarketplaceTokenRefreshClient(nextPair = pair(now + 24.hours))
        val p = provider(s, client, state)

        p.current()
        s.stored = StoredMarketplaceTokens(jwtExpiringAt(now - 1.hours), "refresh-1", now + 30.days)
        p.forceRefresh()

        assertEquals(2, client.presented.size, "the 401 path must be able to insist")
    }

    @Test
    fun a_forced_refresh_ignores_the_deference() = runTest {
        // The site's own refresh is driven by its response interceptor, so an open but IDLE tab never refreshes.
        // If a forced refresh deferred to it, a 401 would stay unrecoverable for as long as that tab is open.
        val s = store(accessExp = now - 1.hours)
        s.otherActorActive = true
        val client = FakeMarketplaceTokenRefreshClient(nextPair = pair(now + 24.hours))
        val p = provider(s, client, deferWhileOtherActorActive = true)

        assertNotNull(p.forceRefresh())
        assertEquals(1, client.presented.size, "the 401 path must be able to insist")
    }

    @Test
    fun deference_declines_to_rotate_while_the_site_is_open() = runTest {
        val s = store(accessExp = now + 5.minutes)
        s.otherActorActive = true
        val client = FakeMarketplaceTokenRefreshClient(nextPair = pair(now + 24.hours))
        val p = provider(s, client, deferWhileOtherActorActive = true)

        assertNotNull(p.current(), "still-usable token is returned")
        assertTrue(client.presented.isEmpty(), "the site's own SPA will refresh; do not race it")
    }

    // ---- anti-loop ----------------------------------------------------------------------------

    @Test
    fun a_refreshed_token_that_does_not_clear_the_trigger_is_a_failure_not_a_success() = runTest {
        // Without this, our own cookie write wakes a cycle, that cycle refreshes again, and so on.
        val state = InMemoryMarketplaceRefreshStateStore()
        val s = store(accessExp = now - 1.hours)
        val client = FakeMarketplaceTokenRefreshClient(nextPair = pair(now + 30.seconds))
        val p = provider(s, client, state)

        p.current()
        assertEquals(1, client.presented.size)

        // Second call, still needing a refresh: the rate limit (armed by the failure) must hold it off.
        p.current()
        assertEquals(1, client.presented.size, "a useless refresh must not be retried immediately")
    }
}
