package com.dmarket.p2p.tracker.engine

import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.TradeId
import com.dmarket.p2p.tracker.model.steam.SteamTransfer
import kotlin.time.Instant

/**
 * Picks the one `GetTradeHistory` row that is a watched deal's **own** transfer, out of the
 * account-wide history read.
 *
 * Two keys, in order of preference. [selectByTradeId] is the real one: Steam's `tradeid` **is** the row's
 * identity, and the offer axis already carries it once the offer is accepted, so the join is exact and free.
 * [select] is the fallback for a deal whose offer Steam no longer lists — it correlates by DMarket's asset
 * ref, which is weaker (an asset can carry several rows) and needs a `/p2p/deals/{id}` read to learn the ref
 * at all.
 *
 * This exists because a rollback is **two records, not one state change**. Steam flips the deal's own
 * row to `status 12` (setting `time_mod`, clearing `time_settlement`) *and additionally* writes a new
 * `status 3` record carrying `rollback_trade` — and that compensating record mirrors the **original's**
 * assets, so it collides with the deal on the very field the correlation keys on. A bare first-match
 * over the payload therefore returns the compensating record and reads a reversal as an ordinary
 * completion: the `12` is deduped away, or — when the completion had not been reported yet — actively
 * replaced by a positive `Complete(3)`, which is the backend's payout condition.
 *
 * `rollback_trade` is what makes rule 1 possible: it is the only field that tells a compensating record
 * apart from a real transfer.
 */
object TransferCorrelation {

    /** `ETradeOfferState.Accepted` — the offer axis's "the items moved" state. */
    private const val OFFER_ACCEPTED = 3

    /**
     * The deal's own transfer, correlated by Steam's own [tradeId] — the **primary** correlation, and the one
     * to prefer wherever the id is available.
     *
     * `tradeid` is the history row's identity, and the offer axis hands it over for free: Steam attaches it to
     * the trade offer the moment the offer is accepted, and the loop already reads that offer every cycle for
     * its state ([com.dmarket.p2p.tracker.model.steam.SteamOfferSnapshot.tradeId]). So this join needs no
     * asset id, no DMarket asset-ref layout, and no `/p2p/deals/{id}` read — and it is exact, so there is
     * nothing to disambiguate: a rollback's compensating record has its **own** `tradeid` and merely *names*
     * this one in `rollback_trade`, which the filter drops anyway.
     */
    fun selectByTradeId(transfers: List<SteamTransfer>, tradeId: TradeId): SteamTransfer? =
        transfers.firstOrNull { it.tradeId == tradeId && it.rollbackTradeId == null }

    /**
     * Whether the offer axis says a transfer record for this deal must already exist in Steam's history.
     *
     * The discriminator between the two reasons [select] returns `null`: a deal whose offer is still Active
     * or awaiting confirmation simply has no transfer yet (ordinary silence), while an **accepted** offer
     * whose row cannot be found means the join itself failed — the correlation key is wrong or the row fell
     * outside the read window. Only the second is worth reporting, re-keying, and acting on.
     */
    fun isTransferDue(offerState: Int?): Boolean = offerState == OFFER_ACCEPTED

    /**
     * The parts of a DMarket asset ref, as a set.
     *
     * `Deal.assetId` is **not** a bare Steam id: the golden contract only says a p2p `asset_id` is "a
     * Steam-asset ref, not a UUID", and the live backend serves it as a compound of the Steam identity
     * numbers — observed as `instanceid:classid:assetid:appid`, e.g. `143865972:8490849127:51978272353:730`
     * for the row whose `assetid` is `51978272353`, `classid` `8490849127`, `instanceid` `143865972`, `appid`
     * `730`. Correlating with the ref verbatim matched nothing, so the transfer axis of every
     * history-watched deal was silently blind — which is how a trade-protection rollback went unreported.
     *
     * Deliberately a **set**, not a parse: the layout is not in the frozen contract, so reading the
     * `assetid` out of a fixed position would break — silently, or worse by lifting a neighbouring field —
     * the moment the backend reorders it, drops a part, or adds one. [identifies] therefore never asks
     * *where* the asset id is; it asks whether the row's own asset id is **among** these parts.
     */
    private fun refParts(assetRef: AssetId): Set<String> = assetRef.value.split(':').filterTo(mutableSetOf()) { it.isNotEmpty() }

    /**
     * Whether [refParts] names one of this row's assets — the row's `assetid` appears among them.
     *
     * That is the whole rule, and it is what makes the correlation independent of the ref's layout: a bare
     * `assetid`, the live four-part ref, a reordered one, and one that grows or loses a part all satisfy it
     * identically. Steam's own identity fields ([SteamTransfer.assetTokens]) are used only to *corroborate* —
     * see [select] — never as a precondition, so a ref carrying a number Steam does not publish still
     * correlates rather than silently failing.
     */
    private fun SteamTransfer.identifies(refParts: Set<String>): Boolean = assetIds.any { it.value in refParts }

    /**
     * Whether every part of the ref is a number Steam published for this row's assets. Corroboration: it
     * tells a row the ref genuinely describes apart from one that merely shares a number with it.
     */
    private fun SteamTransfer.corroborates(refParts: Set<String>): Boolean = assetTokens.isNotEmpty() && assetTokens.containsAll(refParts)

    /**
     * The deal's own transfer among [transfers], correlated by [assetId], or `null` when none matches.
     *
     * [assetId] may be a bare Steam `assetid` or a DMarket asset ref of any layout — see [refParts]. The
     * normalisation lives here, not at the call site, so no caller can correlate against a ref by mistake.
     *
     * 1. A row carrying [SteamTransfer.rollbackTradeId] is a compensating record — a reference to what
     *    was undone, never a transfer of the deal in hand — so it can never be the answer. When it is
     *    the *only* match (its original fell outside the history window) the answer is `null`: no
     *    observation at all, rather than a `Complete(3)` that asserts the opposite of what happened.
     * 2. Rows the ref fully [corroborates] are preferred over rows that merely [identifies] — but only when
     *    there are any: corroboration narrows a genuine ambiguity, and must never turn a match into no match
     *    (a ref part Steam does not publish would otherwise silence the axis, which is the failure mode this
     *    whole object exists to end).
     * 3. Of the remaining rows the **most recently initiated** wins. An asset returns under its original
     *    id after a rollback and may be sold again, so one asset can legitimately carry several rows and
     *    the newest is the deal in hand. A row with no `time_init` loses to any row that has one, and
     *    ties keep the earliest-listed — Steam lists newest first — so the verdict is deterministic
     *    without depending on the payload's order being what we assume.
     */
    fun select(transfers: List<SteamTransfer>, assetId: AssetId): SteamTransfer? {
        val parts = refParts(assetId)
        val named = transfers.filter { it.rollbackTradeId == null && it.identifies(parts) }
        val candidates = named.filter { it.corroborates(parts) }.ifEmpty { named }
        return candidates.maxWithOrNull(compareBy(nullsFirst<Instant>()) { it.initiatedAt })
    }
}
