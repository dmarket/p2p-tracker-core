package com.dmarket.p2p.tracker.engine

import com.dmarket.p2p.tracker.model.marketplace.DirectiveStatus

/** What a read of its offer says about a `cancel_offer` Steam refused. */
enum class FailedCancelKind(val wireName: String) {
    /** The offer is no longer active (`4`, `5`, `6`, `7`, `8`, `10`): nothing dangles, which is what the cancel was for. */
    ALREADY_INACTIVE("already_inactive"),

    /** The trade went through (`3` accepted, `11` in escrow): the cancel failed for real. */
    ALREADY_TRADED("already_traded"),

    /** The offer is still open (`2`, `9`) or in a state this build does not know: the failure stands. */
    STILL_OPEN("still_open"),

    /** The read failed, or Steam did not return the offer: nothing is known beyond the failure. */
    UNREAD("unread"),
}

/**
 * [kind] of a failed cancel, read off its offer's raw `ETradeOfferState` [offerState] (`null`: not read).
 *
 * [settled] when the offer can never be active again: another attempt could only fail the same way, so the cancel is
 * over for this device whatever it reports.
 */
data class FailedCancelVerdict(val kind: FailedCancelKind, val offerState: Int?) {
    val settled: Boolean get() = kind == FailedCancelKind.ALREADY_INACTIVE

    /** What the cancel reports: [FailedCancelCheck.ALREADY_INACTIVE_STATUS] for an offer already inactive, `FAILED` otherwise. */
    val status: DirectiveStatus
        get() = if (kind == FailedCancelKind.ALREADY_INACTIVE) FailedCancelCheck.ALREADY_INACTIVE_STATUS else DirectiveStatus.FAILED

    /**
     * The report's error: what the offer's state says, when it says why the cancel cannot succeed, and [failure] (the
     * cancel's own) otherwise. `null` on a report that is not a failure.
     */
    fun error(failure: String?): String? {
        if (status != DirectiveStatus.FAILED) return null
        val state = offerState ?: return failure
        return when (kind) {
            FailedCancelKind.ALREADY_INACTIVE, FailedCancelKind.ALREADY_TRADED -> FailedCancelCheck.describe(state)
            FailedCancelKind.STILL_OPEN, FailedCancelKind.UNREAD -> failure
        }
    }
}

/**
 * The pure half of telling why a `cancel_offer` failed. Steam answers a cancel of an offer that is no longer active
 * with an error (an HTTP 500 on dev), which alone reads like a network failure; the offer's own state tells them apart.
 */
object FailedCancelCheck {
    /**
     * What a cancel reports when its offer was already inactive. Not settled with DMarket: `SUCCESS` says the offer no
     * longer dangles, and DMarket stops re-issuing the cancel; `FAILED` with the state as its error changes nothing for
     * DMarket but the text. `FAILED` until DMarket says otherwise.
     */
    val ALREADY_INACTIVE_STATUS: DirectiveStatus = DirectiveStatus.FAILED

    private val INACTIVE = setOf(4, 5, 6, 7, 8, 10)
    private val TRADED = setOf(3, 11)

    /** The verdict for an offer read in [offerState]; `null` when it could not be read. */
    fun verdict(offerState: Int?): FailedCancelVerdict {
        val kind = when (offerState) {
            null -> FailedCancelKind.UNREAD
            in INACTIVE -> FailedCancelKind.ALREADY_INACTIVE
            in TRADED -> FailedCancelKind.ALREADY_TRADED
            else -> FailedCancelKind.STILL_OPEN
        }
        return FailedCancelVerdict(kind, offerState)
    }

    /** How an offer in [state] reads in a report: `offer already canceled (state 6)`, `offer in escrow (state 11)`, …. */
    internal fun describe(state: Int): String {
        val name = when (state) {
            3 -> "already accepted"
            4 -> "already countered"
            5 -> "already expired"
            6 -> "already canceled"
            7 -> "already declined"
            8 -> "already invalid: its items are gone"
            10 -> "already canceled by second factor"
            11 -> "in escrow"
            else -> "in an unexpected state"
        }
        return "offer $name (state $state)"
    }
}
