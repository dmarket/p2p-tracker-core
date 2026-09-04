package com.dmarket.p2p.tracker.notary

import com.dmarket.p2p.tracker.model.marketplace.TradeStatusSource

/**
 * Every Steam endpoint this client talks to, as the key a TLSN proof is requested by.
 *
 * **Why an enum rather than the two axes.** Proof requests used to be keyed on [TradeStatusSource], which
 * has exactly two values — so only two Steam endpoints could ever be proven, and the other eight the client
 * calls had no proven-read definition at all. Turning one of them on was a code change (a new `when` branch
 * plus a new config field), not a configuration change. This enum is the registry key that makes it a
 * configuration change: every entry resolves to a
 * [com.dmarket.p2p.tracker.config.ProvenRead] through `NotaryConfig.provenRead`, and
 * `NotaryConfig.enabledReads` decides which of them may actually be spent on.
 *
 * The entries mirror the reads in `SteamEndpointsConfig` plus the two community write surfaces, one to one.
 * Nothing else may be added without a definition in [ProvenReadCatalog] — `ProvenReadCatalogTest` iterates
 * `entries` precisely so "add a kind, forget the definition" fails the build rather than the first proof.
 *
 * **Deliberately carries almost no metadata of its own.** Whether a kind is a write, which host it addresses
 * and how it authenticates are all facts of its [com.dmarket.p2p.tracker.config.ProvenRead] — read them from
 * there (`ProvenRead.isWrite`, `serverName`, `auth`) rather than restating them here. An enum flag saying
 * "this is a write" alongside a definition saying `method = "POST"` is two sources of truth for one fact, and
 * the one that would drift is the one the double-write guard in `TradeTrackerLoop` reads.
 *
 * @property dealScoped whether a proof of this read can bind a
 *   [com.dmarket.p2p.tracker.model.DealId], and therefore whether it has a submission channel at all:
 *   `POST /notary` takes `{dealId, proofPayload}`, so a proof of an account-wide document (the offer list,
 *   a profile, the notification stream, an inventory page) is *producible* but has nowhere to go. Those are
 *   returned to the caller instead of submitted; see `NotaryProver.proveRead`. This one is **not** derivable
 *   from the read — it is a property of the backend's submission contract, not of the Steam request.
 */
enum class ProvenReadKind(val dealScoped: Boolean) {
    /** `IEconService/GetTradeOffer` — one offer by `tradeofferid`. The offer axis's proven read. */
    TRADE_OFFER(dealScoped = true),

    /** `IEconService/GetTradeOffers` — the account-wide offer list, both directions. Binds no single deal. */
    TRADE_OFFERS(dealScoped = false),

    /** `IEconService/GetTradeHistory` — the transfer axis as the polling path reads it (newest rows first). */
    TRADE_HISTORY(dealScoped = false),

    /** `IEconService/GetTradeStatus` — one trade by `tradeid`. The history axis's proven read. */
    TRADE_STATUS(dealScoped = true),

    /** `ISteamUser/GetPlayerSummaries` — public profile (nickname, avatar) for one account. */
    PLAYER_SUMMARIES(dealScoped = false),

    /** `IPlayerService/GetSteamLevel` — account level for one account; empty for a private profile. */
    STEAM_LEVEL(dealScoped = false),

    /** `ISteamNotificationService/GetSteamNotifications` — the read that can name who reversed a trade. */
    STEAM_NOTIFICATIONS(dealScoped = false),

    /** Community `/inventory/{steamid}/{appid}/{ctx}` — the seller's own inventory. Cookie-authenticated. */
    OWN_INVENTORY(dealScoped = false),

    /** Community `/tradeoffer/new/send` — the create write. Cookie-authenticated POST. */
    CREATE_OFFER(dealScoped = true),

    /** Community `/tradeoffer/{id}/cancel` — the cancel write. Cookie-authenticated POST. */
    CANCEL_OFFER(dealScoped = true),
}

/**
 * The proven read a trade-status axis is witnessed by, and the reason the two trade axes still work exactly
 * as they did before [ProvenReadKind] existed.
 *
 * [TradeStatusSource.HISTORY] maps to [ProvenReadKind.TRADE_STATUS] rather than the
 * [ProvenReadKind.TRADE_HISTORY] the polling path actually reads, and that asymmetry is deliberate: the
 * prover's reveal-path syntax has no filters or wildcards, so a row index has to be knowable *before* the
 * read. `GetTradeHistory` answers with up to 50 rows (51 observed) in an order this client does not choose,
 * so no index is addressable; `GetTradeStatus` answers for one trade, making `response.trades.0` a path that
 * means something.
 *
 * Consequence worth knowing, and it is not new: on the history axis the polled read and the proven read are
 * different endpoints, so a code that advances between the two moments is reported as one value and proven as
 * another. Enabling [ProvenReadKind.TRADE_HISTORY] does not fix that by itself — its catalog entry has to
 * pin the response to a single row to be addressable at all.
 */
val TradeStatusSource.defaultProvenReadKind: ProvenReadKind
    get() = when (this) {
        TradeStatusSource.OFFER -> ProvenReadKind.TRADE_OFFER
        TradeStatusSource.HISTORY -> ProvenReadKind.TRADE_STATUS
    }
