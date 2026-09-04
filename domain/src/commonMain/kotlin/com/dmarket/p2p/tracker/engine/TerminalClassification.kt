package com.dmarket.p2p.tracker.engine

/**
 * The client-side outcome of reading a Steam trade's **two axes** — `ETradeOfferState` (the offer
 * lifecycle, 1–11) and `ETradeStatus` (the transfer/history axis) — to decide whether the trade has
 * reached a terminal state (R9).
 *
 * The client does **not** decide settlement: the TERMINAL trade-event proof carries the raw axes and
 * the backend re-derives the money outcome. The classifier's job is narrower — answer *"is this
 * trade terminal yet, or do I keep watching?"* ([isTerminal]) — and carry a best-effort label for
 * logging/UX. When in doubt it returns a **non-terminal** verdict so the client keeps watching and a
 * backend deadline (not a client guess) resolves a stuck trade.
 */
enum class TerminalOutcome {
    /** Still in flight (Active, CreatedNeedsConfirmation, or accepted-but-transfer-not-yet-final). */
    PENDING,

    /** `InEscrow` (offer state 11): on hold; keep watching until the hold clears. */
    IN_ESCROW_HOLD,

    /** Offer accepted (3) **and** transfer Complete — the happy terminal. */
    COMPLETE,

    /** Buyer never accepted: Expired / Declined / Countered / Canceled / CanceledBySecondFactor. */
    EXPIRED_OR_DECLINED,

    /** `InvalidItems` (offer state 8): the seller's item left the inventory — seller fault. */
    INVALID_ITEMS,

    /** A hard transfer failure (offer Invalid, or accepted-but-transfer Failed). */
    FAILED,

    /** Unrecognised axis combination — treated as non-terminal (keep watching). */
    UNKNOWN,
    ;

    /** Terminal outcomes let the loop stop watching and fire the TERMINAL trade-event. */
    val isTerminal: Boolean
        get() = this == COMPLETE || this == EXPIRED_OR_DECLINED || this == INVALID_ITEMS || this == FAILED
}

/**
 * Pure classifier for the two Steam trade axes. Encodes the R9 table against the **standard**
 * `ETradeOfferState` (which tops out at 11; a "13" can only appear on the `ETradeStatus` axis —
 * see [ETradeStatus]).
 */
object TerminalClassification {
    // ETradeOfferState (offer axis)
    private const val OFFER_INVALID = 1
    private const val OFFER_ACTIVE = 2
    private const val OFFER_ACCEPTED = 3
    private const val OFFER_COUNTERED = 4
    private const val OFFER_EXPIRED = 5
    private const val OFFER_CANCELED = 6
    private const val OFFER_DECLINED = 7
    private const val OFFER_INVALID_ITEMS = 8
    private const val OFFER_NEEDS_CONFIRMATION = 9
    private const val OFFER_CANCELED_BY_2FA = 10
    private const val OFFER_IN_ESCROW = 11

    // ETradeStatus (transfer axis). Only Complete/Failed are decisive; e.g. "13" is non-decisive here
    // (its exact meaning is unconfirmed — plan Open Decision #8 — so it falls through to PENDING).
    private const val TRADE_STATUS_COMPLETE = 3
    private const val TRADE_STATUS_FAILED = 4

    fun classify(offerState: Int, tradeStatus: Int): TerminalOutcome = when (offerState) {
        OFFER_ACTIVE, OFFER_NEEDS_CONFIRMATION -> TerminalOutcome.PENDING
        OFFER_IN_ESCROW -> TerminalOutcome.IN_ESCROW_HOLD
        OFFER_ACCEPTED -> when (tradeStatus) {
            TRADE_STATUS_COMPLETE -> TerminalOutcome.COMPLETE
            TRADE_STATUS_FAILED -> TerminalOutcome.FAILED
            else -> TerminalOutcome.PENDING // accepted, transfer still settling
        }
        OFFER_INVALID_ITEMS -> TerminalOutcome.INVALID_ITEMS
        OFFER_EXPIRED, OFFER_CANCELED, OFFER_DECLINED, OFFER_COUNTERED, OFFER_CANCELED_BY_2FA ->
            TerminalOutcome.EXPIRED_OR_DECLINED
        OFFER_INVALID -> TerminalOutcome.FAILED
        else -> TerminalOutcome.UNKNOWN
    }
}
