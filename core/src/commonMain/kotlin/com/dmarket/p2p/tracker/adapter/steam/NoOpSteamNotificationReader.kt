package com.dmarket.p2p.tracker.adapter.steam

import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.port.steam.SteamNotificationReader
import kotlin.time.Instant

/**
 * The default [SteamNotificationReader]: never resolves an actor.
 *
 * Reversal attribution is opt-in. A host that does not wire a real reader simply reports history
 * rollbacks without an initiator, which the backend reads as "undecided" — the deal parks with escrow
 * untouched, which is the intended safe outcome. Keeps platforms without a concrete actual compiling.
 */
object NoOpSteamNotificationReader : SteamNotificationReader {
    override suspend fun reversalInitiator(credential: SteamCredential, counterparty: SteamId?, modifiedAt: Instant?): SteamId? = null
}
