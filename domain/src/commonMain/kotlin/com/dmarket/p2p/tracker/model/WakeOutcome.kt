package com.dmarket.p2p.tracker.model

/**
 * How a wake ended, as the wake ack reports it (contract d455888 §6.4, `outcome`). The ack feeds only metrics,
 * `needsStatusUpdate` and T-11 (§4.3); a directive's own report decides any penalty.
 *
 * [kind] [WakeOutcomeKind.DEFERRED] means only the mobile proof gate held a proof (never on a `directive` wake);
 * [WakeOutcomeKind.FAILED] carries the [cause].
 */
data class WakeOutcome(val kind: WakeOutcomeKind, val cause: WakeCause? = null) {
    companion object {
        val DONE: WakeOutcome = WakeOutcome(WakeOutcomeKind.DONE)

        fun failed(cause: WakeCause): WakeOutcome = WakeOutcome(WakeOutcomeKind.FAILED, cause)
    }
}

/** `outcome` of the ack, as the route spells it. */
enum class WakeOutcomeKind(val wireName: String) {
    /** On receipt: the work started. As the final ack: it ran through. */
    DONE("done"),

    /** A proof waits for an unmetered network or the user's cellular option. */
    DEFERRED("deferred"),

    /** The work did not run through; [WakeOutcome.cause] says why. */
    FAILED("failed"),
}

/**
 * Why a wake did not run through. The contract's `cause` set is `cellular_not_allowed`, `steam_session`, `notary`,
 * `os_killed`, `other`; [wireName] maps each reason onto it.
 */
enum class WakeCause(val wireName: String) {
    /** The proof gate held a proof on a metered network ([WakeOutcomeKind.DEFERRED] only). */
    CELLULAR_NOT_ALLOWED("cellular_not_allowed"),

    /** No live Steam session for the linked account: gone, or another account's. */
    STEAM_SESSION("steam_session"),

    /** A proof the wake ran failed at the notary or the prover. */
    NOTARY("notary"),

    /** The host's time for the wake ran out, or the cycle hit its own deadline: the OS stops the work next. */
    BUDGET("os_killed"),

    /** DMarket did not answer: the network, a 5xx, a 429, the proof's delivery included. */
    NETWORK("other"),

    /** The DMarket session is gone. */
    AUTH("other"),

    /** Anything else, a crash in the cycle included. */
    OTHER("other"),
}
