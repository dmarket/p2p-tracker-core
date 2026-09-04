package com.dmarket.p2p.tracker.client.steam

import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.TradeId
import com.dmarket.p2p.tracker.model.steam.SteamNotification
import com.dmarket.p2p.tracker.model.steam.SteamOfferSnapshot
import com.dmarket.p2p.tracker.model.steam.SteamTransfer
import com.dmarket.p2p.tracker.wire.trackerJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Instant

/**
 * Pure parsing/mapping of Steam `IEconService` + community response bodies, shared by
 * `KtorSteamReadClient` and `KtorSteamInventoryReader` on every platform.
 *
 * Every Steam read now flows through Ktor: on the browser the JS engine sets fetch
 * `credentials:"include"` (see `platformHttpEngine`) so the logged-in Steam cookie session rides
 * along — a cookie-less request is what Steam 403s. Keeping the response mapping here means all
 * targets share one tested code path and the only difference between them is how the bytes are fetched.
 */
internal object SteamReadResponses {

    val json: Json = trackerJson { ignoreUnknownKeys = true }

    /** Single-offer `GetTradeOffer`: its axis snapshot, or `null` when Steam doesn't know the id. */
    fun singleOfferSnapshot(body: String): SteamOfferSnapshot? =
        json.decodeFromString<GetTradeOfferWrapper>(body).response?.offer?.toSnapshot()

    /**
     * Bulk `GetTradeOffers` (both directions requested): map of offer id → its axis snapshot, over the
     * account's **sent and received** offers alike.
     *
     * Both are needed because a watched deal can be one this account is buying — its offer is a received
     * one — and a sent-only list omits it entirely. Offer ids are unique account-wide, so merging the two
     * arrays cannot collide. The caller narrows the result to the ids it is watching, so offers unrelated
     * to a DMarket deal are discarded here and never looked at.
     */
    fun bulkOfferSnapshots(body: String): Map<OfferId, SteamOfferSnapshot> {
        val response = json.decodeFromString<GetTradeOffersWrapper>(body).response ?: return emptyMap()
        return (response.sent + response.received).associate { OfferId(it.tradeOfferId) to it.toSnapshot() }
    }

    /**
     * The offer axis of one offer: its raw state plus the `tradeid` Steam attaches on acceptance — the id of
     * the `GetTradeHistory` row this offer produced, and therefore the correlation key the history axis wants.
     */
    private fun SteamOfferDto.toSnapshot(): SteamOfferSnapshot =
        SteamOfferSnapshot(state = state, tradeId = tradeId?.takeIf { it.isNotBlank() && it != "0" }?.let(::TradeId))

    /**
     * `GetTradeHistory`: the recent transfers with their raw `ETradeStatus` codes, in payload order.
     *
     * **Both asset directions are folded into one set.** Steam splits a record's items into
     * `assets_given` and `assets_received`, and a rollback's compensating record mirrors the *original's*
     * direction rather than the return movement — so a reversal recorded on the receive side would map to
     * an empty asset set and never correlate to its deal at all, silencing that deal's history axis for
     * good. Direction carries no correlation value; presence does.
     */
    fun transfers(body: String): List<SteamTransfer> {
        val trades = json.decodeFromString<GetTradeHistoryWrapper>(body).response?.trades ?: return emptyList()
        return trades.map { trade ->
            val assets = trade.assetsGiven + trade.assetsReceived
            SteamTransfer(
                partnerSteamId = trade.steamIdOther.takeIf { it.isNotBlank() }?.let(::SteamId),
                assetIds = assets.mapTo(mutableSetOf()) { AssetId(it.assetId) },
                // Every identity number Steam published for these assets, so the correlation can check a
                // compound DMarket asset ref against the row without assuming the ref's layout.
                assetTokens = assets.flatMapTo(mutableSetOf()) {
                    listOfNotNull(it.assetId, it.appId.toString(), it.contextId, it.classId, it.instanceId)
                        .filter { token -> token.isNotBlank() }
                },
                status = trade.status,
                tradeId = trade.tradeId.takeIf { it.isNotBlank() }?.let(::TradeId),
                // Non-positive is treated as absent rather than epoch 0: an invented 1970 timestamp would
                // match nothing and read as a real answer.
                initiatedAt = trade.timeInit.takeIf { it > 0L }?.let(Instant::fromEpochSeconds),
                modifiedAt = trade.timeMod?.content?.toLongOrNull()?.takeIf { it > 0L }?.let(Instant::fromEpochSeconds),
                rollbackTradeId = trade.rollbackTrade?.takeIf { it.isNotBlank() }?.let(::TradeId),
                // Same non-positive-is-absent rule, and here it is also the contract: the backend takes an
                // omitted settlement time as "no window read", and an epoch 0 as a window that expired in 1970.
                settlementAt = trade.timeSettlement?.content?.toLongOrNull()?.takeIf { it > 0L }?.let(Instant::fromEpochSeconds),
            )
        }
    }

