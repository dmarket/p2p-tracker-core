package com.dmarket.p2p.sdk

import com.dmarket.p2p.sdk.health.HealthTracker
import com.dmarket.p2p.sdk.health.ProblemCode
import com.dmarket.p2p.tracker.loop.TOKEN_EXPIRING_DEFER_REASON
import com.dmarket.p2p.tracker.model.LifecycleEvent
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.runtime.HeadlessTrackerState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class HealthTrackerTest {
    private val clock = TestClock()
    private val tracker = HealthTracker(clock, version = "1.2.3")
    private fun state(hasKey: Boolean = true, tokenExpiresIn: Duration? = 24.hours, readySince: Instant? = null) = HeadlessTrackerState(
        hasTradingApiKey = hasKey,
        steamTokenSteamId = tokenExpiresIn?.let { SteamId(STEAM_ID) },
        steamTokenExpiresAt = tokenExpiresIn?.let { T0 + it },
        linkedSteamId = SteamId(STEAM_ID),
        readySince = readySince,
    )

    private fun codes(state: HeadlessTrackerState = state()) = tracker.snapshot(state).problems.map { it.code }

    @Test
    fun a_fresh_start_reports_both_missing_secrets_and_is_down() {
        val health = tracker.snapshot(state(hasKey = false, tokenExpiresIn = null))
        assertEquals(listOf(ProblemCode.DMARKET_KEY_MISSING, ProblemCode.STEAM_TOKEN_MISSING), health.problems.map { it.code })
        assertEquals("down", health.status)
        assertEquals("1.2.3", health.version)
    }

    @Test
    fun a_token_with_less_than_four_hours_left_degrades_and_an_expired_one_blocks() {
        val expiring = tracker.snapshot(state(tokenExpiresIn = 3.hours))
        assertEquals(listOf(ProblemCode.STEAM_TOKEN_EXPIRING), expiring.problems.map { it.code })
        assertEquals("degraded", expiring.status)
        assertEquals((T0 + 3.hours).toString(), expiring.steamTokenExpiresAt)

        assertEquals(listOf(ProblemCode.STEAM_TOKEN_EXPIRED), codes(state(tokenExpiresIn = (-1).minutes)))
    }

    @Test
    fun all_clear_is_ok() {
        val health = tracker.snapshot(state())
        assertEquals("ok", health.status)
        assertTrue(health.problems.isEmpty())
    }

    @Test
    fun a_wrong_account_lasts_until_a_heartbeat_comes_back_without_it() = runTest {
        tracker.onEvent(LifecycleEvent.HeartbeatSent(60, 0, 0))
        tracker.onEvent(LifecycleEvent.LinkedSteamIdMismatch(OTHER_STEAM_ID, STEAM_ID))
        assertEquals(listOf(ProblemCode.STEAM_WRONG_ACCOUNT), codes())

        tracker.onEvent(LifecycleEvent.HeartbeatSent(60, 0, 0))
        assertTrue(codes().isEmpty())
    }

    @Test
    fun a_refused_push_is_a_wrong_account_until_a_token_is_held() {
        tracker.tokenRefused(SteamId(OTHER_STEAM_ID), SteamId(STEAM_ID))
        val problem = tracker.snapshot(state()).problems.single()
        assertEquals(ProblemCode.STEAM_WRONG_ACCOUNT, problem.code)
        assertTrue(OTHER_STEAM_ID in problem.message && STEAM_ID in problem.message, problem.message)

        tracker.tokenHeld()
        assertTrue(codes().isEmpty())
    }

    @Test
    fun a_failed_directive_names_its_deal_until_that_deal_succeeds() = runTest {
        tracker.onEvent(
            LifecycleEvent.DirectiveExecuted("create_offer", "FAILED", dealId = "deal-1", error = "Steam create returned HTTP 500: (26)"),
        )
        val problem = tracker.snapshot(state()).problems.single()
        assertEquals(ProblemCode.DIRECTIVE_FAILED, problem.code)
        assertEquals(listOf("deal-1"), problem.affectedDeals.map { it.dealId })
        assertTrue("(26)" in problem.message)

        tracker.onEvent(LifecycleEvent.DirectiveExecuted("create_offer", "NEEDS_CONFIRMATION", "555", dealId = "deal-1"))
        assertTrue(codes().isEmpty())
    }

    @Test
    fun steam_read_failures_are_classified_and_clear_on_a_clean_cycle() = runTest {
        val reasons = mapOf(
            "HttpStatusException: HTTP 429 on GET https://api.steampowered.com/x" to ProblemCode.STEAM_RATE_LIMITED,
            "HttpStatusException: HTTP 403 on GET https://api.steampowered.com/x" to ProblemCode.STEAM_SESSION_REJECTED,
            "IOException: connection reset" to ProblemCode.STEAM_UNREACHABLE,
        )
        for ((reason, code) in reasons) {
            tracker.onEvent(LifecycleEvent.CycleStarted)
            tracker.onEvent(LifecycleEvent.SteamReadFailed("offer", reason))
            tracker.onEvent(LifecycleEvent.CycleCompleted(0, 0, 0, 1))
            assertEquals(listOf(code), codes(), reason)
        }
        tracker.onEvent(LifecycleEvent.CycleStarted)
        tracker.onEvent(LifecycleEvent.CycleCompleted(0, 0, 0, 1))
        assertTrue(codes().isEmpty())
    }

    @Test
    fun a_create_held_back_for_the_token_is_not_reported_as_steam_throttling() = runTest {
        tracker.onEvent(LifecycleEvent.CycleStarted)
        tracker.onEvent(LifecycleEvent.SteamWriteDeferred("create_offer", "dir-1", TOKEN_EXPIRING_DEFER_REASON, dealId = "deal-1"))
        tracker.onEvent(LifecycleEvent.SteamWriteDeferred("create_offer", "dir-2", "partner cooling down", dealId = "deal-2"))
        val problem = tracker.snapshot(state()).problems.single()
        assertEquals(ProblemCode.STEAM_RATE_LIMITED, problem.code)
        assertEquals(listOf("deal-2"), problem.affectedDeals.map { it.dealId })
    }

    @Test
    fun a_problem_keeps_the_time_it_was_first_seen() = runTest {
        tracker.onEvent(LifecycleEvent.MarketplaceServerError("heartbeat", 502))
        val first = tracker.snapshot(state()).problems.single().since
        clock.advance(5.minutes)
        tracker.onEvent(LifecycleEvent.MarketplaceServerError("heartbeat", 502))
        assertEquals(first, tracker.snapshot(state()).problems.single().since)
        assertEquals(T0.toString(), first)
    }

    @Test
    fun the_last_heartbeat_time_is_reported() = runTest {
        clock.advance(2.minutes)
        tracker.onEvent(LifecycleEvent.HeartbeatSent(60, 0, 0))
        assertEquals((T0 + 2.minutes).toString(), tracker.snapshot(state()).lastHeartbeatAt)
    }

    @Test
    fun a_heartbeat_that_stops_coming_back_is_reported_before_the_loop_gives_up() = runTest {
        val ready = state(readySince = T0)
        clock.advance(4.minutes)
        assertTrue(codes(ready).isEmpty(), "a first heartbeat may take a moment")
        clock.advance(2.minutes)
        assertEquals(listOf(ProblemCode.DMARKET_UNREACHABLE), codes(ready))

        tracker.onEvent(LifecycleEvent.HeartbeatSent(100, 0, 0))
        assertTrue(codes(ready).isEmpty())
        clock.advance(6.minutes)
        assertEquals(listOf(ProblemCode.DMARKET_UNREACHABLE), codes(ready), "nor may a later one stop for long")

        assertTrue(codes(state(readySince = null)).isEmpty(), "an SDK that may not act owes no heartbeat")
    }
}
