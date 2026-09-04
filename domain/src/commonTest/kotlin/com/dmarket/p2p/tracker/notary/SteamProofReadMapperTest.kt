package com.dmarket.p2p.tracker.notary

import com.dmarket.p2p.tracker.config.NotaryConfig
import com.dmarket.p2p.tracker.config.ProvenReadAuth
import com.dmarket.p2p.tracker.game.Cs2GameAdapter
import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.TradeId
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SteamProofReadMapperTest {

    private val adapter = Cs2GameAdapter()
    private val steamId = SteamId("76561198000000001")

    private fun binding(offer: String? = "off-9", asset: String? = null, trade: String? = "trade-9") = ProvenReadBinding(
        dealId = DealId("d1"),
        steamOfferId = offer?.let { OfferId(it) },
        assetId = asset?.let { AssetId(it) },
        tradeId = trade?.let { TradeId(it) },
    )

    @Test
    fun offer_spec_proves_the_ieconservice_read_whose_status_we_report() {
        // The whole point of the retarget: the proven read is the call the reported integer comes from. It
        // used to be a `steamcommunity.com` HTML page nothing in the client parses, so the attestation had no
        // defined relationship to the number being reported.
        val spec = SteamProofReadMapper(NotaryConfig()).readSpec(TradeStatusSource.OFFER, binding(), steamId, adapter)

        assertEquals("api.steampowered.com", spec.serverName)
        assertEquals("GET", spec.method)
        assertTrue("GetTradeOffer" in spec.path, "offer read must be GetTradeOffer: ${spec.path}")
        assertTrue("off-9" in spec.path, "offer read path must bind the steam_offer_id: ${spec.path}")
        assertFalse(spec.revealResponseHeaders)
    }

    @Test
    fun history_spec_addresses_a_single_trade_by_id() {
        // NOT GetTradeHistory: it returns up to 50 rows and the prover's reveal-path syntax has no filters,
        // so no row index is knowable before the read.
        val spec = SteamProofReadMapper(NotaryConfig()).readSpec(TradeStatusSource.HISTORY, binding(), steamId, adapter)

        assertEquals("api.steampowered.com", spec.serverName)
        assertTrue("GetTradeStatus" in spec.path, "history read must be GetTradeStatus: ${spec.path}")
        assertTrue("trade-9" in spec.path, "history read path must bind the trade id: ${spec.path}")
    }

    @Test
    fun the_steam_token_stays_an_unfilled_placeholder_so_the_spec_is_credential_free() {
        // The reads authenticate by `?access_token=`, but the credential must not become a field of a pure
        // value — that is how it would reach a log, a crash report or a `toString`. The IO edge fills it.
        val mapper = SteamProofReadMapper(NotaryConfig())
        for (source in TradeStatusSource.entries) {
            val spec = mapper.readSpec(source, binding(), steamId, adapter)
            assertTrue("{token}" in spec.path, "$source must leave {token} for the IO edge: ${spec.path}")
            assertTrue("access_token={token}" in spec.path, "$source must pass the token as access_token: ${spec.path}")
        }
    }

    @Test
    fun both_axes_withhold_the_request_target_because_it_carries_the_steam_token() {
        // INVERTED from what this file originally asserted, and the inversion is the security property: the
        // query string now contains the Steam JWT, and the prover's target disclosure is all-or-nothing, so
        // revealing it would publish the credential inside the attestation.
        val mapper = SteamProofReadMapper(NotaryConfig())
        assertFalse(mapper.readSpec(TradeStatusSource.OFFER, binding(), steamId, adapter).revealRequestTarget)
        assertFalse(mapper.readSpec(TradeStatusSource.HISTORY, binding(), steamId, adapter).revealRequestTarget)
    }

    @Test
    fun both_axes_disclose_the_whole_response_so_the_presentation_keeps_its_http_framing() {
        // A REGRESSION guard, not a preference. Both axes shipped `JsonPaths` for one release and `/notary`
        // rejected every single proof with `verified:false, "the revealed response has no header/body
        // separator"`: the verifier parses the revealed bytes as HTTP and splits on `\r\n\r\n`, while `spansy`
        // keeps each header's trailing CRLF inside that header's span — so withholding the headers withholds
        // the CRLF closing the last one, and only the status line plus the blank line survive. One CRLF where
        // the parser needs two, and no choice of reveal PATHS can add the missing bytes.
        val mapper = SteamProofReadMapper(NotaryConfig())

        for (source in TradeStatusSource.entries) {
            assertEquals(
                ResponseBodyReveal.All,
                mapper.readSpec(source, binding(), steamId, adapter).responseBodyReveal,
                "$source must disclose the whole response while the verifier parses it as HTTP",
            )
        }
    }

    @Test
    fun the_configured_reveal_paths_stay_inert_while_the_response_is_disclosed_whole() {
        // The paths are kept as the narrowing target, so this pins that they are UNREAD rather than
        // half-wired: an axis that quietly picked its config up again would ship the rejection above, and the
        // only symptom is a `verified:false` reason nobody is watching. Deliberately absurd values — if
        // either reached the prover it would fail with `JSON path not found`, which is the loud version.
        val config = NotaryConfig(
            offerRead = NotaryConfig().offerRead.copy(revealJsonPaths = listOf("nope.not.a.field")),
            historyRead = NotaryConfig().historyRead.copy(revealJsonPaths = listOf("also.nope")),
        )
        val mapper = SteamProofReadMapper(config)

        for (source in TradeStatusSource.entries) {
            assertEquals(ResponseBodyReveal.All, mapper.readSpec(source, binding(), steamId, adapter).responseBodyReveal)
        }
    }

    @Test
    fun a_path_reveal_with_no_paths_is_rejected_by_the_type() {
        // The guard that used to live in the mapper, moved to where it still applies: a withheld target AND
        // an empty reveal list produce a well-formed attestation that binds no trade at all, so there is
        // nothing for a verifier to disagree with. It has to survive the interim `All`, because narrowing
        // back to paths is the expected end state.
        assertFailsWith<IllegalArgumentException> { ResponseBodyReveal.JsonPaths(emptyList()) }
    }

    @Test
    fun the_reveal_policy_is_never_weaker_than_the_provers_own_default() {
        // Supplying a policy REPLACES the prover's default, which redacts authorization/cookie/user-agent —
        // and every request header not named is revealed IN FULL. These reads send neither header today; the
        // guard is so a later one that does cannot leak by omission.
        val spec = SteamProofReadMapper(NotaryConfig()).readSpec(TradeStatusSource.OFFER, binding(), steamId, adapter)

        assertTrue("cookie" in spec.redactRequestHeaderValues, "cookie must stay redacted: $spec")
        assertTrue("authorization" in spec.redactRequestHeaderValues, "authorization must stay redacted: $spec")
        assertEquals(
            spec.redactRequestHeaderValues.distinct(),
            spec.redactRequestHeaderValues,
            "a duplicate header name would be passed to the wasm twice",
        )
    }

    @Test
    fun both_proven_reads_suppress_item_descriptions_because_the_recv_cap_depends_on_it() {
        // Not a tuning detail. Item descriptions cost ~2.3 KB PER ITEM (measured over 46 real GetTradeOffer
        // responses: 508-547 B for the offer object, 2,821 B once one item's descriptions are included), so
        // they scale with the trade and cross `NotaryConfig.maxRecvData` (16 KiB, upstream's deliberately
        // tight default — "MPC is bandwidth-bound") at roughly seven items. Lose this parameter and proofs
        // fail for multi-item trades only, which is the least obvious failure shape available.
        val mapper = SteamProofReadMapper(NotaryConfig())
        for (source in TradeStatusSource.entries) {
            val path = mapper.readSpec(source, binding(), steamId, adapter).path
            assertTrue("get_descriptions=0" in path, "$source must suppress item descriptions: $path")
        }
    }

    @Test
    fun path_template_substitutes_all_placeholders() {
        val config = NotaryConfig(
            // `{token}` included because the mapper now requires it — a template without the token slot would
            // issue an unauthenticated read.
            offerRead = NotaryConfig().offerRead.copy(
                pathTemplate = "/p/{steamId}/o/{offerId}/a/{assetId}/g/{appId}/t/{tradeId}?k={token}",
            ),
        )
        val spec = SteamProofReadMapper(config).readSpec(
            TradeStatusSource.OFFER,
            binding(offer = "OFR", asset = "ASSET", trade = "TRD"),
            SteamId("STEAM"),
            adapter,
        )

        assertEquals("/p/STEAM/o/OFR/a/ASSET/g/730/t/TRD?k={token}", spec.path)
    }

    @Test
    fun a_template_without_the_token_slot_is_rejected() {
        // Symmetric with the empty-reveal-paths guard, and the same class of mistake: these reads authenticate
        // by query parameter, so a published template missing the token slot issues an UNAUTHENTICATED read —
        // a Steam 401 inside MPC, surfacing as an opaque ProofFailed for every proof. The templates are
        // remote-overridable and validated only as paths, so nothing else would catch it.
        // Now rejected at CONSTRUCTION rather than per proof: the invariant lives with the data, so a
        // definition that could not authenticate cannot be built in the first place.
        assertFailsWith<IllegalArgumentException> {
            NotaryConfig().offerRead.copy(pathTemplate = "/IEconService/GetTradeOffer/v1/?tradeofferid={offerId}")
        }
    }

    @Test
    fun missing_offer_id_for_a_template_that_needs_it_fails_fast() {
        // The default offer template references {offerId}; a null binding value must throw rather than
        // silently produce `?tradeofferid=&…`, which a real prover would witness as a malformed read.
        assertFailsWith<IllegalArgumentException> {
            SteamProofReadMapper(NotaryConfig()).readSpec(TradeStatusSource.OFFER, binding(offer = null), steamId, adapter)
        }
    }

    @Test
    fun missing_trade_id_for_the_history_read_fails_fast() {
        // Steam sets `tradeid` on the offer only once it is accepted, so a history proof attempted before
        // that has nothing to address — and `?tradeid=` with no value would be witnessed as-is.
        assertFailsWith<IllegalArgumentException> {
            SteamProofReadMapper(NotaryConfig()).readSpec(TradeStatusSource.HISTORY, binding(trade = null), steamId, adapter)
        }
    }

    @Test
    fun missing_asset_id_is_tolerated_when_the_template_does_not_reference_it() {
        // The default offer template uses only {offerId}; a null assetId must not fail-fast.
        val spec = SteamProofReadMapper(NotaryConfig()).readSpec(TradeStatusSource.OFFER, binding(asset = null), steamId, adapter)
        assertFalse("{assetId}" in spec.path)
    }

    // ---- the registry: every Steam endpoint, not just the two axes -------------------------------

    /** Enough of a binding to satisfy every kind's template, so one table can walk all ten. */
    private fun fullBinding() = ProvenReadBinding(
        dealId = DealId("d1"),
        steamOfferId = OfferId("off-9"),
        assetId = AssetId("asset-9"),
        tradeId = TradeId("trade-9"),
        partnerSteamId = SteamId("76561198000000002"),
        tradeToken = "tok-9",
        assetsToGive = listOf(AssetId("asset-9")),
    )

    /** Community kinds need the operator's disclosure acknowledgement before a spec can be built at all. */
    private fun communityConfig() = NotaryConfig(acknowledgeCommunityResponseDisclosure = true)

    /** Hoisted: eight of the tests below want exactly this and nothing per-test about it varies. */
    private val communityMapper = SteamProofReadMapper(communityConfig())

    @Test
    fun the_axis_overload_resolves_to_the_reads_it_always_did() {
        // The bridge that makes this whole change behaviour-neutral: TradeStatusSource still names the same two
        // endpoints, so nothing the loop does has moved.
        val mapper = SteamProofReadMapper(NotaryConfig())
        assertEquals(
            mapper.readSpec(ProvenReadKind.TRADE_OFFER, binding(), steamId, adapter),
            mapper.readSpec(TradeStatusSource.OFFER, binding(), steamId, adapter),
        )
        assertEquals(
            mapper.readSpec(ProvenReadKind.TRADE_STATUS, binding(), steamId, adapter),
            mapper.readSpec(TradeStatusSource.HISTORY, binding(), steamId, adapter),
        )
    }

    @Test
    fun every_kind_builds_a_spec_that_leaks_no_credential() {
        // The invariant that has to hold for all ten, not just the two that were reachable before: a spec is a
        // pure value, so anything secret in it can reach a log or a crash report. Credentials stay as slots.
        for (kind in ProvenReadKind.entries) {
            val spec = communityMapper.readSpec(kind, fullBinding(), steamId, adapter)
            val everything = spec.path + spec.body.orEmpty() + spec.sendHeaders.joinToString { it.valueTemplate }
            assertFalse(STEAM_JWT_LOOKALIKE in everything, "$kind must not embed a token value: $everything")
            // Each auth model leaves exactly the slot its endpoint needs.
            when (spec.serverName) {
                "api.steampowered.com" -> assertTrue("access_token={token}" in spec.path, "$kind: ${spec.path}")
                else -> assertTrue(
                    spec.sendHeaders.any { COOKIE_PLACEHOLDER in it.valueTemplate },
                    "$kind is on ${spec.serverName} and must send a cookie slot: ${spec.sendHeaders}",
                )
            }
        }
    }

    @Test
    fun every_header_carrying_a_credential_slot_is_also_redacted() {
        // Sending a header is not disclosing it — the prover reveals every request header IN FULL unless its
        // name is listed. Getting this pairing wrong is silent: the proof still verifies, it just published the
        // session. So it is asserted for every kind rather than reviewed once.
        for (kind in ProvenReadKind.entries) {
            val spec = communityMapper.readSpec(kind, fullBinding(), steamId, adapter)
            spec.sendHeaders
                .filter { COOKIE_PLACEHOLDER in it.valueTemplate || SESSION_ID_PLACEHOLDER in it.valueTemplate }
                .forEach { header ->
                    assertTrue(
                        header.name in spec.redactRequestHeaderValues,
                        "$kind sends ${header.name} with a credential slot but does not redact it",
                    )
                }
        }
    }

    @Test
    fun token_authed_reads_withhold_their_target_and_community_ones_disclose_it() {
        // Opposite answers for opposite reasons, which is why this is not one default. A token-authed path
        // carries `access_token=` and disclosure is all-or-nothing; a community path carries no secret, and for
        // a write the path IS the binding (`/tradeoffer/{id}/cancel` names the offer).
        assertFalse(communityMapper.readSpec(ProvenReadKind.TRADE_OFFERS, fullBinding(), steamId, adapter).revealRequestTarget)
        assertTrue(communityMapper.readSpec(ProvenReadKind.CANCEL_OFFER, fullBinding(), steamId, adapter).revealRequestTarget)
        assertTrue(communityMapper.readSpec(ProvenReadKind.OWN_INVENTORY, fullBinding(), steamId, adapter).revealRequestTarget)
    }

    @Test
    fun community_reads_never_disclose_the_whole_response() {
        // `All` reveals one span covering status line, headers and body — and `steamcommunity.com` is the host
        // most likely to answer with `set-cookie`. So the community kinds take the narrow mode, and pair it with
        // revealResponseHeaders=true, which is what restores the `\r\n\r\n` the verifier splits on.
        // Filtered on the resolved read's auth, not on an enum flag: a new cookie-authed kind that forgot to
        // set such a flag would silently drop out of the one test whose whole purpose is catching a `set-cookie`
        // disclosure.
        val community = ProvenReadKind.entries.filter { communityConfig().provenRead(it).auth == ProvenReadAuth.SESSION_COOKIE }
        assertTrue(community.isNotEmpty(), "the filter must actually select the community kinds")
        for (kind in community) {
            val spec = communityMapper.readSpec(kind, fullBinding(), steamId, adapter)
            assertTrue(
                spec.responseBodyReveal is ResponseBodyReveal.JsonPaths,
                "$kind must not disclose a community response whole: ${spec.responseBodyReveal}",
            )
            assertTrue(spec.revealResponseHeaders, "$kind needs revealResponseHeaders for the header/body separator")
        }
    }

    @Test
    fun a_community_kind_needs_the_disclosure_acknowledgement() {
        // The plumbing is complete and the measurement is not, so this is the operator's "we looked" step. The
        // default config must refuse to build the spec at all.
        val failure = assertFailsWith<IllegalArgumentException> {
            SteamProofReadMapper(NotaryConfig()).readSpec(ProvenReadKind.OWN_INVENTORY, fullBinding(), steamId, adapter)
        }
        assertTrue("acknowledgeCommunityResponseDisclosure" in (failure.message ?: ""), failure.message ?: "")
    }

    @Test
    fun the_create_spec_carries_the_anti_csrf_pair_steam_validates_against_the_body() {
        // Steam rejects a create whose Referer does not carry THIS trade's partner — it cross-checks the
        // Referer's `partner` against the body's — which is why the web actual needs a declarativeNetRequest
        // rule at all (Referer is a forbidden fetch header). The prover writes it directly, so both come off one
        // binding and must agree.
        val spec = communityMapper.readSpec(ProvenReadKind.CREATE_OFFER, fullBinding(), steamId, adapter)

        val referer = spec.sendHeaders.single { it.name == "referer" }.valueTemplate
        // 76561198000000002 - 76561197960265728 = 39734274
        assertTrue("partner=39734274" in referer, "Referer must carry the 32-bit accountid: $referer")
        assertTrue("token=tok-9" in referer, "Referer must carry the trade token: $referer")
        assertEquals("https://steamcommunity.com", spec.sendHeaders.single { it.name == "origin" }.valueTemplate)
        assertTrue("partner=76561198000000002" in spec.body.orEmpty(), "body must carry the steamid64 form: ${spec.body}")
    }

    @Test
    fun the_create_body_keeps_its_session_id_slot_and_fills_its_json_fragments() {
        val spec = communityMapper.readSpec(ProvenReadKind.CREATE_OFFER, fullBinding(), steamId, adapter)
        val body = assertNotNull(spec.body)

        assertTrue(SESSION_ID_PLACEHOLDER in body, "the IO edge fills sessionid: $body")
        assertFalse(SteamWriteBody.ASSETS_PLACEHOLDER in body, "the asset fragment must be filled: $body")
        assertFalse(SteamWriteBody.CREATE_PARAMS_PLACEHOLDER in body, "the create params must be filled: $body")
        // Form-encoded once, by the builder — so the JSON's own `&`/`=` cannot split the body into fields Steam
        // never sent.
        assertTrue("asset-9" in body, "the asset id must reach the body: $body")
        assertTrue("%22assetid%22" in body, "the JSON must be form-encoded: $body")
    }

    @Test
    fun the_cancel_spec_binds_the_offer_through_its_path_and_sends_nothing_else() {
        val spec = communityMapper.readSpec(ProvenReadKind.CANCEL_OFFER, fullBinding(), steamId, adapter)

        assertEquals("/tradeoffer/off-9/cancel", spec.path)
        assertEquals("sessionid=$SESSION_ID_PLACEHOLDER", spec.body)
        assertEquals("POST", spec.method)
    }

    @Test
    fun the_inventory_spec_addresses_one_asset_rather_than_a_scan() {
        // A 2000-item page is orders of magnitude past any transcript cap, and exceeding the cap fails the
        // proof. So the provable claim is "this asset is in this inventory", which is not the same claim as the
        // whole-scan snapshot the report_inventory directive sends.
        val spec = communityMapper.readSpec(ProvenReadKind.OWN_INVENTORY, fullBinding(), steamId, adapter)

        assertEquals("/inventory/${steamId.value}/730/2?l=english&count=1&start_assetid=asset-9", spec.path)
        assertEquals("steamcommunity.com", spec.serverName)
    }

    @Test
    fun a_profile_read_without_a_partner_id_fails_fast() {
        // Same discipline as the offer/trade ids: `?steamids=` with no value would be witnessed as-is, proving a
        // malformed request rather than failing.
        assertFailsWith<IllegalArgumentException> {
            SteamProofReadMapper(NotaryConfig())
                .readSpec(ProvenReadKind.PLAYER_SUMMARIES, fullBinding().copy(partnerSteamId = null), steamId, adapter)
        }
    }

    @Test
    fun per_read_caps_reach_the_spec_so_one_endpoint_cannot_tax_the_others() {
        // A create needs a much larger send budget than `196 + len(token)`; raising it globally would make every
        // trade-axis proof pay the pre-processing (42 MB measured for a 717 B request).
        assertNotNull(communityMapper.readSpec(ProvenReadKind.CREATE_OFFER, fullBinding(), steamId, adapter).maxSentDataOverride)
        assertEquals(null, communityMapper.readSpec(ProvenReadKind.TRADE_OFFER, fullBinding(), steamId, adapter).maxSentDataOverride)
    }

    private companion object {
        /** Shaped like a Steam JWT so a spec that accidentally embedded one is caught by substring. */
        const val STEAM_JWT_LOOKALIKE = "eyJ"
    }
}
