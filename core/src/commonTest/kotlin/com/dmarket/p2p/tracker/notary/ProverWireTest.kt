package com.dmarket.p2p.tracker.notary

import com.dmarket.p2p.tracker.config.NotaryConfig
import com.dmarket.p2p.tracker.game.Cs2GameAdapter
import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.TradeId
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The prover-neutral wire both provers map: limits, credential slots and disclosure, on every platform. */
class ProverWireTest {
    private val token = "eyJhbGciOiJIUzI1NiJ9.steam-access-token.sig"

    private fun request(source: TradeStatusSource = TradeStatusSource.OFFER) = ProveRequest(
        notaryUrl = "wss://notary.test/",
        notaryToken = "dmarket-token",
        proxyBaseUrl = "wss://proxy.test",
        spec = SteamProofReadMapper(NotaryConfig()).readSpec(
            source = source,
            binding = ProvenReadBinding(dealId = DealId("d1"), steamOfferId = OfferId("off-1"), tradeId = TradeId("trade-1")),
            subjectSteamId = SteamId("76561198000000001"),
            adapter = Cs2GameAdapter(),
        ),
        steamAccessToken = token,
        maxSentData = 4_096,
        maxRecvData = 16_384,
        maxRecvDataOnline = 512,
    )

    private fun communityRequest(kind: ProvenReadKind) = request().copy(
        spec = SteamProofReadMapper(NotaryConfig(acknowledgeCommunityResponseDisclosure = true)).readSpec(
            kind = kind,
            binding = ProvenReadBinding(
                dealId = DealId("d1"),
                steamOfferId = OfferId("off-1"),
                assetId = AssetId("asset-1"),
                partnerSteamId = SteamId("76561198000000002"),
                tradeToken = "tok-1",
                assetsToGive = listOf(AssetId("asset-1")),
            ),
            subjectSteamId = SteamId("76561198000000001"),
            adapter = Cs2GameAdapter(),
        ),
        steamSessionCookie = "steamLoginSecure=cookie-value",
        steamSessionId = "session-id-value",
    )

    @Test
    fun mpc_issuance_carries_the_limits_and_clamps_the_online_budget_to_the_receive_ceiling() {
        val mpc = request().copy(maxRecvDataOnline = 99_999, maxRecvRecordsOnline = 4).mpcIssuance()

        assertEquals("api.steampowered.com", mpc.serverName)
        assertEquals(4_096, mpc.maxSentData)
        assertEquals(16_384, mpc.maxRecvData)
        assertEquals(16_384, mpc.maxRecvDataOnline)
        assertEquals(4, mpc.maxRecvRecordsOnline)
        assertEquals(512, request().mpcIssuance().maxRecvDataOnline)
    }

    @Test
    fun production_sends_no_record_cap_and_no_root_store() {
        val mpc = request().mpcIssuance()
        assertNull(mpc.maxRecvRecordsOnline)
        assertNull(mpc.rootStorePem)
        assertNull(request().proxyIssuance().rootStorePem)
        assertEquals("-----PEM-----", request().copy(rootStorePem = "-----PEM-----").mpcIssuance().rootStorePem)
    }

    @Test
    fun proxy_issuance_carries_only_the_plaintext_limits() {
        assertEquals(
            ProxyIssuance("api.steampowered.com", maxSentData = 4_096, maxRecvData = 16_384),
            request().copy(maxRecvRecordsOnline = 7).proxyIssuance(),
        )
    }

    @Test
    fun the_steam_token_is_joined_to_the_target_and_nothing_else_is_added_to_a_token_read() {
        for (source in TradeStatusSource.entries) {
            val http = request(source).proverHttpRequest()
            assertEquals("GET", http.method)
            assertTrue("access_token=$token" in http.uri, "$source: token substituted")
            assertFalse(TOKEN_PLACEHOLDER in http.uri)
            assertTrue(http.headers.isEmpty())
            assertNull(http.body)
            assertFalse(token in http.toString(), "toString must not print the token")
        }
    }

    @Test
    fun both_trade_axes_withhold_the_request_target_and_copy_the_spec_disclosure() {
        for (source in TradeStatusSource.entries) {
            val spec = request(source).spec
            val policy = spec.proverRevealPolicy()
            assertFalse(policy.revealRequestTarget, "$source: the target carries the Steam token")
            assertEquals(spec.responseBodyReveal, policy.responseBody)
            assertEquals(spec.revealResponseHeaders, policy.revealResponseHeaders)
            assertEquals(spec.redactRequestHeaderValues, policy.redactRequestHeaderValues)
        }
    }

    @Test
    fun a_cookie_read_fills_the_session_header_and_redacts_it_from_the_proof() {
        val req = communityRequest(ProvenReadKind.OWN_INVENTORY)
        val http = req.proverHttpRequest()
        assertEquals("steamLoginSecure=cookie-value", http.headers.single { it.first == "cookie" }.second)
        assertTrue("cookie" in req.spec.proverRevealPolicy().redactRequestHeaderValues)
        assertFalse("cookie-value" in http.toString())
    }

    @Test
    fun a_write_body_gets_the_session_id_and_a_missing_value_stays_a_placeholder() {
        val body = communityRequest(ProvenReadKind.CREATE_OFFER).proverHttpRequest().body.orEmpty()
        assertTrue("session-id-value" in body)
        val unfilled = communityRequest(ProvenReadKind.CREATE_OFFER).copy(steamSessionId = null).proverHttpRequest().body.orEmpty()
        assertTrue(SESSION_ID_PLACEHOLDER in unfilled)
    }
}
