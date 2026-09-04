package com.dmarket.p2p.tracker.model.steam

import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.TradeId
import kotlin.time.Instant

/**
 * One recent Steam trade transfer as read from `GetTradeHistory` — the **transfer axis**
 * (`ETradeStatus`). The trade-history payload is not keyed by trade-offer id, so the loop correlates a
 * transfer to a watched deal by the disclosed [assetIds] and the [partnerSteamId] (the buyer). That
 * correlation is [com.dmarket.p2p.tracker.engine.TransferCorrelation] — not a bare first-match, because
 * a rollback writes a second row that mirrors the deal's own assets.
 *
 * @property assetIds every asset this record moved, in **either** direction. Steam splits them into
 *   `assets_given` / `assets_received`, but the compensating record of a rollback mirrors the
 *   *original's* direction rather than the return movement, so direction carries no correlation value
 *   and both sides are folded into one set.
 * @property assetTokens every identity number Steam published for this record's assets — `assetid`, `appid`,
 *   `contextid`, `classid`, `instanceid` — as strings, in one set. DMarket's asset ref is built out of these
 *   (the live shape is `instanceid:classid:assetid:appid`), and the correlation compares the ref's parts
 *   against this set rather than assuming where in the ref the asset id sits: a layout change then cannot
 *   silently lift the wrong field. Empty simply means "nothing to corroborate with", never "no match" — see
 *   [com.dmarket.p2p.tracker.engine.TransferCorrelation].
 * @property status raw `ETradeStatus` integer (the backend re-derives settlement from the proof).
 * @property tradeId Steam's own id for this transfer record, when present.
 * @property initiatedAt Steam's `time_init`. The tiebreak when one asset legitimately carries several
 *   rows — an asset returns under its original id after a rollback and may be sold again — so the
 *   correlation does not have to trust the payload's ordering.
 * @property modifiedAt Steam's `time_mod`, set on a row that was rolled back (and cleared of
 *   `time_settlement`). Reversal attribution keys on it: the notification that names who rolled a trade
 *   back is matched by exact timestamp equality against this value. `null` means attribution cannot be
 *   attempted for this transfer.
 * @property rollbackTradeId Steam's `rollback_trade` — the trade this record rolls back. Present only on
 *   the **compensating** record a rollback adds, which is what makes it the discriminator: a row that
 *   carries it is a reference to what was undone, never a transfer of the deal in hand.
 * @property settlementAt Steam's `time_settlement` — the end of this transfer's Trade-Protection window,
 *   reported onward as `settlementTime` on the history axis. `null` means "no window on this read", never
 *   "no window": Steam clears the field on the row it flips to `12` (the same flip that sets [modifiedAt]),
 *   so a rollback structurally carries none and the window can only be captured from an earlier read.
 *   Non-positive is mapped to `null` rather than epoch 0 — an invented 1970 window would read as a real,
 *   long-expired answer.
 */
data class SteamTransfer(
    val partnerSteamId: SteamId?,
    val assetIds: Set<AssetId>,
    val assetTokens: Set<String> = emptySet(),
    val status: Int,
    val tradeId: TradeId? = null,
    val initiatedAt: Instant? = null,
    val modifiedAt: Instant? = null,
    val rollbackTradeId: TradeId? = null,
    val settlementAt: Instant? = null,
)
