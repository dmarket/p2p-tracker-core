package com.dmarket.p2p.tracker.policy

import com.dmarket.p2p.tracker.config.SteamWriteConfig
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.support.T0
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class SteamWriteThrottleTest {

    private val limits = SteamWriteConfig()
    private val alice = SteamId("76561199497281579")
    private val bob = SteamId("76561198077327619")

    /** Seeded so the jittered cooldown is reproducible; the assertions below bound it, never pin the draw. */
    private fun random() = Random(1234)

    // ---- classify -----------------------------------------------------------------------------------

    /** The verbatim Steam refusal from the session log that motivated the throttle. */
    private val liveRefusal = "Steam create returned HTTP 500: {\"strError\":\"You have sent too many trade " +
        "offers, or have too many outstanding trade offers with a-partner. Please cancel some before sending more.\"}"

    @Test
    fun names_the_cause_of_every_refusal_seen_in_the_wild() {
        listOf(
            // The reported failure. Steam names the partner, so it is that partner's cap and no one else's.
            liveRefusal to SteamCreateFailureCause.COUNTERPARTY_OFFER_LIMIT,
            // The same refusal with no partner named: the account-wide cap. Every partner is refused now.
            "Steam create returned HTTP 500: {\"strError\":\"You have sent too many trade offers.\"}"
                to SteamCreateFailureCause.OUTGOING_OFFER_LIMIT,
            "You have SENT TOO MANY trade offers" to SteamCreateFailureCause.OUTGOING_OFFER_LIMIT,
            // Request throttling is not a cap on open offers and must never be dressed up as one: telling
            // this user to cancel offers is advice that cannot help them.
            "Steam create returned HTTP 429" to SteamCreateFailureCause.REQUEST_RATE_LIMITED,
            "Failed to fetch" to SteamCreateFailureCause.TRANSPORT,
            "TypeError: NetworkError when attempting to fetch resource" to SteamCreateFailureCause.TRANSPORT,
            "request timed out" to SteamCreateFailureCause.TRANSPORT,
            "no Steam session cookie" to SteamCreateFailureCause.OTHER,
            "Steam create response missing tradeofferid" to SteamCreateFailureCause.OTHER,
            "Steam create returned HTTP 500: {\"strError\":\"There was an error sending your trade offer. (15)\"}"
                to SteamCreateFailureCause.OTHER,
        ).forEach { (error, cause) ->
            assertEquals(cause, SteamWriteThrottle.classifyCause(error, limits), error)
        }
    }

    @Test
    fun an_unreadable_error_names_no_cause() {
        listOf(null, "", "   ").forEach { error ->
            assertEquals(SteamCreateFailureCause.OTHER, SteamWriteThrottle.classifyCause(error, limits), error.toString())
        }
    }

    @Test
    fun every_cause_agrees_with_the_kind_the_throttle_acts_on() {
        // The two vocabularies exist for different jobs (diagnosis vs. what to park) and must not drift:
        // `classify` is defined as the cause read down to its kind, and this pins that relationship.
        listOf(liveRefusal, "You have sent too many trade offers", "HTTP 429", "Failed to fetch", "whatever")
            .forEach { error ->
                assertEquals(
                    SteamWriteThrottle.classifyCause(error, limits).kind,
                    SteamWriteThrottle.classify(error, limits),
                    error,
                )
            }
        assertEquals(SteamWriteFailureKind.RATE_LIMITED, SteamWriteThrottle.classify(liveRefusal, limits))
        assertEquals(
            SteamWriteFailureKind.RATE_LIMITED_SURFACE,
            SteamWriteThrottle.classify("You have sent too many trade offers", limits),
        )
    }

    @Test
    fun reads_a_rate_limit_that_also_mentions_a_transport_word_as_rate_limited() {
        // Rate-limit markers are checked first on purpose: Steam's refusal text is the stronger signal.
        val both = "connection: you have sent too many trade offers"
        assertEquals(SteamCreateFailureCause.OUTGOING_OFFER_LIMIT, SteamWriteThrottle.classifyCause(both, limits))
    }

    @Test
    fun the_counterparty_phrase_wins_over_the_generic_wording_it_contains() {
        // Steam's per-partner refusal carries BOTH: "…sent too many trade offers, or have too many
        // outstanding trade offers with <persona>…". Reading the generic half first would park the whole
        // surface for what is one partner's cap.
        assertEquals(SteamCreateFailureCause.COUNTERPARTY_OFFER_LIMIT, SteamWriteThrottle.classifyCause(liveRefusal, limits))
        assertEquals(SteamWriteFailureKind.RATE_LIMITED, SteamWriteThrottle.classify(liveRefusal, limits))
    }

    @Test
    fun a_blank_counterparty_marker_disables_the_split_instead_of_matching_everything() {
        // A blank marker is a substring of every string, so an unguarded check would call every failure —
        // transport included — a counterparty cap. Blank must fall through to the account-wide reading.
        val blanked = SteamWriteConfig(counterpartyLimitMarker = "  ")
        assertEquals(SteamCreateFailureCause.OUTGOING_OFFER_LIMIT, SteamWriteThrottle.classifyCause(liveRefusal, blanked))
        assertEquals(SteamCreateFailureCause.TRANSPORT, SteamWriteThrottle.classifyCause("Failed to fetch", blanked))
    }

    @Test
    fun a_request_rate_marker_the_rate_limit_set_does_not_cover_has_no_effect() {
        // `requestRateLimitMarkers` only refines a text already read as a rate limit — it cannot promote one.
        val limits = SteamWriteConfig(requestRateLimitMarkers = listOf("slow down"))
        assertEquals(SteamCreateFailureCause.OTHER, SteamWriteThrottle.classifyCause("please slow down", limits))
        assertEquals(SteamCreateFailureCause.OUTGOING_OFFER_LIMIT, SteamWriteThrottle.classifyCause("HTTP 429", limits))
    }

    // ---- gate ---------------------------------------------------------------------------------------

    @Test
    fun an_empty_state_allows_every_partner() {
        assertEquals(WriteGate.Allow, SteamWriteThrottle.gate(SteamWriteThrottleState.EMPTY, alice, T0))
    }

    @Test
    fun a_partner_cooldown_blocks_only_that_partner() {
        val state = SteamWriteThrottleState(partners = mapOf(alice to PartnerCooldown(T0 + 5.minutes, 1)))
        val blocked = assertIs<WriteGate.Blocked>(SteamWriteThrottle.gate(state, alice, T0))
        assertEquals(ThrottleScope.PARTNER, blocked.scope)
        assertEquals(T0 + 5.minutes, blocked.until)
        assertEquals(WriteGate.Allow, SteamWriteThrottle.gate(state, bob, T0))
    }

    @Test
    fun a_cooldown_expires_at_its_deadline_not_after() {
        val state = SteamWriteThrottleState(partners = mapOf(alice to PartnerCooldown(T0 + 5.minutes, 1)))
        assertIs<WriteGate.Blocked>(SteamWriteThrottle.gate(state, alice, T0 + 5.minutes - 1.milliseconds))
        assertEquals(WriteGate.Allow, SteamWriteThrottle.gate(state, alice, T0 + 5.minutes))
    }

    @Test
    fun the_global_block_covers_every_partner_and_is_reported_over_a_partner_one() {
        val state = SteamWriteThrottleState(
            partners = mapOf(alice to PartnerCooldown(T0 + 2.minutes, 1)),
            globalUntil = T0 + 30.minutes,
        )
        listOf(alice, bob).forEach { partner ->
            val blocked = assertIs<WriteGate.Blocked>(SteamWriteThrottle.gate(state, partner, T0))
            assertEquals(ThrottleScope.GLOBAL, blocked.scope)
            assertEquals(T0 + 30.minutes, blocked.until)
        }
    }

    // ---- onFailure ----------------------------------------------------------------------------------

    @Test
    fun a_rate_limit_parks_the_partner_within_the_configured_bounds() {
        val state = SteamWriteThrottle.onFailure(
            SteamWriteThrottleState.EMPTY,
            alice,
            SteamWriteFailureKind.RATE_LIMITED,
            T0,
            limits,
            random(),
        )
        val cooldown = state.partners.getValue(alice)
        assertEquals(1, cooldown.attempt)
        assertTrue(cooldown.until >= T0 + limits.cooldownMin, "cooldown must honour the configured floor")
        assertTrue(cooldown.until <= T0 + limits.cooldownMax, "cooldown must honour the configured cap")
        assertEquals(WriteGate.Allow, SteamWriteThrottle.gate(state, bob, T0), "other partners stay open")
    }

    @Test
    fun repeated_rate_limits_escalate_the_attempt_and_never_dip_below_the_floor() {
        var state = SteamWriteThrottleState.EMPTY
        val random = random()
        // Threshold-crossing arms the global block too; step one partner at a time and read its own rung.
        repeat(6) { round ->
            state = SteamWriteThrottle.onFailure(state, alice, SteamWriteFailureKind.RATE_LIMITED, T0, limits, random)
            val cooldown = state.partners.getValue(alice)
            assertEquals(round + 1, cooldown.attempt)
            assertTrue(cooldown.until >= T0 + limits.cooldownMin)
            assertTrue(cooldown.until <= T0 + limits.cooldownMax)
        }
    }

    @Test
    fun a_transport_failure_parks_nobody_but_still_feeds_the_breaker() {
        var state = SteamWriteThrottleState.EMPTY
        val random = random()
        repeat(limits.globalBreakerThreshold - 1) {
            state = SteamWriteThrottle.onFailure(state, alice, SteamWriteFailureKind.TRANSPORT, T0, limits, random)
        }
        assertTrue(state.partners.isEmpty(), "a transport failure says nothing about a partner's quota")
        assertNull(state.globalUntil)
        assertEquals(limits.globalBreakerThreshold - 1, state.consecutiveFailures)

        state = SteamWriteThrottle.onFailure(state, alice, SteamWriteFailureKind.TRANSPORT, T0, limits, random)
        val blocked = assertIs<WriteGate.Blocked>(SteamWriteThrottle.gate(state, bob, T0))
        assertEquals(ThrottleScope.GLOBAL, blocked.scope)
        assertEquals(0, state.consecutiveFailures, "the streak restarts after arming the breaker")
        assertEquals(1, state.globalAttempt)
    }

    @Test
    fun the_breaker_counts_rate_limits_and_transport_failures_together() {
        var state = SteamWriteThrottleState.EMPTY
        val random = random()
        state = SteamWriteThrottle.onFailure(state, alice, SteamWriteFailureKind.RATE_LIMITED, T0, limits, random)
        state = SteamWriteThrottle.onFailure(state, bob, SteamWriteFailureKind.TRANSPORT, T0, limits, random)
        assertNull(state.globalUntil)
        state = SteamWriteThrottle.onFailure(state, bob, SteamWriteFailureKind.RATE_LIMITED, T0, limits, random)
        assertIs<WriteGate.Blocked>(SteamWriteThrottle.gate(state, SteamId("76561190000000000"), T0))
    }

    @Test
    fun a_surface_wide_rate_limit_parks_everyone_at_once_and_pins_no_partner() {
        val state = SteamWriteThrottle.onFailure(
            SteamWriteThrottleState.EMPTY,
            alice,
            SteamWriteFailureKind.RATE_LIMITED_SURFACE,
            T0,
            limits,
            random(),
        )
        // One refusal, and the whole surface is parked — no waiting for globalBreakerThreshold to agree.
        assertTrue(limits.globalBreakerThreshold > 1, "the point of this test is that the streak is bypassed")
        val blocked = assertIs<WriteGate.Blocked>(SteamWriteThrottle.gate(state, bob, T0))
        assertEquals(ThrottleScope.GLOBAL, blocked.scope)
        assertTrue(blocked.until >= T0 + limits.cooldownMin)
        assertTrue(blocked.until <= T0 + limits.cooldownMax)
        // Nothing here was about `alice`: pinning a cooldown on her would outlast the surface block and hold
        // her back alone once it lifts, for a refusal that never named her.
        assertTrue(state.partners.isEmpty(), "an account-wide refusal says nothing about one partner's quota")
        assertEquals(1, state.globalAttempt)
        assertEquals(0, state.consecutiveFailures, "the streak restarts after arming the breaker")
    }

    @Test
    fun repeated_surface_rate_limits_escalate_the_global_cooldown() {
        var state = SteamWriteThrottleState.EMPTY
        val random = random()
        repeat(3) { round ->
            state = SteamWriteThrottle.onFailure(state, alice, SteamWriteFailureKind.RATE_LIMITED_SURFACE, T0, limits, random)
            assertEquals(round + 1, state.globalAttempt)
        }
    }

    @Test
    fun an_unclassified_failure_changes_nothing() {
        val state = SteamWriteThrottle.onFailure(
            SteamWriteThrottleState.EMPTY,
            alice,
            SteamWriteFailureKind.OTHER,
            T0,
            limits,
            random(),
        )
        assertSame(SteamWriteThrottleState.EMPTY, state)
    }

    // ---- onSuccess / prune --------------------------------------------------------------------------

    @Test
    fun a_success_clears_that_partner_and_the_escalation_counters() {
        val state = SteamWriteThrottleState(
            partners = mapOf(alice to PartnerCooldown(T0 + 5.minutes, 3), bob to PartnerCooldown(T0 + 5.minutes, 1)),
            globalAttempt = 2,
            consecutiveFailures = 2,
        )
        val cleared = SteamWriteThrottle.onSuccess(state, alice)
        assertTrue(alice !in cleared.partners)
        assertTrue(bob in cleared.partners, "one partner's success says nothing about another's quota")
        assertEquals(0, cleared.globalAttempt)
        assertEquals(0, cleared.consecutiveFailures)
    }

    @Test
    fun a_success_leaves_a_standing_global_block_alone() {
        val state = SteamWriteThrottleState(globalUntil = T0 + 30.minutes, globalAttempt = 1, consecutiveFailures = 1)
        assertEquals(T0 + 30.minutes, SteamWriteThrottle.onSuccess(state, alice).globalUntil)
    }

    @Test
    fun a_success_with_nothing_to_clear_returns_the_same_state() {
        val state = SteamWriteThrottleState(partners = mapOf(bob to PartnerCooldown(T0 + 5.minutes, 1)))
        assertSame(state, SteamWriteThrottle.onSuccess(state, alice))
    }

    @Test
    fun prune_drops_expired_entries_and_keeps_live_ones() {
        val state = SteamWriteThrottleState(
            partners = mapOf(alice to PartnerCooldown(T0 - 1.minutes, 2), bob to PartnerCooldown(T0 + 1.minutes, 1)),
            globalUntil = T0 - 1.minutes,
            globalAttempt = 3,
        )
        val pruned = SteamWriteThrottle.prune(state, T0)
        assertEquals(setOf(bob), pruned.partners.keys)
        assertNull(pruned.globalUntil)
        assertEquals(3, pruned.globalAttempt, "escalation depth survives pruning; only a success resets it")
    }

    @Test
    fun prune_is_a_no_op_when_every_entry_is_still_live() {
        val state = SteamWriteThrottleState(
            partners = mapOf(alice to PartnerCooldown(T0 + 1.minutes, 1)),
            globalUntil = T0 + 1.minutes,
        )
        assertSame(state, SteamWriteThrottle.prune(state, T0))
    }
}
