package com.dmarket.p2p.tracker.notary

import com.dmarket.p2p.tracker.config.ProvenRead
import com.dmarket.p2p.tracker.config.ProvenReadAuth

/**
 * One [ProvenRead] definition per [ProvenReadKind] the two named `NotaryConfig` fields do not already cover.
 *
 * **The point of the catalog is that there is no such thing as an unprovable endpoint.** Before it, enabling a
 * proof on a Steam call the client already made meant writing a `when` branch and a config field; now it means
 * adding the kind to `NotaryConfig.enabledReads`. `ProvenReadCatalogTest` iterates `ProvenReadKind.entries`
 * against `NotaryConfig.provenRead`, so an enum case with no definition fails the build rather than the first
 * proof that needs it.
 *
 * `TRADE_OFFER` and `TRADE_STATUS` are deliberately **absent**: they live on as `NotaryConfig.offerRead` /
 * `historyRead`, which are positional `@JsExport` constructor slots a host may already be overriding.
 * `NotaryConfig.provenRead` resolves those two from the named fields and everything else from here, so a host
 * override keeps winning and nothing about the two live axes changes.
 *
 * **Paths mirror `SteamEndpointsConfig` defaults.** They are duplicated rather than derived because these
 * templates additionally pin query parameters the *polling* path does not (`max_trades=1`, `count=1`) — a
 * proven read has to be addressable by a filterless reveal path, which the polled read never had to be. Where
 * a template narrows the request, the KDoc says why.
 */
object ProvenReadCatalog {

    /** Where every token-authed proven read lives. */
    private const val API_HOST = "api.steampowered.com"

    /** Where the cookie-authed reads and both writes live. */
    private const val COMMUNITY_HOST = "steamcommunity.com"

    /**
     * The bulk offer list, **both directions** — the read the polling path actually issues once more than one
     * offer is tracked.
     *
     * ⚠️ A proof of this binds **the account's offer list**, not one offer: the reveal path names the whole
     * `response`, because a filterless path syntax cannot address "the row for offer X" in a list whose order
     * this client does not choose. Use [ProvenReadKind.TRADE_OFFER] to bind a single offer. The recv cap is
     * raised well past the global because the row count is unbounded — and a response over the cap fails the
     * proof outright.
     */
    private fun tradeOffers() = ProvenRead(
        serverName = API_HOST,
        pathTemplate = "/IEconService/GetTradeOffers/v1/?get_sent_offers=1&get_received_offers=1" +
            "&active_only=0&get_descriptions=0&access_token=$TOKEN_PLACEHOLDER",
        revealJsonPaths = listOf("response"),
        maxRecvDataOverride = 262_144,
    )

    /**
     * The transfer axis as the polling path reads it.
     *
     * **`max_trades=1` is load-bearing, not a saving.** `GetTradeHistory` answers with up to 50 rows (51
     * observed) and the reveal-path syntax has no filters or wildcards, so no row index is knowable before the
     * read — `response.trades.0` only means "the trade this proof is about" when the response holds exactly
     * one row. Even then it is the *newest* row, not a chosen one, which is why
     * [ProvenReadKind.TRADE_STATUS] remains the history axis's default proven read.
     */
    private fun tradeHistory() = ProvenRead(
        serverName = API_HOST,
        pathTemplate = "/IEconService/GetTradeHistory/v1/?max_trades=1&get_descriptions=0&access_token=$TOKEN_PLACEHOLDER",
        revealJsonPaths = listOf("response.trades.0"),
    )

    /** Public profile for the counterparty — nickname and avatars, one account per proof. */
    private fun playerSummaries() = ProvenRead(
        serverName = API_HOST,
        pathTemplate = "/ISteamUser/GetPlayerSummaries/v2/?steamids=$PARTNER_STEAM_ID&access_token=$TOKEN_PLACEHOLDER",
        revealJsonPaths = listOf("response.players.0"),
    )

    /** Steam account level for the counterparty. Answers an empty `response` for a private profile. */
    private fun steamLevel() = ProvenRead(
        serverName = API_HOST,
        pathTemplate = "/IPlayerService/GetSteamLevel/v1/?steamid=$PARTNER_STEAM_ID&access_token=$TOKEN_PLACEHOLDER",
        revealJsonPaths = listOf("response"),
    )

    /**
     * The notification stream — the read that can name who reversed a trade, which is the one fact a history
     * `12` report cannot carry on its own.
     *
     * `include_read` and `include_hidden` are mandatory rather than optional: reading a notification sets
     * `read` and dismissing it sets `hidden`, so omitting either empties the response for exactly the
     * notifications worth proving.
     */
    private fun steamNotifications() = ProvenRead(
        serverName = API_HOST,
        pathTemplate = "/ISteamNotificationService/GetSteamNotifications/v1/?include_read=true" +
            "&include_hidden=true&language=english&access_token=$TOKEN_PLACEHOLDER",
        revealJsonPaths = listOf("response"),
        maxRecvDataOverride = 65_536,
    )

