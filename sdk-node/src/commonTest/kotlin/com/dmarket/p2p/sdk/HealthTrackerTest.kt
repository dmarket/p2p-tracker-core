package com.dmarket.p2p.sdk

import com.dmarket.p2p.sdk.health.HealthTracker
import com.dmarket.p2p.sdk.health.ProblemCode
import com.dmarket.p2p.sdk.health.Severity
import com.dmarket.p2p.tracker.loop.OFFER_WRITES_DISABLED
import com.dmarket.p2p.tracker.loop.STEAM_SESSION_MISSING_DROP_REASON
import com.dmarket.p2p.tracker.loop.TOKEN_EXPIRING_DEFER_REASON
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.LifecycleEvent
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.runtime.HeadlessTrackerState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
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
        updateRequired: Boolean = false,
        updateDueTime: Instant? = null,
        deadlines: Map<String, Instant> = emptyMap(),
    ) = HeadlessTrackerState(
        hasTradingApiKey = hasKey,
        steamTokenSteamId = tokenExpiresIn?.let { SteamId(STEAM_ID) },
        steamTokenExpiresAt = tokenExpiresIn?.let { T0 + it },
        linkedSteamId = SteamId(STEAM_ID),
        readySince = readySince,
        trackedDealIds = tracked.map(::DealId),
        clockOffset = clockOffset,
        steamNotLinked = steamNotLinked,
        updateRequired = updateRequired,
        updateDueTime = updateDueTime,
        dealDeadlines = deadlines.mapKeys { DealId(it.key) },
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
    fun an_affected_deal_carries_its_deadline_when_the_heartbeat_gave_one() = runTest {
        val deadline = T0 + 2.hours
        tracker.onEvent(LifecycleEvent.DirectiveExecuted("create_offer", "FAILED", dealId = "deal-1", error = "(26)"))
        tracker.onEvent(LifecycleEvent.DirectiveExecuted("create_offer", "FAILED", dealId = "deal-2", error = "(26)"))
        val problem = tracker.snapshot(state(deadlines = mapOf("deal-1" to deadline))).problems.single()
        assertEquals(ProblemCode.DIRECTIVE_FAILED, problem.code)
        assertEquals(listOf("deal-1" to deadline.toString(), "deal-2" to null), problem.affectedDeals.map { it.dealId to it.deadline })
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

    @Test
    fun a_minimum_in_force_degrades_and_a_scheduled_one_is_only_news() {
        val due = tracker.snapshot(state(updateDueTime = T0 + 72.hours))
        val news = due.problems.single()
        assertEquals(ProblemCode.SDK_UPDATE_DUE, news.code)
        assertEquals("info", news.severity)
        assertTrue((T0 + 72.hours).toString() in news.message, news.message)
        assertEquals("ok", due.status, "info only is ok")

        val required = tracker.snapshot(state(updateRequired = true, updateDueTime = T0))
        assertEquals(listOf(ProblemCode.SDK_UPDATE_REQUIRED), required.problems.map { it.code })
        assertEquals("degraded", required.status, "below the minimum the SDK still serves its deals")
        assertTrue(!required.isDown)
    }

    // ---- leased writes this SDK does not run -------------------------------------------------------

    private suspend fun heartbeatWithoutToken(vararg cancels: String) {
        tracker.onEvent(LifecycleEvent.CycleStarted)
        tracker.onEvent(LifecycleEvent.HeartbeatSent(100, 0, cancels.size))
        cancels.forEach {
            tracker.onEvent(LifecycleEvent.DirectiveDropped("cancel_offer", "dir-$it", STEAM_SESSION_MISSING_DROP_REASON, it))
        }
    }

    /** A heartbeat cycle with a usable token: the loop completes it, running whatever cancel the heartbeat carried. */
    private suspend fun heartbeatWithToken() {
        tracker.onEvent(LifecycleEvent.CycleStarted)
        tracker.onEvent(LifecycleEvent.HeartbeatSent(100, 0, 0))
        tracker.onEvent(LifecycleEvent.CycleCompleted(0, 0, 0, 0))
    }

    private suspend fun heartbeatWithWritesOff(vararg cancels: String) {
        tracker.onEvent(LifecycleEvent.HeartbeatSent(100, 0, cancels.size))
        cancels.forEach { tracker.onEvent(LifecycleEvent.DirectiveDropped("cancel_offer", "dir-$it", OFFER_WRITES_DISABLED, it)) }
    }

    private fun tokenMissing() = state(tokenExpiresIn = null)

    private fun tokenHeld() = state(tokenExpiresIn = clock.now() - T0 + 24.hours)

    private fun cancelPending(state: HeadlessTrackerState) = tracker.snapshot(state).problems.singleOrNull {
        it.code ==
            ProblemCode.STEAM_CANCEL_PENDING
    }

    /**
     * Heartbeats every 100 s for [span], each told by [heartbeat] whether it carries a cancel DMarket keeps issuing: five
     * attempts in its first five minutes, then one every 30 minutes, each leased for two minutes. [check] follows each.
     */
    private suspend fun heartbeatsOfAReissuedCancel(
        span: Duration,
        heartbeat: suspend (carried: Boolean) -> Unit,
        check: (at: Duration) -> Unit,
    ) {
        val issues = (0..4).map { it.minutes } + (1..12).map { 5.minutes + 30.minutes * it }
        var at = Duration.ZERO
        while (at <= span) {
            heartbeat(issues.any { at >= it && at < it + 2.minutes })
            check(at)
            clock.advance(100.seconds)
            at += 100.seconds
        }
    }

    @Test
    fun a_cancel_leased_with_no_usable_token_is_reported_by_deal() = runTest {
        heartbeatWithoutToken("deal-1", "deal-2")
        tracker.onEvent(LifecycleEvent.DirectiveDropped("create_offer", "dir-3", STEAM_SESSION_MISSING_DROP_REASON, "deal-3"))

        val problem = assertNotNull(cancelPending(state(tokenExpiresIn = (-1).hours)))
        assertEquals("degraded", problem.severity)
        assertEquals(listOf("deal-1", "deal-2"), problem.affectedDeals.map { it.dealId }, "a create held back is not a waiting cancel")
        assertTrue("PUT /v1/steam/session" in problem.message, problem.message)
    }

    @Test
    fun a_waiting_cancel_stands_through_heartbeats_that_no_longer_carry_it_and_keeps_its_since() = runTest {
        heartbeatWithoutToken("deal-1")
        val first = assertNotNull(cancelPending(tokenMissing()))
        repeat(12) {
            clock.advance(10.minutes)
            heartbeatWithoutToken()
            val problem = assertNotNull(cancelPending(tokenMissing()), "neither tracked nor leased, its offer may still be open")
            assertEquals(listOf("deal-1"), problem.affectedDeals.map { it.dealId })
            assertEquals(first.since, problem.since)
        }
    }

    @Test
    fun a_token_held_before_the_cancel_is_issued_again_changes_the_message_and_not_the_problem() = runTest {
        heartbeatWithoutToken("deal-1")
        val waiting = assertNotNull(cancelPending(tokenMissing()))

        clock.advance(5.minutes)
        heartbeatWithToken()
        val held = assertNotNull(cancelPending(tokenHeld()), "a heartbeat with a token runs only the cancels it carries")
        assertEquals(waiting.since, held.since)
        assertEquals(listOf("deal-1"), held.affectedDeals.map { it.dealId })
        assertTrue("issues it again" in held.message && "PUT /v1/steam/session" !in held.message, held.message)
        assertTrue("by hand" !in held.message, held.message)

        clock.advance(30.minutes)
        heartbeatWithToken()
        assertTrue("by hand" !in assertNotNull(cancelPending(tokenHeld())).message, "35 minutes since its issue: still between two")
        clock.advance(1.seconds)
        val overdue = assertNotNull(cancelPending(tokenHeld()))
        assertTrue("35 minutes" in overdue.message && "cancelled by hand on Steam" in overdue.message, overdue.message)
        assertEquals(waiting.since, overdue.since)
    }

    @Test
    fun a_cancel_no_longer_issued_is_overdue_as_soon_as_a_token_is_held() = runTest {
        heartbeatWithoutToken("deal-1")
        clock.advance(2.hours)
        heartbeatWithToken()
        val problem = assertNotNull(cancelPending(tokenHeld()))
        assertTrue("cancelled by hand on Steam" in problem.message, problem.message)
    }

    @Test
    fun a_cancel_issued_again_with_a_token_clears_once_it_runs_whatever_steam_answers() = runTest {
        heartbeatWithoutToken("deal-1", "deal-2")
        clock.advance(30.minutes)
        tracker.onEvent(LifecycleEvent.CycleStarted)
        tracker.onEvent(LifecycleEvent.HeartbeatSent(100, 0, 2))
        tracker.onEvent(LifecycleEvent.DirectiveExecuted("cancel_offer", "SUCCESS", dealId = "deal-1"))
        assertEquals(listOf("deal-2"), cancelPending(tokenHeld())?.affectedDeals?.map { it.dealId })

        tracker.onEvent(
            LifecycleEvent.DirectiveExecuted("cancel_offer", "FAILED", dealId = "deal-2", error = "Steam cancel refused with EResult 16"),
        )
        tracker.onEvent(LifecycleEvent.CycleCompleted(2, 0, 0, 0))
        val codes = codes(tokenHeld())
        assertTrue(ProblemCode.STEAM_CANCEL_PENDING !in codes, "a cancel that ran and failed is DIRECTIVE_FAILED's: $codes")
        assertTrue(ProblemCode.DIRECTIVE_FAILED in codes)
    }

    @Test
    fun a_waiting_cancel_lapses_fourteen_days_after_its_first_issue() = runTest {
        heartbeatWithoutToken("deal-1")
        clock.advance(13.days)
        heartbeatWithoutToken("deal-1")
        clock.advance(1.days - 1.seconds)
        assertNotNull(cancelPending(tokenMissing()), "a later issue does not move the offer's own lapse")
        clock.advance(1.seconds)
        assertNull(cancelPending(tokenMissing()), "Steam has let the offer lapse")
        heartbeatWithoutToken()
        assertNull(cancelPending(tokenMissing()), "and once gone it does not come back")
    }

    @Test
    fun a_cancel_issued_every_half_hour_does_not_blink_out_between_issues() = runTest {
        val since = mutableSetOf<String>()
        heartbeatsOfAReissuedCancel(3.hours, { carried ->
            if (carried) heartbeatWithoutToken("deal-1") else heartbeatWithoutToken()
        }) { at ->
            val problem = assertNotNull(cancelPending(tokenMissing()), "at $at")
            assertEquals(listOf("deal-1"), problem.affectedDeals.map { it.dealId }, "at $at")
            since += problem.since
        }
        assertEquals(setOf(T0.toString()), since)
    }

    @Test
    fun a_cancel_leased_to_an_sdk_without_steam_writes_does_not_blink_out_between_issues() = runTest {
        val since = mutableSetOf<String>()
        heartbeatsOfAReissuedCancel(3.hours, { carried ->
            if (carried) heartbeatWithWritesOff("deal-2") else heartbeatWithWritesOff()
        }) { at ->
            val problem = tracker.snapshot(tokenHeld()).problems.single()
            assertEquals(ProblemCode.STEAM_WRITES_DISABLED, problem.code, "at $at")
            assertEquals(listOf("deal-2"), problem.affectedDeals.map { it.dealId }, "at $at")
            since += problem.since
        }
        assertEquals(setOf(T0.toString()), since)
    }

    @Test
    fun writes_leased_to_an_sdk_without_steam_writes_are_their_own_problem_until_no_longer_leased() = runTest {
        tracker.onEvent(LifecycleEvent.HeartbeatSent(100, 1, 2))
        tracker.onEvent(LifecycleEvent.DirectiveDropped("create_offer", "dir-1", OFFER_WRITES_DISABLED, "deal-1"))
        tracker.onEvent(LifecycleEvent.DirectiveDropped("cancel_offer", "dir-2", OFFER_WRITES_DISABLED, "deal-2"))
        tracker.onEvent(LifecycleEvent.DirectiveDropped("create_offer", "dir-3", "refused: malformed"))
        val problem = tracker.snapshot(state()).problems.single()
        assertEquals(ProblemCode.STEAM_WRITES_DISABLED, problem.code)
        assertEquals("degraded", problem.severity)
        assertEquals(listOf("deal-1", "deal-2"), problem.affectedDeals.map { it.dealId })
        assertTrue("steamWrites: false" in problem.message, problem.message)

        // A create is leased again every heartbeat while its window lasts: it goes a heartbeat after its last lease.
        clock.advance(100.seconds)
        tracker.onEvent(LifecycleEvent.HeartbeatSent(100, 1, 1))
        tracker.onEvent(LifecycleEvent.DirectiveDropped("create_offer", "dir-1", OFFER_WRITES_DISABLED, "deal-1"))
        assertEquals(listOf("deal-1", "deal-2"), tracker.snapshot(state()).problems.single().affectedDeals.map { it.dealId })
        clock.advance(100.seconds)
        tracker.onEvent(LifecycleEvent.HeartbeatSent(100, 1, 0))
        assertEquals(listOf("deal-1", "deal-2"), tracker.snapshot(state()).problems.single().affectedDeals.map { it.dealId })
        clock.advance(100.seconds)
        tracker.onEvent(LifecycleEvent.HeartbeatSent(100, 1, 0))
        assertEquals(
            listOf("deal-2"),
            tracker.snapshot(state()).problems.single().affectedDeals.map { it.dealId },
            "a cancel is issued only now and then: it stands until its next issue is due",
        )

        // A cancel stands until its next issue is overdue: 35 minutes after its last lease.
        clock.advance(35.minutes - 300.seconds)
        assertEquals(listOf("deal-2"), tracker.snapshot(state()).problems.single().affectedDeals.map { it.dealId })
        clock.advance(1.seconds)
        assertEquals(emptyList(), codes())
    }
}
