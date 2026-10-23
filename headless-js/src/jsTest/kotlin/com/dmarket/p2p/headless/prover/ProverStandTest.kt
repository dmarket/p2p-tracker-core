package com.dmarket.p2p.headless.prover

import com.dmarket.p2p.headless.STEAM_ID
import com.dmarket.p2p.headless.node.nodeFs
import com.dmarket.p2p.headless.node.nodePath
import com.dmarket.p2p.headless.node.nodeProcess
import com.dmarket.p2p.headless.steamToken
import com.dmarket.p2p.tracker.adapter.host.SystemClock
import com.dmarket.p2p.tracker.client.marketplace.TradingApiAuthenticator
import com.dmarket.p2p.tracker.client.marketplace.TradingApiNotaryUpgradeSigner
import com.dmarket.p2p.tracker.model.marketplace.TradingApiKey
import com.dmarket.p2p.tracker.notary.HostedProofOutcome
import com.dmarket.p2p.tracker.notary.HostedProofRequest
import com.dmarket.p2p.tracker.notary.NotaryProofRequest
import com.dmarket.p2p.tracker.notary.ProofFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * A real proof on a local steam-provenance stand: the SDK's production script forked as the prover process, the
 * tracker's bundle and its four seams in it, the upgrade headers signed here when the child asks, no `bearer.` entry.
 * Skipped unless `SDK_PROVER_E2E_DIR` names the stand's directory (test key, fixture CA, glue mode file).
 *
 * The stand: notary, validator and backend from `test-full-e2e`; a TLS target presenting a test certificate for
 * `api.steampowered.com` under the fixture CA; and a stand-in for the gateway's signature check in front of the
 * notary, which accepts only signed upgrades without a bearer and relays them with the stand's dev token.
 * `SDK_PROVER_EXPECT=oom` runs the proof under a container memory limit that cannot hold it: that case is the Node
 * SDK's own stand test, which reads its health, and these skip.
 */
class ProverStandTest {
    private val env: dynamic = nodeProcess.env
    private val dir = env.SDK_PROVER_E2E_DIR as? String
    private val lines = mutableListOf<String>()
    private val clock = SystemClock()
    private var host: ForkedProofHost? = null

    @AfterTest
    fun stop() {
        host?.close()
        if (dir != null) setGlue("ok")
    }

    private fun setGlue(mode: String) {
        nodeFs.writeFileSync(nodePath.join(dir, "glue-mode"), mode)
    }

    private fun read(name: String): String = nodeFs.readFileSync(nodePath.join(dir, name), "utf8") as String

    private fun host(): ForkedProofHost {
        val settings = ProverSettings(
            notaryUrl = env.SDK_PROVER_NOTARY as String,
            threadCount = (env.SDK_PROVER_THREADS as? String)?.toInt() ?: ProverSettings.DEFAULT_THREADS,
            assetsDir = checkNotNull(proverAssetsDir(env.SDK_PROVER_ASSETS as? String)) { "prover files not found" },
            rootStorePem = read("stand/ca-root.pem"),
            targetAddress = env.SDK_PROVER_TARGET as String,
        )
        return ForkedProofHost(
            env.SDK_PROVER_SCRIPT as String,
            listOf(PROVER_CHILD_ARG),
            settings,
            clock,
            onLine = { _, _, line -> lines += line },
            onNotice = { message, _, fields -> lines += "$message $fields" },
        ).also { host = it }
    }

    private fun signer(notaryUrl: String): TradingApiNotaryUpgradeSigner {
        val keys = Json.parseToJsonElement(read("test-key.json")).jsonObject
        val key =
            checkNotNull(
                TradingApiKey.parse(keys.getValue("publicKey").jsonPrimitive.content, keys.getValue("secretKey").jsonPrimitive.content),
            )
        return TradingApiNotaryUpgradeSigner(TradingApiAuthenticator({ key }, clock), notaryUrl)
    }

    private fun proof() = HostedProofRequest(
        NotaryProofRequest(dealId = "deal-e2e", subjectSteamId = STEAM_ID, steamOfferId = OFFER_ID, kind = "TRADE_OFFER"),
        steamAccessToken = steamToken(exp = Clock.System.now() + 2.hours),
        steamSessionId = "0123456789abcdef01234567",
    )

    private suspend fun prove(host: ForkedProofHost): HostedProofOutcome =
        withContext(Dispatchers.Default) { host.prove(proof(), signer(env.SDK_PROVER_NOTARY as String)) }

