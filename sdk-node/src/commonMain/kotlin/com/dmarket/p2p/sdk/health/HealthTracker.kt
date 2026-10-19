package com.dmarket.p2p.sdk.health

import com.dmarket.p2p.sdk.prover.ProofProblems
import com.dmarket.p2p.sdk.prover.ProverOutput
import com.dmarket.p2p.tracker.loop.OFFER_WRITES_DISABLED
import com.dmarket.p2p.tracker.loop.STEAM_SESSION_MISSING_DROP_REASON
import com.dmarket.p2p.tracker.loop.TOKEN_EXPIRING_DEFER_REASON
import com.dmarket.p2p.tracker.model.LifecycleEvent
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.marketplace.DirectiveAction
import com.dmarket.p2p.tracker.notary.PROOF_TOKEN_EXPIRING_REASON
import com.dmarket.p2p.tracker.notary.ProofFailure
import com.dmarket.p2p.tracker.port.host.Clock
import com.dmarket.p2p.tracker.port.host.EventObserver
import com.dmarket.p2p.tracker.runtime.HeadlessTrackerState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Turns the loop's lifecycle events and what the tracker holds ([HeadlessTrackerState]) into [Health].
 *
 * Event-derived problems last as long as their cause: a heartbeat that round-trips clears the DMarket and
 * account problems it would have raised again, a cycle with no failed Steam read clears the read problem, and
 * a per-deal failure clears when the same deal's next attempt succeeds. Each open problem keeps the time it was
 * first seen in `since`, across the cycles that re-raise it.
 *
 * Driven from one event loop (Node's), so its state needs no lock.
 */
class HealthTracker(
    private val clock: Clock,
    private val version: String,
    private val expiringWithin: Duration = 4.hours,
    private val heartbeatStaleAfter: Duration = 5.minutes,
) : EventObserver {
    private var lastHeartbeatAt: Instant? = null
    private var dmarketAuthFailed = false
    private var dmarketUnreachable = false
    private var wrongAccount: Pair<String?, String>? = null
    private var refusedPush: Pair<String?, String>? = null
    private var refusedKey: Pair<String, String?>? = null
    private var steamRead: Pair<ProblemCode, String>? = null
    private var readFailedThisCycle = false
    private val throttledDeals = linkedSetOf<String>()
    private var throttledThisCycle = false
    private val tokenHeldDeals = linkedSetOf<String>()
    private var tokenHeldThisCycle = false
    private val cancelsAwaitingToken = linkedSetOf<String>()
    private val cancelsLeased = LeasedDeals()
    private val writesLeasedWhileDisabled = LeasedDeals()
    private var heartbeatThisCycle = false
    private val directiveFailures = linkedMapOf<String, String>()
    private val proofFailures = linkedMapOf<String, String>()
    private val proverFailures = linkedMapOf<String, ProofFailure>()
    private val classifiedProofs = mutableSetOf<String>()
    private var proverMemory: String? = null
    private val since = mutableMapOf<ProblemCode, Instant>()

    override suspend fun onEvent(event: LifecycleEvent) {
        when (event) {
            is LifecycleEvent.CycleStarted -> {
                readFailedThisCycle = false
                throttledThisCycle = false
                tokenHeldThisCycle = false
                heartbeatThisCycle = false
            }
            is LifecycleEvent.HeartbeatSent -> {
                lastHeartbeatAt = clock.now()
                dmarketAuthFailed = false
                dmarketUnreachable = false
                wrongAccount = null
                heartbeatThisCycle = true
                cancelsLeased.heartbeat()
                writesLeasedWhileDisabled.heartbeat()
            }
            is LifecycleEvent.DirectiveDropped -> event.dealId?.let { deal ->
                when (event.reason) {
                    OFFER_WRITES_DISABLED -> writesLeasedWhileDisabled += deal
                    STEAM_SESSION_MISSING_DROP_REASON -> if (event.kind == DirectiveAction.CANCEL_OFFER.wireName) {
                        cancelsAwaitingToken += deal
                        cancelsLeased += deal
                    }
                }
            }
            is LifecycleEvent.ReLoginNeeded -> if (event.axis == "marketplace") dmarketAuthFailed = true
            is LifecycleEvent.MarketplaceServerError -> dmarketUnreachable = true
            is LifecycleEvent.LinkedSteamIdMismatch -> wrongAccount = event.linkedSteamId to event.tokenSteamId
            is LifecycleEvent.SteamReadFailed -> {
                readFailedThisCycle = true
                steamRead = classifyRead(event.reason) to "Steam ${event.axis} read failed: ${event.reason ?: "no detail"}"
            }
            // A create held back for the token's sake is STEAM_TOKEN_EXPIRING's story, not Steam's.
            is LifecycleEvent.SteamWriteDeferred -> event.dealId?.let {
                if (event.reason == TOKEN_EXPIRING_DEFER_REASON) {
                    tokenHeldThisCycle = true
                    tokenHeldDeals += it
                } else {
                    throttledThisCycle = true
                    throttledDeals += it
                }
            }
            is LifecycleEvent.CycleCompleted -> {
                if (!readFailedThisCycle) steamRead = null
                if (!throttledThisCycle) throttledDeals.clear()
                if (!tokenHeldThisCycle) tokenHeldDeals.clear()
                // A heartbeat with a usable token ran every cancel it leased; one it no longer leases waits for no token.
                if (heartbeatThisCycle) cancelsAwaitingToken.clear()
            }
            is LifecycleEvent.DirectiveExecuted -> event.dealId?.let { deal ->
                if (event.kind == DirectiveAction.CANCEL_OFFER.wireName) cancelsAwaitingToken -= deal
                if (event.status == FAILED) {
                    directiveFailures[deal] = "${event.kind} failed: ${event.error ?: "no detail"}"
                } else {
                    directiveFailures.remove(deal)
                }
            }
            is LifecycleEvent.ProofFailed -> when {
                // The prover's host already said how it ended ([proofOutcome]).
                classifiedProofs.remove(event.dealId) -> Unit
                // Held back for the token's sake: STEAM_TOKEN_EXPIRING's story, like a held create.
                event.reason?.contains(PROOF_TOKEN_EXPIRING_REASON) == true -> {
                    tokenHeldThisCycle = true
                    tokenHeldDeals += event.dealId
                }
                else -> proofFailures[event.dealId] = "proof failed: ${event.reason ?: "no detail"}"
            }
            is LifecycleEvent.ProofSubmitted -> if (event.verified) proofFailures.remove(event.dealId)
            else -> Unit
        }
    }

    /**
     * How a proof the prover's host ran for [dealId] ended; `null`: it proved, so the notary and the prover work and
     * their problems clear. A failure the SDK held back without starting a prover changes nothing.
     */
    fun proofOutcome(dealId: String, failure: ProofFailure?) {
        if (failure == null) {
            proverFailures.clear()
            proofFailures.remove(dealId)
            return
        }
        classifiedProofs += dealId
        when (ProofProblems.problemFor(failure)) {
            null -> Unit
            ProblemCode.PROOF_FAILED -> proofFailures[dealId] = "proof failed: ${ProverOutput.redact(failure.summary)}"
            else -> proverFailures[dealId] = failure
        }
    }

    /** At start: the memory limit ([limitBytes]) leaves less than one proof's peak ([peakBytes]) beside [usedBytes]. */
    fun proverMemoryLow(limitBytes: Long, usedBytes: Long, peakBytes: Long) {
        proverMemory = "the memory limit is ${limitBytes / MIB} MiB and the SDK uses ${usedBytes / MIB} MiB: " +
            "one proof needs up to ${peakBytes / MIB} MiB beside it"
    }

    /** A pushed token was refused for naming [tokenSteamId] rather than [linkedSteamId]. */
    fun tokenRefused(tokenSteamId: SteamId, linkedSteamId: SteamId?) {
        refusedPush = linkedSteamId?.value to tokenSteamId.value
    }

    /** A pushed token was held: the refusal above, if any, is over. */
    fun tokenHeld() {
        refusedPush = null
    }

    /** A pushed key was refused: its heartbeat named [keyLinkedSteamId], not [linkedSteamId] the held key is bound to. */
    fun keyRefused(linkedSteamId: SteamId, keyLinkedSteamId: SteamId?) {
        refusedKey = linkedSteamId.value to keyLinkedSteamId?.value
    }

    /** A pushed key was held: the refusal above, if any, is over. */
    fun keyHeld() {
        refusedKey = null
    }

    /**
     * The health right now, given what the tracker holds ([state]). A problem that stops the SDK acting for every
     * deal names every deal the last heartbeat tracked. While the tracker is ready to act, a heartbeat that has not
     * come back for [heartbeatStaleAfter] is reported — the loop itself only speaks up after several failures in a
     * row.
     */
    fun snapshot(state: HeadlessTrackerState): Health {
        val now = clock.now()
        val dealsTracked = state.trackedDealIds.map { it.value }
        val expiresAt = state.steamTokenExpiresAt
        val open = linkedMapOf<ProblemCode, Pair<String, List<String>>>()
        fun raise(code: ProblemCode, message: String, deals: Collection<String> = emptyList()) {
            open[code] = message to deals.toList()
        }

        if (!state.hasTradingApiKey) raise(ProblemCode.DMARKET_KEY_MISSING, "push the Trading API key: PUT /v1/dmarket/key", dealsTracked)
        refusedKey?.let { (linked, theirs) ->
            val named = theirs?.let { "links $it" } ?: "links no Steam account"
            raise(
                ProblemCode.DMARKET_KEY_ACCOUNT_CHANGED,
                "a key for another account was refused: its account $named, not $linked; switching accounts takes a restart",
            )
        }
        // A skewed clock is why every signature is refused: the key itself may be fine, so it is not blamed.
        state.clockOffset?.takeIf { state.clockSkewed }?.let { offset ->
            raise(
                ProblemCode.CLOCK_SKEW,
                "this host's clock is ${offset.absoluteValue.inWholeSeconds}s off DMarket's: sync it",
                dealsTracked,
            )
        }
        if (dmarketAuthFailed &&
            !state.clockSkewed
        ) {
            raise(ProblemCode.DMARKET_AUTH_FAILED, "DMarket refused the Trading API key", dealsTracked)
        }
        // A heartbeat refused for the account's missing Steam link is answered: DMarket is reachable.
        if (state.steamNotLinked) {
            raise(ProblemCode.STEAM_NOT_LINKED, "the DMarket account has no Steam account linked: link one on DMarket", dealsTracked)
        } else if (dmarketUnreachable) {
            raise(ProblemCode.DMARKET_UNREACHABLE, "DMarket answers with errors or not at all", dealsTracked)
        } else {
            val since = state.readySince
            val last = listOfNotNull(lastHeartbeatAt, since).maxOrNull()
            val explained = dmarketAuthFailed || state.clockSkewed
            if (since != null && last != null && !explained && now - last > heartbeatStaleAfter) {
                raise(ProblemCode.DMARKET_UNREACHABLE, "no heartbeat has come back since $last", dealsTracked)
            }
        }
        when {
            expiresAt == null -> raise(ProblemCode.STEAM_TOKEN_MISSING, "push a Steam access token: PUT /v1/steam/session", dealsTracked)
            expiresAt <= now -> raise(ProblemCode.STEAM_TOKEN_EXPIRED, "the Steam token expired at $expiresAt", dealsTracked)
            // Every tracked deal loses its Steam side when the token lapses; the creates already held back for it
            // are named even when their deal has left the tracking list.
            expiresAt - now < expiringWithin ->
                raise(ProblemCode.STEAM_TOKEN_EXPIRING, "the Steam token expires at $expiresAt", dealsTracked + tokenHeldDeals)
        }
        (wrongAccount ?: refusedPush)?.let { (linked, token) ->
            val named = linked?.let { "DMarket links $it" } ?: "DMarket has not named the linked account yet"
            raise(ProblemCode.STEAM_WRONG_ACCOUNT, "the Steam token is for $token; $named", dealsTracked)
        }
        // Below the minimum the SDK still finishes its deals: degraded, not down. A scheduled minimum is only news.
        if (state.updateRequired) {
            raise(ProblemCode.SDK_UPDATE_REQUIRED, "this SDK version ($version) is below DMarket's minimum: update it")
        } else {
            state.updateDueTime?.let {
                raise(ProblemCode.SDK_UPDATE_DUE, "DMarket's minimum SDK version moves above $version at $it: update before then")
            }
        }
        steamRead?.let { (code, message) -> raise(code, message) }
        if (throttledDeals.isNotEmpty()) raise(ProblemCode.STEAM_RATE_LIMITED, "Steam offer creation is cooling down", throttledDeals)
        // A waiting cancel stands while its deal is still in the heartbeat: tracked, or its cancel still leased.
        val cancelsStillLeased = cancelsLeased.deals
        cancelsAwaitingToken.retainAll { it in dealsTracked || it in cancelsStillLeased }
        if (cancelsAwaitingToken.isNotEmpty()) {
            raise(
                ProblemCode.STEAM_CANCEL_PENDING,
                "DMarket asks to cancel a Steam offer and there is no usable Steam token: the offer stays open until one " +
                    "is pushed (PUT /v1/steam/session)",
                cancelsAwaitingToken,
            )
        }
        val writesNotRun = writesLeasedWhileDisabled.deals
        if (writesNotRun.isNotEmpty()) {
            raise(
                ProblemCode.STEAM_WRITES_DISABLED,
                "DMarket leases Steam offer writes to this SDK, which runs with steamWrites: false; they lapse to another device",
                writesNotRun,
            )
        }
        if (proofFailures.isNotEmpty()) raise(ProblemCode.PROOF_FAILED, proofFailures.values.first(), proofFailures.keys)
        // A refused proof can name a problem a heartbeat already raised (a refused key, a skewed clock): its deals join.
        proverFailures.entries.groupBy { ProofProblems.problemFor(it.value, state.clockSkewed) }.forEach { (code, entries) ->
            if (code == null) return@forEach
            val deals = entries.map { it.key }
            val held = open[code]
            if (held != null) {
                open[code] = held.first to (held.second + deals)
            } else {
                raise(code, "proof: ${ProverOutput.redact(entries.last().value.summary)}", deals)
            }
        }
        proverMemory?.let { raise(ProblemCode.PROVER_MEMORY_LOW, it) }
        if (directiveFailures.isNotEmpty()) raise(ProblemCode.DIRECTIVE_FAILED, directiveFailures.values.first(), directiveFailures.keys)

        since.keys.retainAll(open.keys)
        val deadlines = state.dealDeadlines.mapKeys { it.key.value }
        val problems = open.map { (code, detail) ->
            val first = since.getOrPut(code) { now }
            Problem(
                code = code,
                severity = code.severity.wireName,
                since = first.toString(),
                message = detail.first,
                affectedDeals = detail.second.distinct().map { AffectedDeal(it, deadlines[it]?.toString()) },
            )
        }
        val worst = problems.maxOfOrNull { it.code.severity.rank } ?: Severity.INFO.rank
        val status = if (worst <= Severity.INFO.rank) Health.OK else Severity.entries.first { it.rank == worst }.wireName
        return Health(
            status = status,
            problems = problems,
            steamTokenExpiresAt = expiresAt?.toString(),
            lastHeartbeatAt = lastHeartbeatAt?.toString(),
            version = version,
        )
    }

    private fun classifyRead(reason: String?): ProblemCode = when (HTTP_STATUS.find(reason.orEmpty())?.groupValues?.get(1)) {
        "401", "403" -> ProblemCode.STEAM_SESSION_REJECTED
        "429" -> ProblemCode.STEAM_RATE_LIMITED
        else -> ProblemCode.STEAM_UNREACHABLE
    }

    /**
     * The deals one kind of leased directive named in the last two heartbeats: a heartbeat's drops are told after it,
     * so the one before still counts while they arrive, and a deal the backend stopped leasing goes one heartbeat later.
     */
    private class LeasedDeals {
        private var current = linkedSetOf<String>()
        private var previous = linkedSetOf<String>()

        val deals: Set<String> get() = previous + current

        operator fun plusAssign(deal: String) {
            current += deal
        }

        fun heartbeat() {
            previous = current
            current = linkedSetOf()
        }
    }

    private companion object {
        const val FAILED = "FAILED"
        const val MIB = 1024L * 1024L
        val HTTP_STATUS = Regex("HTTP (\\d{3})")
    }
}
