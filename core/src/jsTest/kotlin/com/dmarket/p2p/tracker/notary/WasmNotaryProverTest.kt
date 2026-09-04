package com.dmarket.p2p.tracker.notary

import com.dmarket.p2p.tracker.config.NotaryConfig
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.TradeId
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusSource
import com.dmarket.p2p.tracker.support.fakeSteamCredential
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Records calls and returns fixed presentation bytes; tracks peak concurrency. */
private class FakeWasmProverModule(private val onEnter: suspend () -> Unit = {}) : WasmProverModule {
    var initCount = 0
    var proveCount = 0
    var inFlight = 0
    var peakInFlight = 0
    val lastRequests = mutableListOf<WasmProveRequest>()

    override suspend fun initialize(threadCount: Int) {
        initCount++
    }

    override suspend fun prove(request: WasmProveRequest): ByteArray {
        inFlight++
        peakInFlight = maxOf(peakInFlight, inFlight)
        try {
            onEnter()
            proveCount++
            lastRequests += request
            return byteArrayOf(1, 2, 3, proveCount.toByte())
        } finally {
            inFlight--
        }
    }
}

class WasmNotaryProverTest {

    private fun config() = NotaryConfig(notaryUrl = "wss://notary.test/", proxyBaseUrl = "wss://proxy.test")

    private fun prover(module: WasmProverModule, config: NotaryConfig = config()) = WasmNotaryProver(
        config = config,
        tokenProvider = { "tok" },
        module = module,
    )

    private fun binding() = ProvenReadBinding(
        dealId = DealId("d1"),
        steamOfferId = OfferId("off-1"),
        tradeId = TradeId("trade-1"),
    )

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun returns_base64_of_presentation_bytes_for_the_deal() = runTest {
        val module = FakeWasmProverModule()
        val result = prover(module).proveTransition(binding(), TradeStatusSource.OFFER, fakeSteamCredential())

        assertEquals(DealId("d1"), result.dealId)
        assertEquals(Base64.encode(byteArrayOf(1, 2, 3, 1)), result.proofPayload)
        assertEquals(1, module.initCount)
    }

    @Test
    fun binds_the_offer_and_carries_both_tokens_without_putting_the_steam_one_in_the_spec() = runTest {
        val module = FakeWasmProverModule()
        prover(module).proveTransition(binding(), TradeStatusSource.OFFER, fakeSteamCredential())

        val req = module.lastRequests.single()
        assertTrue("off-1" in req.spec.path, "the offer id must be in the read: ${req.spec.path}")
        // The credential reaches the IO edge as its own field and stays OUT of the pure spec, which is what
        // keeps it out of logs and `toString`s. The spec still holds the unfilled placeholder.
        assertEquals("ya29.test-steam-token", req.steamAccessToken)
        assertTrue("{token}" in req.spec.path, "the spec must keep the placeholder unfilled: ${req.spec.path}")
        assertTrue("ya29.test-steam-token" !in req.spec.path, "the Steam token must never enter the spec")
        assertTrue("ya29.test-steam-token" !in req.toString(), "toString must not disclose the Steam token")
        assertEquals("wss://notary.test/", req.notaryUrl)
        assertEquals("tok", req.notaryToken)
    }

    @Test
    fun a_default_config_proves_through_the_production_notary() = runTest {
        // The point of `notaryUrl` having a default at all: a host that configures nothing still attests
        // through a real notary instead of falling to the no-op prover's empty payload. Asserted on the
        // request the module receives rather than on `NotaryConfig()` — a default that nothing forwards is
        // the same silent nothing it replaced, and this prover used to `requireNotNull` at exactly this hop.
        //
        // Against the CONSTANT, not the URL: this test owns the forwarding hop, and `TrackerConfigTest`
        // owns the literal (one owner per deployed endpoint, as every other notary default here has).
        // Pinning the string in both would make moving the notary a two-module edit, and this is the module
        // nobody would think to grep.
        val module = FakeWasmProverModule()
        prover(module, NotaryConfig()).proveTransition(binding(), TradeStatusSource.OFFER, fakeSteamCredential())

        assertEquals(NotaryConfig.PRODUCTION_NOTARY_URL, module.lastRequests.single().notaryUrl)
    }

