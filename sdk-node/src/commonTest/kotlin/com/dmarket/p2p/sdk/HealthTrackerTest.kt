package com.dmarket.p2p.sdk

import com.dmarket.p2p.sdk.health.HealthTracker
import com.dmarket.p2p.sdk.health.ProblemCode
import com.dmarket.p2p.sdk.health.Severity
import com.dmarket.p2p.tracker.loop.TOKEN_EXPIRING_DEFER_REASON
import com.dmarket.p2p.tracker.model.DealId
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
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class HealthTrackerTest {
    private val clock = TestClock()
    private val tracker = HealthTracker(clock, version = "1.2.3")
    private fun state(
        hasKey: Boolean = true,
        tokenExpiresIn: Duration? = 24.hours,
        readySince: Instant? = null,
        tracked: List<String> = emptyList(),
        clockOffset: Duration? = null,
        steamNotLinked: Boolean = false,
    ) = HeadlessTrackerState(
        hasTradingApiKey = hasKey,
        steamTokenSteamId = tokenExpiresIn?.let { SteamId(STEAM_ID) },
        steamTokenExpiresAt = tokenExpiresIn?.let { T0 + it },
        linkedSteamId = SteamId(STEAM_ID),
        readySince = readySince,
        trackedDealIds = tracked.map(::DealId),
        clockOffset = clockOffset,
        steamNotLinked = steamNotLinked,
    )

    private fun codes(state: HeadlessTrackerState = state()) = tracker.snapshot(state).problems.map { it.code }

    @Test
    fun a_fresh_start_reports_both_missing_secrets_and_is_down() {
        val health = tracker.snapshot(state(hasKey = false, tokenExpiresIn = null))
        assertEquals(listOf(ProblemCode.DMARKET_KEY_MISSING, ProblemCode.STEAM_TOKEN_MISSING), health.problems.map { it.code })
        assertEquals(listOf("down", "degraded"), health.problems.map { it.severity })
        assertEquals("down", health.status)
        assertTrue(health.isDown)
        assertEquals("1.2.3", health.version)
    }

    @Test
    fun down_is_only_what_stops_the_heartbeat_and_info_is_only_a_scheduled_update() {
        val down = ProblemCode.entries.filter { it.severity == Severity.DOWN }
        assertEquals(
            setOf(
                ProblemCode.DMARKET_KEY_MISSING,
                ProblemCode.DMARKET_AUTH_FAILED,
                ProblemCode.CLOCK_SKEW,
                ProblemCode.DMARKET_UNREACHABLE,
                ProblemCode.STEAM_NOT_LINKED,
            ),
            down.toSet(),
        )
        assertEquals(listOf(ProblemCode.SDK_UPDATE_DUE), ProblemCode.entries.filter { it.severity == Severity.INFO })
        assertEquals(Severity.DEGRADED, ProblemCode.SDK_UPDATE_REQUIRED.severity, "below the minimum the SDK still serves its deals")
    }

    @Test
    fun status_is_the_worst_open_severity() = runTest {
        tracker.onEvent(LifecycleEvent.DirectiveExecuted("create_offer", "FAILED", dealId = "deal-1", error = "(26)"))
        assertEquals("degraded", tracker.snapshot(state()).status)
        tracker.onEvent(LifecycleEvent.MarketplaceServerError("heartbeat", 502))
        val health = tracker.snapshot(state())
        assertEquals("down", health.status)
        assertEquals(listOf("down", "degraded"), health.problems.map { it.severity })
    }

    @Test
    fun a_token_with_less_than_four_hours_left_or_expired_degrades() {
        val expiring = tracker.snapshot(state(tokenExpiresIn = 3.hours))
        assertEquals(listOf(ProblemCode.STEAM_TOKEN_EXPIRING), expiring.problems.map { it.code })
        assertEquals("degraded", expiring.status)
        assertEquals((T0 + 3.hours).toString(), expiring.steamTokenExpiresAt)

        val expired = tracker.snapshot(state(tokenExpiresIn = (-1).minutes))
        assertEquals(listOf(ProblemCode.STEAM_TOKEN_EXPIRED), expired.problems.map { it.code })
        assertEquals("degraded", expired.status, "the SDK still heartbeats without a usable token")
    }

    @Test
    fun an_expiring_token_names_the_tracked_deals_and_the_creates_held_back_for_it() = runTest {
        tracker.onEvent(LifecycleEvent.CycleStarted)
        tracker.onEvent(LifecycleEvent.SteamWriteDeferred("create_offer", "dir-9", TOKEN_EXPIRING_DEFER_REASON, dealId = "deal-9"))
        tracker.onEvent(LifecycleEvent.CycleCompleted(0, 0, 0, 2))
        val problem = tracker.snapshot(state(tokenExpiresIn = 20.minutes, tracked = listOf("deal-1", "deal-9"))).problems.single()
        assertEquals(ProblemCode.STEAM_TOKEN_EXPIRING, problem.code)
        assertEquals(listOf("deal-1", "deal-9"), problem.affectedDeals.map { it.dealId })

        tracker.onEvent(LifecycleEvent.CycleStarted)
        tracker.onEvent(LifecycleEvent.CycleCompleted(0, 0, 0, 0))
        val later = tracker.snapshot(state(tokenExpiresIn = 20.minutes)).problems.single()
        assertTrue(later.affectedDeals.isEmpty(), "a cycle with nothing held back and nothing tracked names no deal: $later")
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

    @Test
    fun a_skewed_clock_is_reported_instead_of_a_refused_key() = runTest {
        tracker.onEvent(LifecycleEvent.ReLoginNeeded("marketplace"))
        assertEquals(listOf(ProblemCode.DMARKET_AUTH_FAILED), codes(state(clockOffset = 59.seconds)))

        val health = tracker.snapshot(state(clockOffset = (-3).minutes, readySince = T0))
        assertEquals(listOf(ProblemCode.CLOCK_SKEW), health.problems.map { it.code })
        assertTrue("180s" in health.problems.single().message, health.problems.single().message)
        assertEquals("down", health.status)
        clock.advance(10.minutes)
        assertEquals(listOf(ProblemCode.CLOCK_SKEW), codes(state(clockOffset = 3.minutes, readySince = T0)), "nor read as unreachable")
    }

    @Test
    fun an_unlinked_account_is_reported_instead_of_an_unreachable_dmarket() = runTest {
        tracker.onEvent(LifecycleEvent.MarketplaceServerError("heartbeat", 403))
        val health = tracker.snapshot(state(steamNotLinked = true, tracked = listOf("deal-1")))
        assertEquals(listOf(ProblemCode.STEAM_NOT_LINKED), health.problems.map { it.code })
        assertEquals(listOf("deal-1"), health.problems.single().affectedDeals.map { it.dealId })
        assertEquals("down", health.status)
        clock.advance(10.minutes)
        assertEquals(listOf(ProblemCode.STEAM_NOT_LINKED), codes(state(steamNotLinked = true, readySince = T0)))
    }
}
