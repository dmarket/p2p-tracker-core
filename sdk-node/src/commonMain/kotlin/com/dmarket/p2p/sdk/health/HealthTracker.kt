package com.dmarket.p2p.sdk.health

import com.dmarket.p2p.tracker.loop.TOKEN_EXPIRING_DEFER_REASON
import com.dmarket.p2p.tracker.model.LifecycleEvent
import com.dmarket.p2p.tracker.model.SteamId
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
    private var accountUnknown = false
    private var steamRead: Pair<ProblemCode, String>? = null
    private var readFailedThisCycle = false
    private val throttledDeals = linkedSetOf<String>()
    private var throttledThisCycle = false
    private val directiveFailures = linkedMapOf<String, String>()
    private val proofFailures = linkedMapOf<String, String>()
    private val since = mutableMapOf<ProblemCode, Instant>()

    override suspend fun onEvent(event: LifecycleEvent) {
        when (event) {
            is LifecycleEvent.CycleStarted -> {
                readFailedThisCycle = false
                throttledThisCycle = false
            }
            is LifecycleEvent.HeartbeatSent -> {
                lastHeartbeatAt = clock.now()
                dmarketAuthFailed = false
                dmarketUnreachable = false
                wrongAccount = null
                accountUnknown = false
            }
            is LifecycleEvent.ReLoginNeeded -> if (event.axis == "marketplace") dmarketAuthFailed = true
            is LifecycleEvent.MarketplaceServerError -> dmarketUnreachable = true
            is LifecycleEvent.LinkedSteamIdMismatch -> wrongAccount = event.linkedSteamId to event.tokenSteamId
            is LifecycleEvent.LinkedSteamIdUnknown -> accountUnknown = true
            is LifecycleEvent.SteamReadFailed -> {
                readFailedThisCycle = true
                steamRead = classifyRead(event.reason) to "Steam ${event.axis} read failed: ${event.reason ?: "no detail"}"
            }
            // A create held back for the token's sake is STEAM_TOKEN_EXPIRING's story, not Steam's.
            is LifecycleEvent.SteamWriteDeferred -> event.dealId?.takeIf { event.reason != TOKEN_EXPIRING_DEFER_REASON }?.let {
                throttledThisCycle = true
                throttledDeals += it
            }
            is LifecycleEvent.CycleCompleted -> {
                if (!readFailedThisCycle) steamRead = null
                if (!throttledThisCycle) throttledDeals.clear()
            }
            is LifecycleEvent.DirectiveExecuted -> event.dealId?.let { deal ->
                if (event.status == FAILED) {
                    directiveFailures[deal] = "${event.kind} failed: ${event.error ?: "no detail"}"
                } else {
                    directiveFailures.remove(deal)
                }
            }
            is LifecycleEvent.ProofFailed -> proofFailures[event.dealId] = "proof failed: ${event.reason ?: "no detail"}"
            is LifecycleEvent.ProofSubmitted -> if (event.verified) proofFailures.remove(event.dealId)
            else -> Unit
        }
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
     * The health right now, given what the tracker holds ([state]); [dealsTracked] are the deals a blocking
     * problem holds up. While the tracker is ready to act, a heartbeat that has not come back for
     * [heartbeatStaleAfter] is reported — the loop itself only speaks up after several failures in a row.
     */
    fun snapshot(state: HeadlessTrackerState, dealsTracked: List<String> = emptyList()): Health {
        val now = clock.now()
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
        if (dmarketAuthFailed) raise(ProblemCode.DMARKET_AUTH_FAILED, "DMarket refused the Trading API key", dealsTracked)
        if (dmarketUnreachable) {
            raise(ProblemCode.DMARKET_UNREACHABLE, "DMarket answers with errors or not at all", dealsTracked)
        } else {
            val since = state.readySince
            val last = listOfNotNull(lastHeartbeatAt, since).maxOrNull()
            if (since != null && last != null && !dmarketAuthFailed && now - last > heartbeatStaleAfter) {
                raise(ProblemCode.DMARKET_UNREACHABLE, "no heartbeat has come back since $last", dealsTracked)
            }
        }
        when {
            expiresAt == null -> raise(ProblemCode.STEAM_TOKEN_MISSING, "push a Steam access token: PUT /v1/steam/session", dealsTracked)
            expiresAt <= now -> raise(ProblemCode.STEAM_TOKEN_EXPIRED, "the Steam token expired at $expiresAt", dealsTracked)
            expiresAt - now < expiringWithin -> raise(ProblemCode.STEAM_TOKEN_EXPIRING, "the Steam token expires at $expiresAt")
        }
        (wrongAccount ?: refusedPush)?.let { (linked, token) ->
            val named = linked?.let { "DMarket links $it" } ?: "DMarket has not named the linked account yet"
            raise(ProblemCode.STEAM_WRONG_ACCOUNT, "the Steam token is for $token; $named", dealsTracked)
        }
        if (accountUnknown) raise(ProblemCode.STEAM_ACCOUNT_UNKNOWN, "DMarket names no linked Steam account", dealsTracked)
        steamRead?.let { (code, message) -> raise(code, message) }
        if (throttledDeals.isNotEmpty()) raise(ProblemCode.STEAM_RATE_LIMITED, "Steam offer creation is cooling down", throttledDeals)
        if (proofFailures.isNotEmpty()) raise(ProblemCode.PROOF_FAILED, proofFailures.values.first(), proofFailures.keys)
        if (directiveFailures.isNotEmpty()) raise(ProblemCode.DIRECTIVE_FAILED, directiveFailures.values.first(), directiveFailures.keys)

        since.keys.retainAll(open.keys)
        val problems = open.map { (code, detail) ->
            val first = since.getOrPut(code) { now }
            Problem(
                code = code,
                severity = if (code.blocking) "error" else "warning",
                since = first.toString(),
                message = detail.first,
                affectedDeals = detail.second.map { AffectedDeal(it) },
            )
        }
        val status = when {
            problems.any { it.code.blocking } -> "down"
            problems.isNotEmpty() -> "degraded"
            else -> "ok"
        }
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

    private companion object {
        const val FAILED = "FAILED"
        val HTTP_STATUS = Regex("HTTP (\\d{3})")
    }
}
