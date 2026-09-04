package com.dmarket.p2p.tracker.policy

import com.dmarket.p2p.tracker.config.SteamWriteConfig
import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.DirectiveId
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.marketplace.Directive
import com.dmarket.p2p.tracker.model.marketplace.DirectiveAction
import com.dmarket.p2p.tracker.support.T0
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class CreateChainPlannerTest {

    private val limits = SteamWriteConfig()
    private val alice = SteamId("76561199497281579")
    private val bob = SteamId("76561198077327619")
    private val carol = SteamId("76561190000000000")

    /** `n` creates for [partner], ids `<tag>-1..n`, in the order a heartbeat would have served them. */
    private fun creates(partner: SteamId, n: Int, tag: String) = (1..n).map { i ->
        Directive(
            directiveId = DirectiveId("$tag-$i"),
            action = DirectiveAction.CREATE_OFFER,
            dealId = DealId("deal-$tag-$i"),
            partnerSteamId = partner,
            assetIds = listOf(AssetId("asset-$tag-$i")),
            tradeToken = "token",
        )
    }

    private fun plan(
        creates: List<Directive>,
        throttle: SteamWriteThrottleState = SteamWriteThrottleState.EMPTY,
        limits: SteamWriteConfig = this.limits,
    ) = CreateChainPlanner.plan(creates, throttle, T0, limits)

    private fun CreateChainPlan.ids(partner: SteamId) = chains.single { it.partner == partner }.directives.map { it.directiveId.value }

    private fun CreateChainPlan.deferredIds() = deferred.map { it.directive.directiveId.value }

    @Test
    fun no_creates_is_the_empty_plan() {
        assertSame(CreateChainPlan.EMPTY, plan(emptyList()))
        assertTrue(CreateChainPlan.EMPTY.isEmpty)
    }

    @Test
    fun groups_by_counterparty_keeping_backend_order_within_and_first_seen_order_across() {
        // Interleaved on the wire — the planner must still produce one chain per partner.
        val interleaved = listOf(
            creates(alice, 2, "a")[0],
            creates(bob, 2, "b")[0],
            creates(alice, 2, "a")[1],
            creates(bob, 2, "b")[1],
        )
        val result = plan(interleaved)
        assertEquals(listOf(alice, bob), result.chains.map { it.partner })
        assertEquals(listOf("a-1", "a-2"), result.ids(alice))
        assertEquals(listOf("b-1", "b-2"), result.ids(bob))
        assertTrue(result.deferred.isEmpty())
    }

    @Test
    fun three_counterparties_yield_three_chains() {
        val result = plan(creates(alice, 1, "a") + creates(bob, 1, "b") + creates(carol, 1, "c"))
        assertEquals(3, result.chains.size)
        assertEquals(listOf(1, 1, 1), result.chains.map { it.directives.size })
    }

    @Test
    fun a_global_block_defers_every_create_with_a_retry_hint() {
        val throttle = SteamWriteThrottleState(globalUntil = T0 + 10.minutes)
        val result = plan(creates(alice, 2, "a") + creates(bob, 1, "b"), throttle)
        assertTrue(result.isEmpty)
        assertEquals(listOf("a-1", "a-2", "b-1"), result.deferredIds())
        assertTrue(result.deferred.all { it.retryAfterSeconds == 10.minutes.inWholeSeconds.toInt() })
        assertTrue(result.deferred.all { it.reason.contains("surface") })
    }

    @Test
    fun a_partner_cooldown_defers_only_that_partners_chain() {
        val throttle = SteamWriteThrottleState(partners = mapOf(alice to PartnerCooldown(T0 + 5.minutes, 1)))
        val result = plan(creates(alice, 3, "a") + creates(bob, 2, "b"), throttle)
        assertEquals(listOf(bob), result.chains.map { it.partner })
        assertEquals(listOf("b-1", "b-2"), result.ids(bob))
        assertEquals(listOf("a-1", "a-2", "a-3"), result.deferredIds())
        assertTrue(result.deferred.all { it.retryAfterSeconds == 5.minutes.inWholeSeconds.toInt() })
    }

    @Test
    fun an_expired_cooldown_does_not_defer_anything() {
        val throttle = SteamWriteThrottleState(partners = mapOf(alice to PartnerCooldown(T0, 1)))
        val result = plan(creates(alice, 1, "a"), throttle)
        assertEquals(listOf("a-1"), result.ids(alice))
        assertTrue(result.deferred.isEmpty())
    }

    @Test
    fun the_per_partner_cap_keeps_the_first_n_and_defers_the_tail() {
        // The session that motivated this leased 26 creates for one partner against Steam's limit of 5.
        val result = plan(creates(alice, 26, "a"), limits = limits.copy(maxCreatesPerCycle = 100))
        assertEquals((1..5).map { "a-$it" }, result.ids(alice))
        assertEquals((6..26).map { "a-$it" }, result.deferredIds())
        assertTrue(result.deferred.all { it.reason.contains("per-partner") })
        assertTrue(result.deferred.all { it.retryAfterSeconds == null })
    }

    @Test
    fun the_cycle_ceiling_is_spent_round_robin_so_one_long_chain_cannot_starve_the_short_ones() {
        // 5 for alice (her whole per-partner budget) + 1 each for bob and carol, against a ceiling of 3.
        val result = plan(
            creates(alice, 26, "a") + creates(bob, 1, "b") + creates(carol, 1, "c"),
            limits = limits.copy(maxCreatesPerCycle = 3),
        )
        assertEquals(listOf("a-1"), result.ids(alice))
        assertEquals(listOf("b-1"), result.ids(bob))
        assertEquals(listOf("c-1"), result.ids(carol))
        assertEquals(3, result.chains.sumOf { it.directives.size })
        assertTrue("a-2" in result.deferredIds())
    }

    @Test
    fun the_ceiling_deals_a_second_create_only_after_every_chain_has_a_first() {
        val result = plan(
            creates(alice, 3, "a") + creates(bob, 3, "b"),
            limits = limits.copy(maxCreatesPerCycle = 3),
        )
        assertEquals(listOf("a-1", "a-2"), result.ids(alice))
        assertEquals(listOf("b-1"), result.ids(bob))
    }

    @Test
    fun the_concurrency_limit_defers_whole_chains_so_the_running_ones_keep_their_budget() {
        val result = plan(
            creates(alice, 2, "a") + creates(bob, 2, "b") + creates(carol, 2, "c"),
            limits = limits.copy(maxConcurrentChains = 2),
        )
        assertEquals(listOf(alice, bob), result.chains.map { it.partner })
        assertEquals(listOf("c-1", "c-2"), result.deferredIds())
        assertTrue(result.deferred.all { it.reason.contains("chain limit") })
    }

    @Test
    fun a_create_without_a_partner_is_deferred_rather_than_chained() {
        val orphan = creates(alice, 1, "a")[0].copy(partnerSteamId = null)
        val result = plan(listOf(orphan) + creates(bob, 1, "b"))
        assertEquals(listOf(bob), result.chains.map { it.partner })
        assertEquals(listOf("a-1"), result.deferredIds())
    }

    @Test
    fun every_leased_create_is_either_chained_or_deferred_never_dropped() {
        val all = creates(alice, 26, "a") + creates(bob, 7, "b") + creates(carol, 3, "c")
        val result = plan(all, limits = limits.copy(maxCreatesPerCycle = 4, maxConcurrentChains = 2))
        val accounted = result.chains.flatMap { it.directives } + result.deferred.map { it.directive }
        assertEquals(all.size, accounted.size)
        assertEquals(all.map { it.directiveId }.toSet(), accounted.map { it.directiveId }.toSet())
    }
}
