package com.dmarket.p2p.tracker.credential.steam

import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.port.SessionRefreshOutcome
import com.dmarket.p2p.tracker.port.steam.SteamSessionRefresher
import com.dmarket.p2p.tracker.port.steam.SteamSessionScraper
import com.dmarket.p2p.tracker.port.steam.SteamWebSessionState
import com.dmarket.p2p.tracker.support.FakeClock
import com.dmarket.p2p.tracker.support.FakeCredentialVault
import com.dmarket.p2p.tracker.support.FakeSteamSessionRefresher
import com.dmarket.p2p.tracker.support.FakeSteamSessionScraper
import com.dmarket.p2p.tracker.support.fakeSteamCredential
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class SteamCredentialProviderTest {

    private val clock = FakeClock(current = Instant.fromEpochMilliseconds(1_781_611_200_000L))

    private fun provider(
        vault: FakeCredentialVault = FakeCredentialVault(),
        scraper: FakeSteamSessionScraper = FakeSteamSessionScraper(),
    ) = SteamCredentialProvider(vault = vault, scraper = scraper, clock = clock)

    // ---- current(): no refresh when credential is fresh ----------------------------------------

    @Test
    fun current_returns_cached_credential_without_scraping_when_fresh() = runTest {
        // Far-future credential → isFresh = true → scraper must NOT be called.
        val cred = fakeSteamCredential()
        val vault = FakeCredentialVault(steamCredential = cred)
        val scraper = FakeSteamSessionScraper(result = cred)
        val p = provider(vault, scraper)

        val result = p.current()

        assertEquals(cred, result)
        assertEquals(0, scraper.scrapeCalls, "scraper must not be called when credential is fresh")
    }

    // ---- current(): refresh when credential is stale ------------------------------------------

    @Test
    fun current_scrapes_and_stores_when_credential_is_stale() = runTest {
        // Credential expired in the past.
        val staleCred = fakeSteamCredential().copy(
            expiresAt = Instant.fromEpochSeconds(1_000_000_000L), // way in the past
        )
        val freshCred = fakeSteamCredential(token = "fresh-token")
        val vault = FakeCredentialVault(steamCredential = staleCred)
        val scraper = FakeSteamSessionScraper(result = freshCred)
        val p = provider(vault, scraper)

        val result = p.current()

        assertEquals(freshCred, result)
        assertEquals(1, scraper.scrapeCalls)
        assertEquals(freshCred, vault.readSteamCredential())
    }

    // ---- current(): scrape when vault is empty ------------------------------------------------

    @Test
    fun current_scrapes_and_stores_when_vault_is_empty() = runTest {
        val fresh = fakeSteamCredential()
        val vault = FakeCredentialVault(steamCredential = null)
        val scraper = FakeSteamSessionScraper(result = fresh)
        val p = provider(vault, scraper)

        val result = p.current()

        assertEquals(fresh, result)
        assertEquals(1, scraper.scrapeCalls)
    }

    // ---- current(): logged out → sets flag, returns null --------------------------------------

    @Test
    fun current_sets_lastRefreshFailedLoggedOut_when_scraper_returns_null() = runTest {
        val vault = FakeCredentialVault(steamCredential = null)
        val scraper = FakeSteamSessionScraper(result = null)
        val p = provider(vault, scraper)

        assertFalse(p.lastRefreshFailedLoggedOut)
        val result = p.current()

        assertNull(result)
        assertTrue(p.lastRefreshFailedLoggedOut)
    }

    // ---- account switch: clears old credential before writing new ----------------------------

    @Test
    fun current_clears_old_credential_on_account_switch() = runTest {
        val oldCred = fakeSteamCredential(steamId = "111")
            .copy(expiresAt = Instant.fromEpochSeconds(1_000_000_000L)) // stale
        val newCred = fakeSteamCredential(steamId = "222")
        val vault = FakeCredentialVault(steamCredential = oldCred)
        val scraper = FakeSteamSessionScraper(result = newCred)
        val p = provider(vault, scraper)

        val result = p.current()

        assertEquals(newCred, result)
        // Vault should contain the NEW credential, not the old one
        assertEquals(newCred, vault.readSteamCredential())
    }

    // ---- account switch on a STILL-FRESH cache: the cookie's identity is the only signal -------
    // The case above deliberately marks the cached credential stale, so it exercises the path the real
    // account switch never takes: a scraped token stays fresh by its own JWT clock for ~24h no matter who
    // is signed in, and a re-login re-Set-Cookies a perfectly healthy session. Only the cookie's steamid
    // can say the cache is wrong.

    private val accountA = SteamId("76561198000000111")
    private val accountB = SteamId("76561198000000222")

    @Test
    fun current_rescrapes_when_the_session_cookie_belongs_to_another_account() = runTest {
        val cachedA = fakeSteamCredential(token = "token-a", steamId = accountA.value) // FRESH, not stale
        val scrapedB = fakeSteamCredential(token = "token-b", steamId = accountB.value)
        val vault = FakeCredentialVault(steamCredential = cachedA)
        val scraper = FakeSteamSessionScraper(result = scrapedB)
        val p = SteamCredentialProvider(
            vault = vault,
            scraper = scraper,
            clock = FakeClock(),
            sessionRefresher = FakeSteamSessionRefresher(cookieSteamId = accountB),
        )

        val result = p.current()

        assertEquals(scrapedB, result, "the credential must follow the session, not its own expiry")
        assertEquals(scrapedB, vault.readSteamCredential())
        assertEquals(1, scraper.scrapeCalls, "exactly one re-scrape — the switch is not a retry loop")
    }

    @Test
    fun current_keeps_the_fresh_cache_when_the_cookie_account_agrees() = runTest {
        val cachedA = fakeSteamCredential(steamId = accountA.value)
        val vault = FakeCredentialVault(steamCredential = cachedA)
        val scraper = FakeSteamSessionScraper(result = cachedA)
        val p = SteamCredentialProvider(
            vault = vault,
            scraper = scraper,
            clock = FakeClock(),
            sessionRefresher = FakeSteamSessionRefresher(cookieSteamId = accountA),
        )

        assertEquals(cachedA, p.current())
        assertEquals(0, scraper.scrapeCalls, "the identity gate must not degenerate into a scrape per cycle")
    }

    @Test
    fun current_keeps_the_fresh_cache_when_the_cookie_account_is_unknown() = runTest {
        // The NoOp / mobile / cookie-hiccup default: identity unknown is never a switch (fail-open).
        val cachedA = fakeSteamCredential(steamId = accountA.value)
        val vault = FakeCredentialVault(steamCredential = cachedA)
        val scraper = FakeSteamSessionScraper(result = cachedA)
        val p = SteamCredentialProvider(
            vault = vault,
            scraper = scraper,
            clock = FakeClock(),
            sessionRefresher = FakeSteamSessionRefresher(cookieSteamId = null),
        )

        assertEquals(cachedA, p.current())
        assertEquals(0, scraper.scrapeCalls)
    }

    @Test
    fun current_withholds_the_old_account_credential_when_the_rescrape_fails() = runTest {
        // Reads authenticate with the token and writes ride the browser cookie, so handing back the
        // previous account's token because the scrape blipped would act on the wrong account. Return null.
        val cachedA = fakeSteamCredential(steamId = accountA.value)
        val vault = FakeCredentialVault(steamCredential = cachedA)
        val scraper = FakeSteamSessionScraper(result = null) // logged out mid-switch
        val p = SteamCredentialProvider(
            vault = vault,
            scraper = scraper,
            clock = FakeClock(),
            sessionRefresher = FakeSteamSessionRefresher(cookieSteamId = accountB),
        )

        assertNull(p.current())
        assertNull(vault.readSteamCredential(), "the wrong account's token must not survive in the vault")
        assertTrue(p.lastRefreshFailedLoggedOut)
    }

    @Test
    fun concurrent_current_calls_after_an_account_switch_scrape_once() = runTest {
        // The OTHER_ACCOUNT branch re-scrapes while already holding the mutex (it must — the lock is not
        // reentrant), so single-flight has to survive the refreshLocked extraction.
        val cachedA = fakeSteamCredential(steamId = accountA.value)
        val vault = FakeCredentialVault(steamCredential = cachedA)
        val scraper = FakeSteamSessionScraper(result = fakeSteamCredential(steamId = accountB.value))
        val p = SteamCredentialProvider(
            vault = vault,
            scraper = scraper,
            clock = FakeClock(),
            sessionRefresher = FakeSteamSessionRefresher(cookieSteamId = accountB),
        )

        val a = async { p.current() }
        val b = async { p.current() }
        a.await()
        b.await()

        assertEquals(1, scraper.scrapeCalls, "the second caller must reuse the first's result")
    }

    // ---- a session that is GONE takes its credential with it ------------------------------------

    @Test
    fun a_gone_session_clears_the_cached_credential() = runTest {
        // Otherwise a logout leaves the old token behind, and a login as a DIFFERENT account then meets a
        // still-fresh cache for the previous one.
        val cached = fakeSteamCredential(steamId = accountA.value)
        val vault = FakeCredentialVault(steamCredential = cached)
        val p = SteamCredentialProvider(
            vault = vault,
            scraper = FakeSteamSessionScraper(result = cached),
            clock = FakeClock(),
            sessionRefresher = FakeSteamSessionRefresher(state = SteamWebSessionState.GONE),
        )

        assertNull(p.current())
        assertNull(vault.readSteamCredential())
        assertTrue(p.sessionMissing)
    }

    // ---- single-flight: two concurrent current() calls → exactly one scrape ------------------

    @Test
    fun concurrent_current_calls_trigger_only_one_scrape() = runTest {
        val stale = fakeSteamCredential()
            .copy(expiresAt = Instant.fromEpochSeconds(1_000_000_000L)) // stale
        val fresh = fakeSteamCredential(token = "fresh-token")
        val vault = FakeCredentialVault(steamCredential = stale)
        val scraper = FakeSteamSessionScraper(result = fresh)
        val p = provider(vault, scraper)

        // Launch two coroutines concurrently. Under runTest they interleave cooperatively.
        val d1 = async { p.current() }
        val d2 = async { p.current() }
        val r1 = d1.await()
        val r2 = d2.await()

        // Both should return the fresh credential.
        assertEquals(fresh, r1)
        assertEquals(fresh, r2)
        // Exactly ONE scrape must have happened — single-flight guarantee.
        assertEquals(1, scraper.scrapeCalls, "Expected exactly 1 scrape call, got ${scraper.scrapeCalls}")
    }

    // ---- session refresh: runs before the scrape, failures are swallowed ----------------------

    @Test
    fun current_refreshes_session_before_scraping_on_stale_credential() = runTest {
        val events = mutableListOf<String>()
        val stale = fakeSteamCredential().copy(expiresAt = Instant.fromEpochSeconds(1_000_000_000L))
        val fresh = fakeSteamCredential(token = "fresh-token")
        val refresher = object : SteamSessionRefresher {
            // A session inside its renewal window — that is what makes the keep-alive run at all now.
            override suspend fun sessionState(expectedSteamId: SteamId?): SteamWebSessionState = SteamWebSessionState.NEEDS_REFRESH

            override suspend fun refreshSession(force: Boolean): SessionRefreshOutcome {
                events += "refresh"
                return SessionRefreshOutcome.REFRESHED
            }
        }
        val scraper = object : SteamSessionScraper {
            override suspend fun scrape(): SteamCredential {
                events += "scrape"
                return fresh
            }
        }
        val p = SteamCredentialProvider(
            vault = FakeCredentialVault(steamCredential = stale),
            scraper = scraper,
            clock = clock,
            sessionRefresher = refresher,
        )

        p.current()

        assertEquals(listOf("refresh", "scrape"), events, "session refresh must run before the scrape")
    }

    @Test
    fun current_swallows_session_refresh_failure_and_still_scrapes() = runTest {
        val stale = fakeSteamCredential().copy(expiresAt = Instant.fromEpochSeconds(1_000_000_000L))
        val fresh = fakeSteamCredential(token = "fresh-token")
        val scraper = FakeSteamSessionScraper(result = fresh)
        val refresher = FakeSteamSessionRefresher(
            failWith = RuntimeException("ajaxrefresh exploded"),
            state = SteamWebSessionState.NEEDS_REFRESH,
        )
        val p = SteamCredentialProvider(
            vault = FakeCredentialVault(steamCredential = stale),
            scraper = scraper,
            clock = clock,
            sessionRefresher = refresher,
        )

        val result = p.current()

        assertEquals(fresh, result, "a refresh failure must not abort credential acquisition")
        assertEquals(1, refresher.refreshCalls)
        assertEquals(1, scraper.scrapeCalls)
    }

    @Test
    fun current_does_not_refresh_session_when_credential_is_fresh() = runTest {
        val cred = fakeSteamCredential()
        val scraper = FakeSteamSessionScraper(result = cred)
        val refresher = FakeSteamSessionRefresher()
        val p = SteamCredentialProvider(
            vault = FakeCredentialVault(steamCredential = cred),
            scraper = scraper,
            clock = clock,
            sessionRefresher = refresher,
        )

        p.current()

        assertEquals(0, refresher.refreshCalls, "fresh-cache fast path must not touch the session refresher")
        assertEquals(0, scraper.scrapeCalls)
    }

    // ---- forceRefresh: always scrapes --------------------------------------------------------

    @Test
    fun forceRefresh_scrapes_even_when_credential_is_fresh() = runTest {
        val cred = fakeSteamCredential()
        val vault = FakeCredentialVault(steamCredential = cred)
        val fresh = fakeSteamCredential(token = "new-token")
        val scraper = FakeSteamSessionScraper(result = fresh)
        val p = provider(vault, scraper)

        val result = p.forceRefresh()

        assertEquals(fresh, result)
        assertEquals(1, scraper.scrapeCalls)
    }

    // ---- sessionMissing: the corroborated logged-out verdict ------------------------------------

    /**
     * A Steam logout deletes the session cookie but leaves the scraped token in the vault, unexpired. The
     * fresh-cache fast path must therefore confirm the session still exists — otherwise the loop keeps
     * acting on a dead session (and the host keeps claiming tracking is live) for the rest of that token's
     * ~24h life. One cookie read, no network, no scrape.
     */
    @Test
    fun current_reports_logged_out_when_the_session_cookie_is_gone_despite_a_fresh_cache() = runTest {
        val cred = fakeSteamCredential()
        val scraper = FakeSteamSessionScraper(result = cred)
        val refresher = FakeSteamSessionRefresher(state = SteamWebSessionState.GONE)
        val p = SteamCredentialProvider(
            vault = FakeCredentialVault(steamCredential = cred),
            scraper = scraper,
            clock = clock,
            sessionRefresher = refresher,
        )

        assertNull(p.current(), "a fresh cached token behind a deleted session cookie is not usable")
        assertTrue(p.sessionMissing)
        assertEquals(1, refresher.stateCalls)
        assertEquals(0, refresher.refreshCalls, "the liveness check must never be a session re-mint")
        assertEquals(0, scraper.scrapeCalls)
    }

    // ---- the write axis: the cookie session the two Steam writes actually authenticate with -------

    /**
     * [SteamCredentialProvider.sessionBelongsTo] is the write surfaces' own identity check — the loop calls
     * it immediately before a create or cancel, since those POST with the cookie session and never use the
     * credential they are handed. Fail-open on an unreadable store, so a hiccup cannot block a real write.
     */
    @Test
    fun session_belongs_to_answers_for_the_write_axis_and_fails_open() = runTest {
        val ours = SteamId("76561198000000001")
        val cred = fakeSteamCredential()
        fun provider(refresher: FakeSteamSessionRefresher) = SteamCredentialProvider(
            vault = FakeCredentialVault(steamCredential = cred),
            scraper = FakeSteamSessionScraper(result = cred),
            clock = clock,
            sessionRefresher = refresher,
        )

        assertTrue(provider(FakeSteamSessionRefresher(cookieSteamId = ours)).sessionBelongsTo(ours))
        assertFalse(provider(FakeSteamSessionRefresher(cookieSteamId = SteamId("76561198000000009"))).sessionBelongsTo(ours))
        assertTrue(
            provider(FakeSteamSessionRefresher(stateFailsWith = RuntimeException("cookie store gone"))).sessionBelongsTo(ours),
            "a cookie-store failure must never block a legitimate write",
        )
    }

    /** The liveness check fails OPEN: an unreadable cookie store must never manufacture a logout. */
    @Test
    fun current_keeps_a_fresh_cached_credential_when_the_liveness_check_throws() = runTest {
        val cred = fakeSteamCredential()
        val refresher = FakeSteamSessionRefresher(stateFailsWith = RuntimeException("cookie store gone"))
        val p = SteamCredentialProvider(
            vault = FakeCredentialVault(steamCredential = cred),
            scraper = FakeSteamSessionScraper(result = cred),
            clock = clock,
            sessionRefresher = refresher,
        )

        assertEquals(cred, p.current())
        assertFalse(p.sessionMissing)
    }

    /** A cookie that is back clears an earlier verdict, so the block can't be pinned by a stale flag. */
    @Test
    fun a_returned_session_cookie_clears_the_logged_out_verdict() = runTest {
        val cred = fakeSteamCredential()
        val refresher = FakeSteamSessionRefresher(state = SteamWebSessionState.GONE)
        val p = SteamCredentialProvider(
            vault = FakeCredentialVault(steamCredential = cred),
            scraper = FakeSteamSessionScraper(result = cred),
            clock = clock,
            sessionRefresher = refresher,
        )
        assertNull(p.current())
        assertTrue(p.sessionMissing)

        refresher.state = SteamWebSessionState.ALIVE

        assertEquals(cred, p.current())
        assertFalse(p.sessionMissing)
    }

    /**
     * `scrape()` returns null for a Steam rate-limit, 5xx, interstitial, HTML drift or a bad token regex
     * just as it does for a real logout. With the session cookie still present that stays the signal-only
     * hint: only [SteamCredentialProvider.sessionMissing] is fit to drive a user-facing prompt.
     */
    @Test
    fun a_failed_scrape_with_a_present_session_cookie_is_signal_only() = runTest {
        val stale = fakeSteamCredential().copy(expiresAt = Instant.fromEpochSeconds(1_000_000_000L))
        val p = SteamCredentialProvider(
            vault = FakeCredentialVault(steamCredential = stale),
            scraper = FakeSteamSessionScraper(result = null),
            clock = clock,
            sessionRefresher = FakeSteamSessionRefresher(state = SteamWebSessionState.ALIVE),
        )

        assertNull(p.current())
        assertTrue(p.lastRefreshFailedLoggedOut, "the legacy signal-only flag still fires")
        assertFalse(p.sessionMissing, "no cookie evidence → no blocking verdict")
    }

    /**
     * A deleted cookie is the corroborated logout — and it must cost NOTHING. Neither call can succeed:
     * the refresher renews a live session (Steam answers a renew for a dead one with `InvalidParam`, even
     * with a valid durable refresh cookie) and the scrape reads a page only served to a logged-in session.
     * Retrying them on every wake was pointless traffic against Steam.
     */
    @Test
    fun a_deleted_session_cookie_sets_sessionMissing_without_spending_a_request() = runTest {
        val stale = fakeSteamCredential().copy(expiresAt = Instant.fromEpochSeconds(1_000_000_000L))
        val scraper = FakeSteamSessionScraper(result = null)
        val refresher = FakeSteamSessionRefresher(state = SteamWebSessionState.GONE)
        val p = SteamCredentialProvider(
            vault = FakeCredentialVault(steamCredential = stale),
            scraper = scraper,
            clock = clock,
            sessionRefresher = refresher,
        )

        assertNull(p.current())
        assertTrue(p.lastRefreshFailedLoggedOut)
        assertTrue(p.sessionMissing)
        assertEquals(0, refresher.refreshCalls, "no session to renew — the re-mint request is pure waste")
        assertEquals(0, scraper.scrapeCalls, "no session — the scrape can only ever come back empty")
    }

    /** Same on the reactive 401 path: forcing a refresh with no session cookie must not hit the network. */
    @Test
    fun forceRefresh_spends_no_request_when_the_session_cookie_is_gone() = runTest {
        val scraper = FakeSteamSessionScraper()
        val refresher = FakeSteamSessionRefresher(state = SteamWebSessionState.GONE)
        val p = SteamCredentialProvider(
            vault = FakeCredentialVault(steamCredential = fakeSteamCredential()),
            scraper = scraper,
            clock = clock,
            sessionRefresher = refresher,
        )

        assertNull(p.forceRefresh())
        assertTrue(p.sessionMissing)
        assertEquals(0, refresher.refreshCalls)
        assertEquals(0, scraper.scrapeCalls)
    }

    /** The keep-alive runs whenever the session is due for renewal — that is the case it exists for. */
    @Test
    fun the_session_is_renewed_when_it_is_due_for_renewal() = runTest {
        val stale = fakeSteamCredential().copy(expiresAt = Instant.fromEpochSeconds(1_000_000_000L))
        val fresh = fakeSteamCredential(token = "fresh-token")
        val scraper = FakeSteamSessionScraper(result = fresh)
        val refresher = FakeSteamSessionRefresher(state = SteamWebSessionState.NEEDS_REFRESH)
        val p = SteamCredentialProvider(
            vault = FakeCredentialVault(steamCredential = stale),
            scraper = scraper,
            clock = clock,
            sessionRefresher = refresher,
        )

        assertEquals(fresh, p.current())
        assertEquals(1, refresher.refreshCalls)
        assertEquals(1, scraper.scrapeCalls)
        assertFalse(p.sessionMissing)
    }

    /** …and does NOT run for a session with plenty of life left: the re-mint would be a no-op anyway. */
    @Test
    fun a_session_with_life_left_is_not_renewed_on_the_way_to_a_scrape() = runTest {
        val stale = fakeSteamCredential().copy(expiresAt = Instant.fromEpochSeconds(1_000_000_000L))
        val fresh = fakeSteamCredential(token = "fresh-token")
        val scraper = FakeSteamSessionScraper(result = fresh)
        val refresher = FakeSteamSessionRefresher(state = SteamWebSessionState.ALIVE)
        val p = SteamCredentialProvider(
            vault = FakeCredentialVault(steamCredential = stale),
            scraper = scraper,
            clock = clock,
            sessionRefresher = refresher,
        )

        assertEquals(fresh, p.current())
        assertEquals(0, refresher.refreshCalls, "an ALIVE session needs no re-mint")
        assertEquals(1, scraper.scrapeCalls)
    }

    // ---- the keep-alive now runs on the SESSION's clock -------------------------------------------

    /**
     * The regression that loses sessions outright: with a still-fresh cached credential nothing used to
     * consult the session at all, so a session entering its renewal window was never renewed unless the
     * credential happened to go stale at the same time. Once it expires it cannot be renewed at all.
     */
    @Test
    fun a_session_due_for_renewal_is_renewed_even_though_the_cached_credential_is_fresh() = runTest {
        val cred = fakeSteamCredential()
        val scraper = FakeSteamSessionScraper(result = cred)
        val refresher = FakeSteamSessionRefresher(state = SteamWebSessionState.NEEDS_REFRESH)
        val p = SteamCredentialProvider(
            vault = FakeCredentialVault(steamCredential = cred),
            scraper = scraper,
            clock = clock,
            sessionRefresher = refresher,
        )

        assertEquals(cred, p.current(), "the fresh cached credential is still returned")
        assertEquals(1, refresher.refreshCalls, "the session must be renewed on its own clock")
        assertEquals(0, scraper.scrapeCalls, "renewing the session must not force a re-scrape")
        assertFalse(p.sessionMissing)
    }

    /** A failed renewal inside the window is retried on the next call, not latched. */
    @Test
    fun a_failed_renewal_is_retried_while_the_session_is_still_in_its_window() = runTest {
        val cred = fakeSteamCredential()
        val refresher = FakeSteamSessionRefresher(
            failWith = RuntimeException("re-mint failed"),
            state = SteamWebSessionState.NEEDS_REFRESH,
        )
        val p = SteamCredentialProvider(
            vault = FakeCredentialVault(steamCredential = cred),
            scraper = FakeSteamSessionScraper(result = cred),
            clock = clock,
            sessionRefresher = refresher,
        )

        p.current()
        p.current()

        assertEquals(2, refresher.refreshCalls, "each cycle in the window is a fresh chance")
        assertFalse(p.sessionMissing, "a session still inside its window is not reported as gone")
    }

    /** Once the session has expired there is nothing left to renew: report it and spend no request. */
    @Test
    fun an_expired_session_is_reported_gone_without_a_renewal_attempt() = runTest {
        val cred = fakeSteamCredential()
        val scraper = FakeSteamSessionScraper(result = cred)
        val refresher = FakeSteamSessionRefresher(state = SteamWebSessionState.GONE)
        val p = SteamCredentialProvider(
            vault = FakeCredentialVault(steamCredential = cred),
            scraper = scraper,
            clock = clock,
            sessionRefresher = refresher,
        )

        assertNull(p.current())
        assertTrue(p.sessionMissing)
        assertEquals(0, refresher.refreshCalls)
        assertEquals(0, scraper.scrapeCalls)
    }

    /**
     * A mint redeems whichever account the platform's durable "remember me" credential names, which is not
     * necessarily the one the caller is entitled to act as. A session minted for somebody else is not
     * recovery: nothing is cached and the missing-session flag stays set, so the host keeps prompting instead
     * of the client quietly re-establishing an account the user is signing out of.
     */
    @Test
    fun a_mint_that_restores_another_account_is_not_recovery() = runTest {
        val linked = SteamId("76561198000000001")
        val somebodyElse = SteamId("76561198000000099")
        val refresher = FakeSteamSessionRefresher(
            state = SteamWebSessionState.GONE,
            mintRestoresSession = true,
            cookieSteamId = somebodyElse,
        )
        val p = SteamCredentialProvider(
            vault = FakeCredentialVault(),
            scraper = FakeSteamSessionScraper(),
            clock = clock,
            sessionRefresher = refresher,
        )
        // Establish the missing-session flag the mint is supposed to clear.
        assertNull(p.current())
        assertTrue(p.sessionMissing)

        assertFalse(p.mintSession(linked), "somebody else's session is not the caller's session")
        assertTrue(p.sessionMissing, "the prompt must stay up")
        assertEquals(listOf(true), refresher.forcedCalls, "the handshake itself is still attempted exactly once")
    }

    /** …while a mint that brings back the expected account (or with no expectation) is recovery, as before. */
    @Test
    fun a_mint_that_restores_the_expected_account_is_recovery() = runTest {
        val linked = SteamId("76561198000000001")
        val refresher = FakeSteamSessionRefresher(
            state = SteamWebSessionState.GONE,
            mintRestoresSession = true,
            cookieSteamId = linked,
        )
        val p = SteamCredentialProvider(
            vault = FakeCredentialVault(),
            scraper = FakeSteamSessionScraper(),
            clock = clock,
            sessionRefresher = refresher,
        )
        assertNull(p.current())

        assertTrue(p.mintSession(linked))
        assertFalse(p.sessionMissing)
    }
}