    @Test
    fun the_record_budget_travels_from_the_config_to_the_request() = runTest {
        // The one hop this value takes, and it is unlike its byte-budget sibling's: that one is resolved into
        // `ProvenReadSpec` by the mapper because a refusal can teach a higher minimum. Nothing teaches a record
        // count, so this reads straight off the config — and a silently-dropped read here would look exactly
        // like an operator's remote-config change having no effect, which is unfalsifiable from a log.
        val module = FakeWasmProverModule()
        prover(module, config().copy(maxRecvRecordsOnline = 6))
            .proveTransition(binding(), TradeStatusSource.OFFER, fakeSteamCredential())

        assertEquals(6, module.lastRequests.single().maxRecvRecordsOnline)
    }

    @Test
    fun an_unset_record_budget_stays_unset_all_the_way_to_the_request() = runTest {
        // The default path, asserted separately because it is the one every existing deployment runs: `null`
        // must survive the hop rather than being defaulted to a number somewhere along it.
        val module = FakeWasmProverModule()
        prover(module).proveTransition(binding(), TradeStatusSource.OFFER, fakeSteamCredential())

        assertEquals(null, module.lastRequests.single().maxRecvRecordsOnline)
    }

    @Test
    fun the_proven_read_withholds_the_request_target_because_it_carries_the_steam_token() = runTest {
        // This assertion is INVERTED from what it originally said, and the inversion is the security fix.
        // The proven read is now the token-authed `IEconService` call whose integer we report, so its query
        // string contains `access_token=<Steam JWT>` — and the prover's target disclosure is all-or-nothing.
        // Revealing it would publish the credential inside the attestation.
        val module = FakeWasmProverModule()
        prover(module).proveTransition(binding(), TradeStatusSource.OFFER, fakeSteamCredential())

        val spec = module.lastRequests.single().spec
        assertTrue(!spec.revealRequestTarget, "the request target carries the Steam token and must be withheld: $spec")
        // The binding that the withheld target would have provided has to come from the response instead, and
        // it has to arrive with its field NAMES *inside parseable HTTP* — hence the whole response, since a
        // selective reveal loses the header/body separator the verifier splits on.
        assertEquals(
            ResponseBodyReveal.All,
            spec.responseBodyReveal,
            "the response must be disclosed whole, names and framing included: $spec",
        )
        // Supplying a policy REPLACES the prover's own default, which redacts authorization/cookie/user-agent.
        // Ours must not be weaker: every request header not named here is revealed in full.
        assertTrue("cookie" in spec.redactRequestHeaderValues, "cookie must stay redacted: $spec")
        assertTrue("authorization" in spec.redactRequestHeaderValues, "authorization must stay redacted: $spec")
    }

    @Test
    fun the_history_read_addresses_one_trade_by_id() = runTest {
        // `GetTradeHistory` returns up to 50 rows and the reveal-path syntax has no filters, so a history
        // proof must address a single trade — hence `GetTradeStatus?tradeid=`, and hence the binding needs
        // the trade id at all.
        val module = FakeWasmProverModule()
        prover(module).proveTransition(binding(), TradeStatusSource.HISTORY, fakeSteamCredential())

        val spec = module.lastRequests.single().spec
        assertTrue("trade-1" in spec.path, "the history read must address the trade id: ${spec.path}")
        assertTrue("GetTradeStatus" in spec.path, "history proves GetTradeStatus, not GetTradeHistory: ${spec.path}")
        assertEquals(
            ResponseBodyReveal.All,
            spec.responseBodyReveal,
            "the response must be disclosed whole, names and framing included: $spec",
        )
    }

    @Test
    fun a_history_proof_without_a_trade_id_fails_loudly_rather_than_reading_a_malformed_url() = runTest {
        val module = FakeWasmProverModule()
        val noTradeId = ProvenReadBinding(dealId = DealId("d1"), steamOfferId = OfferId("off-1"))

        assertFailsWith<IllegalArgumentException> {
            prover(module).proveTransition(noTradeId, TradeStatusSource.HISTORY, fakeSteamCredential())
        }
        assertTrue(module.lastRequests.isEmpty(), "nothing should have been proven: a `?tradeid=` with no value")
    }

    @Test
    fun never_exceeds_max_concurrency() = runTest {
        val release = CompletableDeferred<Unit>()
        val module = FakeWasmProverModule(onEnter = { release.await() })
        val p = prover(module, config().copy(maxConcurrency = 2))

        val jobs = (1..5).map { async { p.proveTransition(binding(), TradeStatusSource.OFFER, fakeSteamCredential()) } }
        // Let the gate admit as many as it will, then unblock everyone.
        release.complete(Unit)
        jobs.awaitAll()

        assertEquals(5, module.proveCount)
        assertTrue(module.peakInFlight <= 2, "semaphore must cap in-flight proofs at 2 (peak=${module.peakInFlight})")
    }
}
