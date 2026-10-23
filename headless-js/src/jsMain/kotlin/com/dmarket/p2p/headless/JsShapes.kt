package com.dmarket.p2p.headless

import com.dmarket.p2p.tracker.credential.marketplace.TradingKeyPushResult
import com.dmarket.p2p.tracker.credential.steam.SteamPushResult
import com.dmarket.p2p.tracker.runtime.HeadlessTrackerState
import kotlin.time.Instant

// What the host gets back: fresh plain objects of strings, numbers, booleans, string arrays and null, built field
// by field. None of them holds a reference to a Kotlin object, so nothing the library keeps is reachable from one.
// Times are epoch milliseconds, durations milliseconds, ids strings; a value Kotlin has as `null` is `null`.

/** A fresh, empty plain object. */
internal fun plainObject(): dynamic = js("({})")

private fun Instant?.epochMs(): Double? = this?.toEpochMilliseconds()?.toDouble()

/** A key push's result, keyed by `type`. No case carries the secret key: the core's results never hold it. */
internal fun TradingKeyPushResult.toJs(): dynamic {
    val o = plainObject()
    when (this) {
        is TradingKeyPushResult.Held -> {
            o.type = "held"
            o.publicKey = publicKey
            o.change = change.name
        }
        is TradingKeyPushResult.Invalid -> {
            o.type = "invalid"
            o.reason = reason
        }
        is TradingKeyPushResult.AccountChanged -> {
            o.type = "accountChanged"
            o.publicKey = publicKey
            o.heldPublicKey = heldPublicKey
            o.linkedSteamId = linkedSteamId.value
            o.keyLinkedSteamId = keyLinkedSteamId?.value
        }
        is TradingKeyPushResult.Unverified -> {
            o.type = "unverified"
            o.publicKey = publicKey
            o.reason = reason
        }
    }
    return o
}

/** A token push's result, keyed by `type`. No case carries the token. */
internal fun SteamPushResult.toJs(): dynamic {
    val o = plainObject()
    when (this) {
        is SteamPushResult.Held -> {
            o.type = "held"
            o.steamId = steamId.value
            o.expiresAtMs = expiresAt.epochMs()
        }
        is SteamPushResult.WrongAccount -> {
            o.type = "wrongAccount"
            o.tokenSteamId = tokenSteamId.value
            o.linkedSteamId = linkedSteamId?.value
        }
        is SteamPushResult.Invalid -> {
            o.type = "invalid"
            o.reason = reason
        }
    }
    return o
}

/** The tracker's state without either secret. `dealDeadlines` maps each deal id to its deadline. */
internal fun HeadlessTrackerState.toJs(): dynamic {
    val o = plainObject()
    o.hasTradingApiKey = hasTradingApiKey
    o.steamTokenSteamId = steamTokenSteamId?.value
    o.steamTokenExpiresAtMs = steamTokenExpiresAt.epochMs()
    o.linkedSteamId = linkedSteamId?.value
    o.readySinceMs = readySince.epochMs()
    o.trackedDealIds = trackedDealIds.map { it.value }.toTypedArray()
    o.clockOffsetMs = clockOffset?.inWholeMilliseconds?.toDouble()
    o.clockSkewed = clockSkewed
    o.steamNotLinked = steamNotLinked
    o.updateRequired = updateRequired
    o.updateDueTimeMs = updateDueTime.epochMs()
    val deadlines = plainObject()
    dealDeadlines.forEach { (deal, deadline) -> deadlines[deal.value] = deadline.toEpochMilliseconds().toDouble() }
    o.dealDeadlines = deadlines
    return o
}
