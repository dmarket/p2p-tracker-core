package com.dmarket.p2p.tracker.policy

import com.dmarket.p2p.tracker.config.SteamWriteConfig
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.marketplace.Directive
import kotlin.time.Instant

/**
 * The `create_offer` directives for one counterparty, in the order the backend served them. The caller
 * runs a chain **strictly sequentially** — Steam counts outstanding offers per partner, so two concurrent
 * creates for one partner race towards the same quota and the second learns nothing the first didn't
 * already tell us — and stops the chain at its first failure.
 */
data class CreateChain(val partner: SteamId, val directives: List<Directive>)

/** A create this cycle will not attempt, with the reason to surface for it. */
data class DeferredCreate(val directive: Directive, val reason: String, val retryAfterSeconds: Int? = null)

/**
 * How this cycle's `create_offer` work is split: the [chains] to run (one per counterparty, concurrent
 * with each other) and the [deferred] creates that will not be attempted at all.
 */
data class CreateChainPlan(val chains: List<CreateChain> = emptyList(), val deferred: List<DeferredCreate> = emptyList()) {
    val isEmpty: Boolean get() = chains.isEmpty()

    companion object {
        val EMPTY: CreateChainPlan = CreateChainPlan()
    }
}

/**
 * Turns the flat `create_offer` list from [com.dmarket.p2p.tracker.engine.DirectivePlanner] into
 * per-counterparty chains, applying every limit that decides *which* creates this cycle attempts.
 *
 * This exists because the backend leases far more creates than Steam will accept — in the session that
 * motivated it, 18 → 30 `create_offer` directives per heartbeat, **all for one partner**, against Steam's
 * limit of 5 outstanding offers per partner. Executing them flat meant ~20 doomed POSTs per heartbeat and,
 * eventually, Steam's edge refusing the create surface outright. Grouping by partner gives the two
 * properties that fix it: one in-flight create per counterparty (so a refusal is learned once, not twenty
 * times), and failure isolation (a partner over quota cannot starve the others).
 *
 * Pure: no clock, no IO — [now] and the throttle state are supplied by the caller, so the whole flow is
 * table-testable. Every rejected directive comes back in [CreateChainPlan.deferred] with its reason rather
 * than being dropped silently: the backend re-leases anything it still wants done, so an invisible drop
 * would stall a deal with nothing in the log to explain it.
 */
object CreateChainPlanner {
    /** Order matters: cheapest, broadest rejections first, so a blocked surface never reports cap reasons. */
    fun plan(creates: List<Directive>, throttle: SteamWriteThrottleState, now: Instant, limits: SteamWriteConfig): CreateChainPlan {
        if (creates.isEmpty()) return CreateChainPlan.EMPTY

        // 1. The whole surface parked: nothing is attempted, whatever the partner.
        throttle.globalUntil?.takeIf { now < it }?.let { until ->
            return CreateChainPlan(deferred = creates.map { it.deferred(SURFACE_COOLING_DOWN, now, until) })
        }

        val deferred = mutableListOf<DeferredCreate>()
        // 2. Group by counterparty, keeping the backend's order inside a group and first-seen order across
        //    groups. `plan.creates` never carries a null partner (DirectivePlanner drops those as malformed),
        //    but a defensive null lands in `deferred` rather than in a chain keyed by a fabricated id.
        val grouped = LinkedHashMap<SteamId, MutableList<Directive>>()
        for (directive in creates) {
            val partner = directive.partnerSteamId
            if (partner == null) {
                deferred += directive.deferred(MISSING_PARTNER, now, null)
                continue
            }
            grouped.getOrPut(partner) { mutableListOf() } += directive
        }

        // 3. Partners under their own cooldown: the whole group waits.
        val open = LinkedHashMap<SteamId, MutableList<Directive>>()
        for ((partner, group) in grouped) {
            val until = throttle.partners[partner]?.until?.takeIf { now < it }
            if (until != null) {
                group.forEach { deferred += it.deferred(PARTNER_COOLING_DOWN, now, until) }
            } else {
                open[partner] = group
            }
        }

        // 4. Per-partner cap: past Steam's own outstanding-offer limit every further create is refused anyway.
        //    The tail is deferred in the order it was served, so the log reads the way the heartbeat did.
        val capped = LinkedHashMap<SteamId, List<Directive>>()
        for ((partner, group) in open) {
            val keep = group.take(limits.maxCreatesPerPartnerPerCycle)
            group.drop(keep.size).forEach { deferred += it.deferred(PARTNER_CAP, now, null) }
            capped[partner] = keep
        }

        // 5. Concurrency limit: whole chains, so the ones that do run keep their full per-partner budget.
        val running = capped.entries.take(limits.maxConcurrentChains).map { it.key to it.value }
        capped.entries.drop(limits.maxConcurrentChains).forEach { (_, group) ->
            group.forEach { deferred += it.deferred(CHAIN_LIMIT, now, null) }
        }

        // 6. Global ceiling, spent round-robin so one long chain cannot starve the short ones. A flat
        //    `take(n)` would have handed the entire budget to the 26-create partner that motivated this.
        val chains = running.roundRobinLimit(limits.maxCreatesPerCycle) { dropped ->
            deferred += dropped.deferred(CYCLE_CEILING, now, null)
        }

        return if (chains.isEmpty() && deferred.isEmpty()) {
            CreateChainPlan.EMPTY
        } else {
            CreateChainPlan(chains = chains, deferred = deferred)
        }
    }

    /**
     * Keeps at most [budget] directives across all groups by dealing them out one per group per pass, so
     * every chain gets a first create before any chain gets a second. [onDropped] receives each directive
     * that did not fit.
     */
    private fun List<Pair<SteamId, List<Directive>>>.roundRobinLimit(budget: Int, onDropped: (Directive) -> Unit): List<CreateChain> {
        if (sumOf { it.second.size } <= budget) return map { CreateChain(it.first, it.second) }

        // How many of each group's creates fit, dealt one per group per pass. Counting rather than copying keeps
        // the bookkeeping to one IntArray, and every group's kept slice stays a prefix of the order it arrived in.
        val keep = IntArray(size)
        var remaining = budget
        deal@ for (round in 0 until maxOf { it.second.size }) {
            forEachIndexed { index, (_, group) ->
                if (remaining == 0) return@forEachIndexed
                if (round < group.size) {
                    keep[index]++
                    remaining--
                }
            }
            if (remaining == 0) break@deal
        }
        forEachIndexed { index, (_, group) -> group.drop(keep[index]).forEach(onDropped) }
        return mapIndexedNotNull { index, (partner, group) ->
            group.take(keep[index]).takeIf { it.isNotEmpty() }?.let { CreateChain(partner, it) }
        }
    }

    private fun Directive.deferred(reason: String, now: Instant, until: Instant?) =
        DeferredCreate(this, reason, until?.let { SteamWriteThrottle.retryAfterSeconds(it, now) })

    private const val MISSING_PARTNER = "create_offer missing partner_steam_id"
    private const val SURFACE_COOLING_DOWN = "steam create surface cooling down"
    private const val PARTNER_COOLING_DOWN = "partner cooling down after a steam refusal"
    private const val PARTNER_CAP = "per-partner create cap reached for this cycle"
    private const val CHAIN_LIMIT = "concurrent create chain limit reached"
    private const val CYCLE_CEILING = "per-cycle create ceiling reached"
}
