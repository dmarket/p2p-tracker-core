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

/** The C ABI's JSON (`client-cffi/src/config.rs`): one mode's limits, snake_case, a policy with every field set. */
class NativeProverJsonTest {
    private val spec = SteamProofReadMapper(NotaryConfig()).readSpec(
        source = TradeStatusSource.HISTORY,
        binding = ProvenReadBinding(dealId = DealId("d1"), tradeId = TradeId("trade-1")),
        subjectSteamId = SteamId("76561198000000001"),
        adapter = Cs2GameAdapter(),
    )
    private val request = ProveRequest(
        notaryUrl = "wss://notary.test/",
        notaryToken = "dmarket-token",
        proxyBaseUrl = "wss://proxy.test",
        spec = spec,
        steamAccessToken = "steam-token",
        maxSentData = 4_096,
        maxRecvData = 16_384,
        maxRecvDataOnline = 512,
        maxRecvRecordsOnline = 3,
    )

    private fun json(text: String) = kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject

    @Test
    fun mpc_config_carries_the_request_and_the_mpc_limits_only() {
        val config = json(nativeConfigJson(IssuanceMode.MPC, request))
        assertEquals(setOf("abi_version", "server_name", "request", "mpc"), config.keys)
        assertEquals("1", config["abi_version"].toString())
        val mpc = config["mpc"] as kotlinx.serialization.json.JsonObject
        assertEquals(setOf("max_sent_data", "max_recv_data", "max_recv_data_online", "max_recv_records_online"), mpc.keys)
        assertEquals("512", mpc["max_recv_data_online"].toString())
        val http = config["request"] as kotlinx.serialization.json.JsonObject
        assertTrue("steam-token" in http["uri"].toString())
        assertEquals("[]", http["headers"].toString())
    }

    @Test
    fun proxy_config_carries_the_plaintext_limits_and_pem_roots_become_der_hex() {
        val pem = "-----BEGIN CERTIFICATE-----\nAAEC\n/w==\n-----END CERTIFICATE-----\n"
        val config = json(nativeConfigJson(IssuanceMode.PROXY, request.copy(rootStorePem = pem)))
        assertEquals(setOf("max_sent_data", "max_recv_data"), (config["proxy"] as kotlinx.serialization.json.JsonObject).keys)
        assertTrue("mpc" !in config)
        assertEquals("[\"000102ff\"]", config["roots_der_hex"].toString())
    }

    @Test
    fun the_policy_sets_every_field_and_withholds_the_target() {
        val policy = json(nativePolicyJson(spec.proverRevealPolicy()))
        assertEquals("false", policy["reveal_request_target"].toString())
        assertTrue("redact_request_header_values" in policy && "reveal_response_headers" in policy && "body" in policy)
        val all = json(nativePolicyJson(spec.proverRevealPolicy().copy(responseBody = ResponseBodyReveal.All)))
        assertEquals("\"all\"", all["body"].toString())
        assertTrue("json_paths" !in all)
    }
}
