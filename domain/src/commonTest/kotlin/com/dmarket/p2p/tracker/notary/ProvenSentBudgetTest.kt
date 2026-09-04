package com.dmarket.p2p.tracker.notary

import com.dmarket.p2p.tracker.config.NotaryConfig
import com.dmarket.p2p.tracker.game.Cs2GameAdapter
import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.TradeId
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusSource
import com.dmarket.p2p.tracker.support.COUNTERPARTY_STEAM_ID
import com.dmarket.p2p.tracker.support.SELF_STEAM_ID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Sizing is asserted against the specs `SteamProofReadMapper` really produces, never hand-built ones: the
 * whole rule turns on the *shape* of a spec (no headers, no body, no override, a token slot in the path), and
 * a hand-built fixture would prove only that the fixture matches the predicate.
 */
class ProvenSentBudgetTest {

    private val config = NotaryConfig()
    private val mapper = SteamProofReadMapper(config)

    /**
     * The community reads refuse to build at all until the host accepts their response-header disclosure, so
     * the cases that need one go through a mapper that has. Nothing here proves anything about that gate — it
     * is simply the only way to obtain the specs whose *shape* is what those cases are about.
     */
    private val communityConfig = NotaryConfig(acknowledgeCommunityResponseDisclosure = true)
    private val communityMapper = SteamProofReadMapper(communityConfig)
    private val adapter = Cs2GameAdapter()
    private val steamId = SteamId(SELF_STEAM_ID)

    private val binding = ProvenReadBinding(
        dealId = DealId("d1"),
        steamOfferId = OfferId("9329974212"),
        assetId = AssetId("a1"),
        tradeId = TradeId("4589711234567890123"),
        // Only the create POST reads these two, and it refuses to build without them.
        partnerSteamId = SteamId(COUNTERPARTY_STEAM_ID),
        assetsToGive = listOf(AssetId("a1")),
    )

    private fun specFor(kind: ProvenReadKind) = communityMapper.readSpec(kind, binding, steamId, adapter)

    private fun tradeAxisSpec(source: TradeStatusSource) = mapper.readSpec(source, binding, steamId, adapter)

    private fun budget(spec: ProvenReadSpec, tokenLength: Int, margin: Int = config.sentBudgetMarginPercent) =
        ProvenSentBudget.sentBudget(spec, config.copy(sentBudgetMarginPercent = margin), tokenLength)

    /** The path bytes the prover really sends, i.e. with the placeholder replaced by a token of [tokenLength]. */
    private fun ProvenReadSpec.sentPathLength(tokenLength: Int) = path.length - TOKEN_PLACEHOLDER.length + tokenLength

    // ---- the derivation ----------------------------------------------------------------------------

    @Test
    fun the_history_axis_reproduces_the_measured_request_size() {
        // 196 + len(token) is what `NotaryConfig.maxSentData`'s KDoc measured, on this axis. The derivation
        // must land on the same number from the other direction — 103 of framing plus this path — or the split
        // of that constant into "framing" and "path" is wrong.
        val spec = tradeAxisSpec(TradeStatusSource.HISTORY)
        assertEquals(196 + 522, 103 + spec.sentPathLength(522))
        assertEquals(718, budget(spec, tokenLength = 522, margin = 0))
    }

    @Test
    fun the_offer_axis_is_sized_smaller_than_the_history_axis_it_shares_no_constant_with() {
        // The point of deriving from the spec: a shorter path now costs less, where one folded constant made
        // both axes pay the longer one's price.
        val offer = budget(tradeAxisSpec(TradeStatusSource.OFFER), tokenLength = 522, margin = 0)
        val history = budget(tradeAxisSpec(TradeStatusSource.HISTORY), tokenLength = 522, margin = 0)
        assertTrue(offer < history, "offer ($offer) should size below history ($history)")
    }

    @Test
    fun the_default_margin_gives_back_most_of_the_flat_ceiling() {
        // The headline of the change: 1024 flat against 826 sized, on the axis the tracker proves in practice.
        assertEquals(826, budget(tradeAxisSpec(TradeStatusSource.HISTORY), tokenLength = 522))
        assertTrue(config.maxSentData == 1_024, "the saving above is stated against the shipped ceiling")
    }

    @Test
    fun the_margin_rounds_up_because_a_budget_one_byte_short_fails_the_proof() {
        val spec = tradeAxisSpec(TradeStatusSource.HISTORY)
        val exact = budget(spec, tokenLength = 1, margin = 0)
        // A 10% margin on an odd requirement must round up, never down.
        assertEquals(exact + (exact * 10 + 99) / 100, budget(spec, tokenLength = 1, margin = 10))
    }

