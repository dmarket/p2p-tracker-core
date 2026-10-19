package com.dmarket.p2p.tracker.runtime

import com.dmarket.p2p.tracker.adapter.notary.NoOpNotaryProver
import com.dmarket.p2p.tracker.client.marketplace.TradingApiSignature
import com.dmarket.p2p.tracker.config.NotaryConfig
import com.dmarket.p2p.tracker.config.TrackerConfig
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.notary.HostedNotaryProver
import com.dmarket.p2p.tracker.notary.HostedProofOutcome
import com.dmarket.p2p.tracker.notary.HostedProofRequest
import com.dmarket.p2p.tracker.notary.NotaryUpgradeHeaders
import com.dmarket.p2p.tracker.notary.ProofHost
import com.dmarket.p2p.tracker.notary.ProvenReadBinding
import com.dmarket.p2p.tracker.notary.ProvenReadKind
import com.dmarket.p2p.tracker.port.host.DeviceKeyValueStore
import com.dmarket.p2p.tracker.port.marketplace.Ed25519Signer
import com.dmarket.p2p.tracker.support.FakeClock
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/** The headless tracker's prover: the platform's host when it gives one, signing each upgrade with the pushed key. */
class HeadlessProvingTest {
    private val clock = FakeClock()
    private val idle = CoroutineScope(StandardTestDispatcher(TestCoroutineScheduler()))
    private val engine = MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }
    private val echoSigner = Ed25519Signer { message -> message }

    private object NoStore : DeviceKeyValueStore {
        override suspend fun get(key: String): String? = null

        override suspend fun set(key: String, value: String) = Unit

        override suspend fun remove(key: String) = Unit
    }

    /** Records each proof and the upgrade headers it asked for, and proves. */
    private class SigningHost : ProofHost {
        val proofs = mutableListOf<HostedProofRequest>()
        val headers = mutableListOf<NotaryUpgradeHeaders>()

        override suspend fun prove(
            proof: HostedProofRequest,
            upgrade: com.dmarket.p2p.tracker.notary.NotaryUpgradeSigner,
        ): HostedProofOutcome {
            proofs += proof
            headers += upgrade.sign()
            return HostedProofOutcome.Proved("cHJvb2Y=")
        }
    }

    private fun tracker(proving: HeadlessProving?, notaryUrl: String = DEV_NOTARY) = HeadlessTracker(
        platform = HeadlessPlatform(NoStore, clock, httpEngine = engine, signerFor = { echoSigner }, proving = proving),
        scope = idle,
        baseUrl = "https://gateway.test",
        tunables = TrackerConfig(notary = NotaryConfig(notaryUrl = notaryUrl)),
    )

    private val binding = ProvenReadBinding(dealId = DealId("deal-1"))

    private fun credential() = SteamCredential(TOKEN, SteamId(STEAM_ID), clock.now() + 2.hours)

    @Test
    fun without_a_host_the_tracker_proves_nothing() {
        assertSame(NoOpNotaryProver, tracker(proving = null).prover)
    }

    @Test
    fun with_a_host_one_proof_runs_at_a_time_unless_two_are_asked_for() {
        val one = tracker(HeadlessProving(SigningHost())).prover
        assertIs<HostedNotaryProver>(one)
        assertEquals(1, one.maxConcurrency)
        assertEquals("tlsn", one.id)
        assertEquals(2, tracker(HeadlessProving(SigningHost(), maxConcurrency = 2)).prover.maxConcurrency)
        assertFailsWith<IllegalArgumentException> { HeadlessProving(SigningHost(), maxConcurrency = 3) }
    }

    @Test
    fun each_upgrade_is_signed_with_the_pushed_key_for_the_notary_path() = runTest {
        val host = SigningHost()
        val tracker = tracker(HeadlessProving(host))
        tracker.pushTradingApiKey(PUBLIC_KEY, SECRET_SEED)

        tracker.prover.proveRead(binding, ProvenReadKind.TRADE_OFFER, credential())

        val headers = host.headers.single().values
        assertEquals(PUBLIC_KEY, headers[TradingApiSignature.API_KEY])
        val date = headers.getValue(TradingApiSignature.SIGN_DATE)
        val signed = headers.getValue(TradingApiSignature.REQUEST_SIGN).removePrefix("dmar ed25519 ").hexToByteArray().decodeToString()
        assertEquals("GET/provenance/v1/$date", signed)
        assertTrue(headers.values.none { SECRET_SEED in it })
        assertEquals(TOKEN, host.proofs.single().steamAccessToken, "the token goes to the host with the request")
    }

    @Test
    fun with_no_key_the_upgrade_cannot_be_signed_and_the_proof_fails() = runTest {
        val tracker = tracker(HeadlessProving(SigningHost()))
        assertFailsWith<Exception> { tracker.prover.proveRead(binding, ProvenReadKind.TRADE_OFFER, credential()) }
    }

    private companion object {
        const val DEV_NOTARY = "wss://api-dev2.devss.xyz/provenance/v1/"
        const val STEAM_ID = "76561198000000001"
        const val TOKEN = "eyJhbGciOiJFZERTQSJ9.eyJzdWIiOiI3NjU2MTE5ODAwMDAwMDAwMSJ9.c2lnbmF0dXJl"
        const val PUBLIC_KEY = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
        const val SECRET_SEED = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"
    }
}
