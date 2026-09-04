package com.dmarket.p2p.tracker.port.steam

import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import kotlin.time.Instant

/**
 * Resolves **who reversed a trade**, for a history status-12 (trade-protection rollback) report.
 *
 * The Steam trade record itself names nobody — both parties see identical rows — so the actor can only
 * come from the account's notification stream. That stream has no server-side type filter, which is why
 * this port is shaped as narrowly as possible: it returns **only the resolved [SteamId]**, never the
 * notifications. The broad payload is mapped and discarded at the IO edge, so no other component in the
 * library can observe unrelated personal traffic, and the implementation must suppress response-body
 * capture so it cannot reach a host [com.dmarket.p2p.tracker.port.host.NetworkObserver] either.
 *
 * Read-only, and on the Steam side of the audit boundary: it takes the device-only [SteamCredential] and
 * never touches the marketplace.
 *
 * `null` is the normal, expected answer. Steam signs out whoever performed a rollback, so the read often
 * simply fails; the selection rule is also deliberately intolerant (see
 * [com.dmarket.p2p.tracker.model.steam.ReversalAttribution]). Callers must send nothing on `null` — never
 * a fallback actor — because absence is interpreted downstream as "undecided", which parks the deal with
 * escrow untouched rather than moving money the wrong way.
 */
interface SteamNotificationReader {
    /**
     * The steamid64 that reversed the trade whose record has [modifiedAt] (`time_mod`) and counterparty
     * [counterparty], or `null` when it cannot be determined unambiguously.
     */
    suspend fun reversalInitiator(credential: SteamCredential, counterparty: SteamId?, modifiedAt: Instant?): SteamId?
}