    /**
     * One page of the seller's own inventory, cookie-authenticated on the community host.
     *
     * **`count=1` with `start_assetid={assetId}` is the whole design.** The polling path requests 2000 items a
     * page; a page that size is orders of magnitude past any sane transcript cap, and exceeding the cap fails
     * the proof. So a proven inventory read proves **one addressed asset**: the cursor names it and the page
     * holds it alone. That is the provable claim — "this asset is in this inventory" — and it is not the same
     * claim as the `report_inventory` snapshot, which is a whole-scan diff the backend computes.
     *
     * The send cap is raised because a cookie header carrying `steamLoginSecure` dwarfs the token-authed
     * request this library was originally sized for.
     */
    private fun ownInventory() = ProvenRead(
        serverName = COMMUNITY_HOST,
        pathTemplate = "/inventory/$SUBJECT_STEAM_ID/$APP_ID/$CONTEXT_ID?l=english&count=1&start_assetid=$ASSET_ID",
        revealJsonPaths = listOf("assets.0"),
        auth = ProvenReadAuth.SESSION_COOKIE,
        maxSentDataOverride = 2_048,
        maxRecvDataOverride = 65_536,
    )

    /**
     * The create write. **Enabling this makes the prover perform the create** — TLSN requires the prover to be
     * the TLS client, so there is no way to witness the POST `FetchSteamOfferCreator` already made.
     *
     * No `declarativeNetRequest` rule is involved, unlike the web actual: the prover sets its own `Referer`
     * and `Origin` directly, which is what that rule exists to work around. Steam validates the `Referer`'s
     * `partner` against the body's, so the mapper builds both from one binding.
     *
     * The send cap covers the cookie header plus the two JSON documents; the global 1024 is sized for
     * `196 + len(token)` and would fail every create.
     */
    private fun createOffer() = ProvenRead(
        serverName = COMMUNITY_HOST,
        pathTemplate = "/tradeoffer/new/send",
        revealJsonPaths = listOf("tradeofferid"),
        auth = ProvenReadAuth.SESSION_COOKIE,
        method = "POST",
        bodyTemplate = SteamWriteBody.createOfferFormTemplate(),
        refererTemplate = SteamWriteBody.createOfferReferer("https://$COMMUNITY_HOST"),
        maxSentDataOverride = 8_192,
        acknowledgeRequestBodyDisclosure = true,
    )

    /**
     * The cancel write. Same "the prover performs it" semantics as [createOffer].
     *
     * The offer is named by the **path**, and a cookie-authed request discloses its target — so that
     * disclosure is the binding, and the body carries nothing but `sessionid`.
     */
    private fun cancelOffer() = ProvenRead(
        serverName = COMMUNITY_HOST,
        pathTemplate = "/tradeoffer/$OFFER_ID/cancel",
        revealJsonPaths = listOf("success"),
        auth = ProvenReadAuth.SESSION_COOKIE,
        method = "POST",
        bodyTemplate = SteamWriteBody.cancelOfferFormTemplate(),
        maxSentDataOverride = 2_048,
        acknowledgeRequestBodyDisclosure = true,
    )

    /**
     * The definition for [kind], or `null` for the two the named `NotaryConfig` fields own.
     *
     * **A function, not a materialized map, and that is the difference between doing this work and not doing
     * it at all.** The shipping default enables exactly `TRADE_OFFER` and `TRADE_STATUS`, and
     * `NotaryConfig.provenRead` resolves both of those from `offerRead`/`historyRead` — so a stock build
     * never asks this catalog for anything. A map built as a default constructor argument would still
     * construct all eight entries (eight `ProvenRead` `init` blocks, sixteen `SteamHosts` URL parses, one full
     * percent-encode pass for the create body) on every service-worker spawn, forever, and then read none of
     * them. `when` defers each entry to the moment something actually names it.
     *
     * `ProvenReadCatalogTest` walks `ProvenReadKind.entries` through `NotaryConfig.provenRead`, so a
     * kind with no branch here is still a test failure rather than a runtime surprise.
     */
    fun of(kind: ProvenReadKind): ProvenRead? = when (kind) {
        // Owned by NotaryConfig.offerRead / historyRead, so that a host override of those positional
        // @JsExport slots keeps winning.
        ProvenReadKind.TRADE_OFFER, ProvenReadKind.TRADE_STATUS -> null
        ProvenReadKind.TRADE_OFFERS -> tradeOffers()
        ProvenReadKind.TRADE_HISTORY -> tradeHistory()
        ProvenReadKind.PLAYER_SUMMARIES -> playerSummaries()
        ProvenReadKind.STEAM_LEVEL -> steamLevel()
        ProvenReadKind.STEAM_NOTIFICATIONS -> steamNotifications()
        ProvenReadKind.OWN_INVENTORY -> ownInventory()
        ProvenReadKind.CREATE_OFFER -> createOffer()
        ProvenReadKind.CANCEL_OFFER -> cancelOffer()
    }
}

// The binding-supplied slots `SteamProofReadMapper` fills. Named constants rather than inline literals so a
// template and the mapper's substitution cannot drift apart in a way only a live proof would reveal.

/** The device's own steamid64, taken off the credential rather than the deal. */
const val SUBJECT_STEAM_ID: String = "{steamId}"

/** The counterparty's steamid64. */
const val PARTNER_STEAM_ID: String = "{partnerSteamId}"

/** The game's Steam `appid`, from the active [com.dmarket.p2p.tracker.game.GameAdapter]. */
const val APP_ID: String = "{appId}"

/** The game's inventory context id, from the active [com.dmarket.p2p.tracker.game.GameAdapter]. */
const val CONTEXT_ID: String = "{contextId}"

/** A single Steam asset id. */
const val ASSET_ID: String = "{assetId}"

/** Steam's `tradeofferid`. */
const val OFFER_ID: String = "{offerId}"

/** Steam's own `tradeid` — the history axis's binding key. */
const val TRADE_ID: String = "{tradeId}"