    // ---- the clamp ---------------------------------------------------------------------------------

    @Test
    fun it_can_only_ever_spend_less_than_the_configured_ceiling() {
        // A token far past what the ceiling admits does NOT raise the budget: the operator's knob keeps the
        // meaning it has today, and an absurd token must not become an unbounded upload. Such a proof fails
        // exactly as it does now, and the remedy is still to publish a larger `maxSentData`.
        assertEquals(1_024, budget(tradeAxisSpec(TradeStatusSource.OFFER), tokenLength = 10_000))
    }

    @Test
    fun the_documented_rollback_margin_restores_todays_behaviour_on_every_axis() {
        // 44, and it must hold for the SHORTEST-pathed axis, which is the one that keeps sizing longest: at 43
        // the offer axis still lands at 1020 and the sizing is quietly still on. A rollback figure that works
        // on one axis only is worse than none — it reads as applied while a proof is still being tightened.
        for (source in TradeStatusSource.entries) {
            assertEquals(config.maxSentData, budget(tradeAxisSpec(source), tokenLength = 522, margin = 44), "$source")
        }
    }

    @Test
    fun an_absurd_margin_saturates_instead_of_overflowing_into_a_negative_budget() {
        // `sentBudgetMarginPercent` is host-supplied and only validated `>= 0`. In `Int` arithmetic a large one
        // wraps NEGATIVE, and `minOf` would then pick it as the budget — a silently unprovable request rather
        // than a loud config error.
        assertEquals(
            1_024,
            budget(tradeAxisSpec(TradeStatusSource.OFFER), tokenLength = 522, margin = Int.MAX_VALUE),
        )
    }

    // ---- which reads the rule touches --------------------------------------------------------------

    @Test
    fun exactly_the_token_authed_reads_are_sized_and_every_other_kind_is_left_alone() {
        // Enumerated, not described. The predicate is structural, so the set it admits moves when a catalog
        // entry changes shape — and the framing constant is only defensible for requests that are the line
        // plus the four injected headers. Widening this set should be a visible test change.
        val sized = ProvenReadKind.entries.filter { kind ->
            val spec = specFor(kind)
            budget(spec, tokenLength = 522) != (spec.maxSentDataOverride ?: config.maxSentData)
        }
        assertEquals(
            ProvenReadKind.entries.filter { specFor(it).needsAccessToken && specFor(it).maxSentDataOverride == null },
            sized,
        )
        assertTrue(ProvenReadKind.TRADE_OFFER in sized && ProvenReadKind.TRADE_STATUS in sized, "both axes: $sized")
    }

    @Test
    fun the_reads_with_their_own_send_override_keep_the_catalog_value_verbatim() {
        // The override exists to say the global sizing does not apply to that read. Asserted against the
        // catalog's real numbers, so this fails if a template's override changes without the rule being
        // re-examined.
        for (kind in ProvenReadKind.entries) {
            val override = specFor(kind).maxSentDataOverride ?: continue
            assertEquals(override, budget(specFor(kind), tokenLength = 522), "$kind")
        }
    }

    @Test
    fun a_read_that_carries_no_token_slot_is_left_alone() {
        // The token is the one part of the request the spec does not state, so a read that substitutes none is
        // not a request this can size — whatever else it looks like.
        val spec = tradeAxisSpec(TradeStatusSource.OFFER).copy(path = "/IEconService/GetTradeOffer/v1/?tradeofferid=1")
        assertEquals(config.maxSentData, budget(spec, tokenLength = 522))
    }

    @Test
    fun a_cookie_authed_read_is_excluded_by_its_headers_even_without_its_override() {
        // Belt and braces on the predicate: SESSION_COOKIE reads carry a cookie header whose length is not in
        // the framing constant, so the exclusion holds even for one that forgets to set an override.
        val spec = specFor(ProvenReadKind.OWN_INVENTORY).copy(maxSentDataOverride = null)
        assertTrue(spec.sendHeaders.isNotEmpty(), "the inventory read must carry a cookie header")
        assertEquals(config.maxSentData, budget(spec, tokenLength = 522))
    }

    @Test
    fun a_write_is_excluded_by_its_body_even_without_its_override() {
        // The clause no catalog entry exercises on its own — every bodied read also has headers and an
        // override — so it is constructed here rather than left untested.
        val spec = specFor(ProvenReadKind.CREATE_OFFER).copy(maxSentDataOverride = null, sendHeaders = emptyList())
        assertTrue(spec.body != null, "the create must carry a form body")
        assertEquals(config.maxSentData, budget(spec, tokenLength = 522))
    }
}
