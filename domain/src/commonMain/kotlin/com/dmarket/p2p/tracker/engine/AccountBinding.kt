package com.dmarket.p2p.tracker.engine

import com.dmarket.p2p.tracker.model.SteamId

/** The relationship between the DMarket-linked Steam id and the Steam id of the token the client holds. */
enum class AccountBindingStatus {
    /** The two ids agree — the browser is logged into the account the DMarket profile is linked to. */
    MATCH,

    /** The two ids differ — a wrong-account session. All Steam activity must be blocked. */
    MISMATCH,

    /** No expected id was supplied (older backend / the FE hasn't sent it) — treated as "no opinion". */
    UNKNOWN,
}

/**
 * Pure, zero-IO decision comparing the DMarket account's linked Steam id ([expected], from the
 * `/heartbeat` response or an FE `postMessage`) against the Steam id of the token the client actually
 * holds ([token], `SteamCredential.subjectSteamId`).
 *
 * A `null` [expected] is [AccountBindingStatus.UNKNOWN], never a mismatch — the guard fails open so an
 * older backend or a not-yet-updated FE never blocks a legitimate session. Only a present-and-different
 * id is a [AccountBindingStatus.MISMATCH].
 */
object AccountBinding {
    fun evaluate(expected: SteamId?, token: SteamId): AccountBindingStatus = when {
        expected == null -> AccountBindingStatus.UNKNOWN
        expected == token -> AccountBindingStatus.MATCH
        else -> AccountBindingStatus.MISMATCH
    }
}