    private suspend fun http(method: String, url: String, body: String? = null): String {
        val init: dynamic = js("({})")
        init.method = method
        if (body != null) {
            init.body = body
            init.headers = js("({ 'content-type': 'application/json' })")
        }
        val response: dynamic = js("fetch")(url, init).unsafeCast<Promise<dynamic>>().await()
        return response.text().unsafeCast<Promise<String>>().await()
    }

    private fun record(label: String, values: Map<String, Any?>) {
        val text = values.entries.joinToString(", ", "{", "}") { (k, v) -> "\"$k\":${if (v is String) "\"$v\"" else v}" }
        nodeFs.appendFileSync(nodePath.join(dir, "results.jsonl"), "{\"case\":\"$label\",\"values\":$text}\n")
    }

    private fun stats(): String? = lines.lastOrNull { "stats peakRssMb" in it }?.substringAfter("stats ")?.substringBefore("\"")

    private fun assertNoSecretLogged() {
        val token = proof().steamAccessToken
        for (line in lines) {
            assertFalse("dmar ed25519 " in line.replace("dmar ed25519 <redacted>", ""), line)
            assertFalse(token.substringBefore('.') + "." in line, line)
        }
    }

    @Test
    fun a_proof_through_the_bundle_validates() = runTest(timeout = STAND_TIMEOUT) {
        if (dir == null || env.SDK_PROVER_EXPECT == "oom") return@runTest
        setGlue("ok")
        http("GET", "${env.SDK_PROVER_BACKEND}/challenge")
        val started = TimeSource.Monotonic.markNow()
        val outcome = prove(host())
        val wall = started.elapsedNow()
        val proved = assertIs<HostedProofOutcome.Proved>(outcome, "$outcome / ${lines.takeLast(15)}")
        val verdict = Json.parseToJsonElement(
            http(
                "POST",
                "${env.SDK_PROVER_BACKEND}/exchange/v1/p2p/ext/notary",
                """{"dealId":"deal-e2e","proofPayload":"${proved.presentation}"}""",
            ),
        ).jsonObject
        assertTrue(verdict.getValue("valid").jsonPrimitive.boolean, "$verdict")
        val glue = read("logs/glue.log").lines().last { "upgrade" in it }
        assertTrue("signed=true" in glue && "bearer=false" in glue, glue)
        assertNoSecretLogged()
        record("proof", mapOf("wallMs" to wall.inWholeMilliseconds, "stats" to stats(), "presentationB64" to proved.presentation.length))
    }

    @Test
    fun the_notarys_refusals_reach_the_sdk_through_ws() = runTest(timeout = STAND_TIMEOUT) {
        if (dir == null || env.SDK_PROVER_EXPECT == "oom") return@runTest
        val seen = mutableMapOf<String, ProofFailure>()
        for (mode in listOf("401", "503", "close1013")) {
            setGlue(mode)
            // A host per refusal: each one's backoff would hold the next proof back.
            val host = host()
            seen[mode] = assertIs<HostedProofOutcome.Failed>(prove(host)).failure
            host.close()
        }
        assertEquals(ProofFailure.UpgradeRefused(401), seen["401"])
        assertEquals(ProofFailure.UpgradeRefused(503), seen["503"])
        assertEquals(1013, assertIs<ProofFailure.SessionClosed>(seen["close1013"]).code)
        record("refusals", seen.mapValues { it.value.summary })
    }

    @Test
    fun a_queued_childs_memory_is_measured() = runTest(timeout = STAND_TIMEOUT) {
        if (dir == null || env.SDK_PROVER_EXPECT == "oom") return@runTest
        setGlue("hold:8000")
        http("GET", "${env.SDK_PROVER_BACKEND}/challenge")
        val outcome = prove(host())
        assertIs<HostedProofOutcome.Proved>(outcome, "$outcome")
        // The hold is the notary's queue after the 101: the child is connected, the wasm initialised, MPC not begun.
        val connected = lines.lastOrNull { "notary connected" in it }?.substringAfter("rssMb=")?.substringBefore("\"")
        record(
            "queued",
            mapOf(
                "connectedRssMb" to connected,
                "stats" to stats(),
            ),
        )
        val verdict =
            http(
                "POST",
                "${env.SDK_PROVER_BACKEND}/exchange/v1/p2p/ext/notary",
                """{"dealId":"deal-e2e","proofPayload":"${(outcome as HostedProofOutcome.Proved).presentation}"}""",
            )
        assertTrue("\"valid\":true" in verdict, verdict)
    }

    private companion object {
        const val OFFER_ID = "5550001"
        val STAND_TIMEOUT = 5.minutes
    }
}