    /**
     * `GetSteamNotifications`: the notifications reduced to the three fields reversal attribution needs.
     * Entries missing any of the three are dropped — an unusable candidate must not become a loose match.
     * Everything else in the payload (notification bodies and unrelated traffic) is discarded here.
     */
    fun notifications(body: String): List<SteamNotification> {
        val entries = json.decodeFromString<GetSteamNotificationsWrapper>(body).response?.notifications ?: return emptyList()
        return entries.mapNotNull { entry ->
            val type = entry.notificationType?.content?.toIntOrNull() ?: return@mapNotNull null
            val actor = entry.actor?.content?.toLongOrNull() ?: return@mapNotNull null
            val timestamp = entry.timestamp?.content?.toLongOrNull()?.takeIf { it > 0L } ?: return@mapNotNull null
            SteamNotification(type = type, actorAccountId = actor, timestamp = Instant.fromEpochSeconds(timestamp))
        }
    }

    /**
     * Community `/inventory/{steamid}/{appid}/{ctx}`: one page of present (non-blank) asset ids plus
     * Steam's paging cursor. See [SteamInventoryPage.usable] for why emptiness alone is not trusted.
     */
    fun inventoryPage(body: String): SteamInventoryPage {
        val response = json.decodeFromString<SteamInventoryResponse>(body)
        val assetIds = response.assets
            .mapNotNull { asset -> asset.assetId.takeIf { it.isNotBlank() }?.let(::AssetId) }
            .toSet()
        // A POSITIVE completeness signal. `success == 1` alone is not enough: Steam answers some
        // rate-limit/private-inventory shapes with a 200 that carries no `assets` key at all, which
        // would otherwise decode to an empty list and read as "the seller owns nothing" — licence for
        // the backend to cancel their entire on-sale set. So require either some assets or an explicit
        // `total_inventory_count: 0` (a genuinely empty inventory, which IS a complete scan).
        val usable = response.success.isTruthy() && (assetIds.isNotEmpty() || response.totalInventoryCount == 0)
        return SteamInventoryPage(
            assetIds = assetIds,
            moreItems = response.moreItems.isTruthy(),
            lastAssetId = response.lastAssetId?.takeIf { it.isNotBlank() },
            usable = usable,
        )
    }

    /**
     * Coerce a community-endpoint flag that Steam may encode as `1`, `"1"` or `true`. Anything absent or
     * unrecognised is falsy — callers treat a missing `success` as unusable and a missing `more_items` as
     * "no further pages", both of which fail safe.
     */
    private fun JsonPrimitive?.isTruthy(): Boolean = when (this?.content) {
        null -> false
        "1", "true" -> true
        else -> false
    }
}

/**
 * One decoded page of the community inventory read.
 *
 * @property usable whether the body is a trustworthy inventory page at all. `false` means the response
 *   decoded but says nothing dependable about what the seller owns (no `success`, or no `assets` key
 *   without an explicit zero count) — the caller must report an incomplete scan rather than an empty one.
 * @property moreItems Steam's truncation flag; when set, the next page starts at [lastAssetId].
 */
internal data class SteamInventoryPage(val assetIds: Set<AssetId>, val moreItems: Boolean, val lastAssetId: String?, val usable: Boolean)
