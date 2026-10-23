package com.dmarket.p2p.sdk.health

import com.dmarket.p2p.headless.prover.ProverOutput
import com.dmarket.p2p.sdk.prover.ProofProblems
import com.dmarket.p2p.tracker.engine.FailedCancelKind
import com.dmarket.p2p.tracker.engine.ProofSkipReason
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
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Turns the loop's lifecycle events and what the tracker holds ([HeadlessTrackerState]) into [Health].
 *
 * Event-derived problems last as long as their cause: a heartbeat that round-trips clears the DMarket and
 * account problems it would have raised again, a cycle with no failed Steam read clears the read problem, and
 * a per-deal failure clears when the same deal's next attempt succeeds, when its offer turns out to be gone already,
 * or once DMarket has stopped asking for it. Each open problem keeps the time it was first seen in `since`, across the
 * cycles that re-raise it.
 *
 * Its state is held in memory only, so a restart forgets a cancel that waits for a token. The SDK starts without the
 * key and the token anyway, and the cancel's next issue, told by a heartbeat sent without a token, raises it again.
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

    /** Each `cancel_offer` leased while no token was usable and not run since, by deal. */
    private val cancelsAwaitingToken = linkedMapOf<String, CancelIssues>()
    private val createsLeasedWhileDisabled = LeasedDeals()

    /** Each `cancel_offer` leased while offer writes are off, by deal: when a heartbeat last carried it. */
    private val cancelsLeasedWhileDisabled = linkedMapOf<String, Instant>()

    /** Each deal whose cancelled offer Steam shows traded, by deal: the offer id and when it was last seen so. */
    private val acceptedAfterCancel = linkedMapOf<String, Pair<String, Instant>>()

    /** Each deal's last failed directive, by deal. */
    private val directiveFailures = linkedMapOf<String, DirectiveFailure>()
    private val proofFailures = linkedMapOf<String, String>()

    /** Each decisive transition whose proof is due with no prover to make it, as (deal, axis): its report is held back. */
    private val proofsWithoutProver = linkedSetOf<Pair<String, String>>()
    private val proverFailures = linkedMapOf<String, ProofFailure>()
    private val classifiedProofs = mutableSetOf<String>()
    private var proverMemory: String? = null
    private val since = mutableMapOf<ProblemCode, Instant>()

    override suspend fun onEvent(event: LifecycleEvent) {
        leasedDeal(event)?.let { directiveFailures[it]?.leasedAt = clock.now() }
        when (event) {
            is LifecycleEvent.CycleStarted -> {
                readFailedThisCycle = false
                throttledThisCycle = false
                tokenHeldThisCycle = false
            }
            is LifecycleEvent.HeartbeatSent -> {
                lastHeartbeatAt = clock.now()
                dmarketAuthFailed = false
                dmarketUnreachable = false
                wrongAccount = null
                createsLeasedWhileDisabled.heartbeat()
            }
            is LifecycleEvent.DirectiveDropped -> event.dealId?.let { deal ->
                val cancel = event.kind == DirectiveAction.CANCEL_OFFER.wireName
                val now = clock.now()
                when (event.reason) {
                    OFFER_WRITES_DISABLED -> if (cancel) cancelsLeasedWhileDisabled[deal] = now else createsLeasedWhileDisabled += deal
                    STEAM_SESSION_MISSING_DROP_REASON -> if (cancel) {
                        cancelsAwaitingToken[deal] = CancelIssues(first = cancelsAwaitingToken[deal]?.first ?: now, last = now)
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
            }
            is LifecycleEvent.DirectiveExecuted -> event.dealId?.let { deal ->
                // The cancel ran, however it ended: a failure is DIRECTIVE_FAILED's from here on.
                if (event.kind == DirectiveAction.CANCEL_OFFER.wireName) cancelsAwaitingToken -= deal
                if (event.status == FAILED) {
                    directiveFailures[deal] = DirectiveFailure("${event.kind} failed: ${event.error ?: "no detail"}", clock.now())
                } else {
                    directiveFailures.remove(deal)
                }
            }
            // An offer gone already is what the cancel was for: nothing is left to fix, whatever the cancel reported.
            is LifecycleEvent.FailedCancelChecked -> when (event.kind) {
                FailedCancelKind.ALREADY_INACTIVE.wireName -> event.dealId?.let(directiveFailures::remove)
                FailedCancelKind.ALREADY_TRADED.wireName -> event.dealId?.let {
                    acceptedAfterCancel[it] = event.steamOfferId to clock.now()
                }
                else -> Unit
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
            is LifecycleEvent.ProofSuppressed -> if (event.reason == ProofSkipReason.NO_PROVER.message) {
                proofsWithoutProver += event.dealId to event.source
            }
            // The axis's report went: the proof it waited for is no longer due.
            is LifecycleEvent.TradeStatusReported -> proofsWithoutProver -= event.dealId to event.source
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
        // A waiting cancel stands until it runs, though its deal has usually left the tracking list and DMarket issues
        // the cancel only now and then: the offer may be open all that while. A token pushed meanwhile runs it only
        // at its next issue.
        cancelsAwaitingToken.values.removeAll { now - it.first >= CANCEL_OFFER_LIFETIME }
        if (cancelsAwaitingToken.isNotEmpty()) {
            val heldBack = "DMarket asks to cancel a Steam offer: with a usable Steam token held, it runs when DMarket issues it again"
            val message = when {
                expiresAt == null || expiresAt <= now ->
                    "DMarket asks to cancel a Steam offer and there is no usable Steam token: the offer stays open until one " +
                        "is pushed (PUT /v1/steam/session)"
                cancelsAwaitingToken.values.any { now - it.last > CANCEL_REISSUED_WITHIN } ->
                    "$heldBack, which it has not for over ${CANCEL_REISSUED_WITHIN.inWholeMinutes} minutes: the offer can be " +
                        "cancelled by hand on Steam"
                else -> heldBack
            }
            raise(ProblemCode.STEAM_CANCEL_PENDING, message, cancelsAwaitingToken.keys)
        }
        // A create is leased again every heartbeat while its window lasts; a cancel only now and then, so it stands
        // until the next issue is due rather than blinking out in between.
        cancelsLeasedWhileDisabled.values.removeAll { now - it > CANCEL_REISSUED_WITHIN }
        val writesNotRun = createsLeasedWhileDisabled.deals + cancelsLeasedWhileDisabled.keys
        if (writesNotRun.isNotEmpty()) {
            raise(
                ProblemCode.STEAM_WRITES_DISABLED,
                "DMarket leases Steam offer writes to this SDK, which runs with steamWrites: false; they lapse to another device",
                writesNotRun,
            )
        }
        acceptedAfterCancel.values.removeAll { (_, seen) -> now - seen > ACCEPTED_AFTER_CANCEL_SHOWN }
        acceptedAfterCancel.entries.lastOrNull()?.let { (deal, sighting) ->
            raise(
                ProblemCode.OFFER_ACCEPTED_AFTER_CANCEL,
                "DMarket asked to cancel the Steam offer ${sighting.first} of deal $deal, and Steam shows it accepted: the trade " +
                    "went through after the deal was cancelled, and a person has to look at it",
                acceptedAfterCancel.keys,
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
        // A deal DMarket no longer tracks owes no proof.
        proofsWithoutProver.retainAll { it.first in dealsTracked }
        if (proofsWithoutProver.isNotEmpty()) {
            raise(
                ProblemCode.PROVER_DISABLED,
                "DMarket requires a proof for these deals and this SDK has no prover (prover.enabled is false, or its files " +
                    "were not found): they do not advance until the prover is enabled, or DMarket stops requiring the proof",
                proofsWithoutProver.map { it.first },
            )
        }
        // A deal DMarket still tracks keeps its failure until an attempt succeeds. One it no longer tracks keeps it while
        // its directives are still issued: a create every heartbeat, a cancel about every 30 minutes, so a failure
        // does not blink out between two issues. Past that DMarket has given up on it, and so does the problem.
        val tracked = dealsTracked.toSet()
        directiveFailures.entries.removeAll { (deal, failure) -> deal !in tracked && now - failure.leasedAt > CANCEL_REISSUED_WITHIN }
        if (directiveFailures.isNotEmpty()) {
            raise(ProblemCode.DIRECTIVE_FAILED, directiveFailures.values.first().message, directiveFailures.keys)
        }

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

    /** The deal [event] shows a directive leased for, run or not; `null` when it shows none. */
    private fun leasedDeal(event: LifecycleEvent): String? = when (event) {
        is LifecycleEvent.DirectiveExecuted -> event.dealId
        is LifecycleEvent.DirectiveDropped -> event.dealId
        is LifecycleEvent.SteamWriteDeferred -> event.dealId
        is LifecycleEvent.DuplicateWriteSuppressed -> event.dealId
        is LifecycleEvent.CreateUnanswered -> event.dealId
        else -> null
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

    /** A deal's failed directive: what failed, and when a directive was last leased for the deal. */
    private class DirectiveFailure(val message: String, var leasedAt: Instant)

    /** When a heartbeat sent without a usable token first and last carried a deal's `cancel_offer`. */
    private class CancelIssues(val first: Instant, val last: Instant)

    private companion object {
        const val FAILED = "FAILED"
        const val MIB = 1024L * 1024L
        val HTTP_STATUS = Regex("HTTP (\\d{3})")

        /**
         * How long a cancel waits at most. Steam lets an unanswered trade offer lapse after 14 days, and DMarket keeps
         * the cancel it issues as long: past that there is no offer left to cancel and no issue left to wait for.
         */
        val CANCEL_OFFER_LIFETIME: Duration = 14.days

        /**
         * How soon after its last issue an unfinished cancel is due again. DMarket re-issues one about every 30 minutes
         * once its first few attempts are spent, and a device sees an issue at its next heartbeat, a minute or two
         * later: past 35 minutes an issue is overdue, not merely between two. A backend that stops re-issuing leaves
         * every waiting cancel overdue, and the problem says so. A failed directive for a deal no longer tracked lapses
         * after as long without a lease: by then DMarket has stopped issuing it.
         */
        val CANCEL_REISSUED_WITHIN: Duration = 35.minutes

        /**
         * How long a trade made after its cancel is named. Nothing on this side can resolve it, and a cancel whose offer
         * is traded is not issued again once it is reported spent, so the problem is told for a day and the `warn` log
         * line is its lasting record.
         */
        val ACCEPTED_AFTER_CANCEL_SHOWN: Duration = 1.days
    }
}
