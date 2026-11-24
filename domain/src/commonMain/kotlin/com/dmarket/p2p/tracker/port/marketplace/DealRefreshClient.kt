package com.dmarket.p2p.tracker.port.marketplace

import com.dmarket.p2p.tracker.model.DealId
import kotlin.time.Instant

/**
 * The server permit for a manual "Update status", `POST /exchange/v1/p2p/deals/{id}/refresh` (DMA-963). A refusal is
 * thrown as the client's usual errors: a 429 with its `Retry-After` and gateway code, a 404 or 503 with its status.
 */
fun interface DealRefreshClient {
    suspend fun refreshDeal(id: DealId): DealRefresh
}

/**
 * A 200 from the refresh.
 *
 * @property tracked the answer carried the caller's watch entry: the permit was taken. `false` = nothing to prove for
 *   the caller (a closed deal, or no Steam offer yet); such a refresh is not counted.
 * @property nextAllowedAt when the next refresh of this deal is allowed; `null` when no wait is known.
 */
data class DealRefresh(val tracked: Boolean, val nextAllowedAt: Instant? = null)
