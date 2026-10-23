package com.dmarket.p2p.sdk.prover

import com.dmarket.p2p.headless.prover.ForkedProofHost
import com.dmarket.p2p.headless.prover.PROVER_CHILD_ARG
import com.dmarket.p2p.headless.prover.ProverSettings
import com.dmarket.p2p.headless.prover.proverAssetsDir
import com.dmarket.p2p.sdk.STEAM_ID
import com.dmarket.p2p.sdk.SdkLog
import com.dmarket.p2p.sdk.health.HealthTracker
import com.dmarket.p2p.sdk.health.ProblemCode
import com.dmarket.p2p.sdk.node.NodeModules
import com.dmarket.p2p.sdk.steamToken
import com.dmarket.p2p.tracker.adapter.host.SystemClock
import com.dmarket.p2p.tracker.client.marketplace.TradingApiAuthenticator
import com.dmarket.p2p.tracker.client.marketplace.TradingApiNotaryUpgradeSigner
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.marketplace.TradingApiKey
import com.dmarket.p2p.tracker.notary.HostedProofOutcome
import com.dmarket.p2p.tracker.notary.HostedProofRequest
import com.dmarket.p2p.tracker.notary.NotaryProofRequest
import com.dmarket.p2p.tracker.notary.ProofFailure
import com.dmarket.p2p.tracker.runtime.HeadlessTrackerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * A real proof on a local steam-provenance stand under a container memory limit that cannot hold it
 * (`SDK_PROVER_EXPECT=oom`): the child dies, this process lives, and health reports both the kill and the low memory.
 * The stand and the proof itself are the headless library's own stand test; this is the half that reads the SDK's
 * health and the container's memory. Skipped unless `SDK_PROVER_E2E_DIR` names the stand's directory.
 */
class ProverStandTest {
    private val env: dynamic = NodeModules.process.env
    private val dir = env.SDK_PROVER_E2E_DIR as? String
    private val lines = mutableListOf<String>()
    private val clock = SystemClock()
    private val log = SdkLog(clock) { line -> lines += line }
    private var host: ForkedProofHost? = null

    @AfterTest
    fun stop() {
        host?.close()
        if (dir != null) setGlue("ok")
    }

    private fun setGlue(mode: String) {
        js("require('node:fs')").writeFileSync(NodeModules.path.join(dir, "glue-mode"), mode)
    }

    private fun read(name: String): String = js("require('node:fs')").readFileSync(NodeModules.path.join(dir, name), "utf8") as String

    private fun host(): ForkedProofHost {
        val settings = ProverSettings(
            notaryUrl = env.SDK_PROVER_NOTARY as String,
            threadCount = (env.SDK_PROVER_THREADS as? String)?.toInt() ?: ProverConfig.DEFAULT_THREADS,
            assetsDir = checkNotNull(proverAssetsDir(env.SDK_PROVER_ASSETS as? String)) { "prover files not found" },
            rootStorePem = read("stand/ca-root.pem"),
            targetAddress = env.SDK_PROVER_TARGET as String,
        )
        return ForkedProofHost(
            env.SDK_PROVER_SCRIPT as String,
            listOf(PROVER_CHILD_ARG),
            settings,
            clock,
            onLine = log::proverLine,
            onNotice = log::proverNotice,
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
        js("require('node:fs')").appendFileSync(NodeModules.path.join(dir, "results.jsonl"), "{\"case\":\"$label\",\"values\":$text}\n")
    }

    @Test
    fun a_proof_under_a_memory_limit_too_small_is_killed_and_reported() = runTest(timeout = STAND_TIMEOUT) {
        if (dir == null || env.SDK_PROVER_EXPECT != "oom") return@runTest
        setGlue("ok")
        http("GET", "${env.SDK_PROVER_BACKEND}/challenge")
        val started = TimeSource.Monotonic.markNow()
        val outcome = prove(host())
        val wall = started.elapsedNow()
        val failure = assertIs<HostedProofOutcome.Failed>(outcome).failure
        // Out of memory, or thrashing past a deadline: either way the child dies and this process lives.
        val killed = assertIs<ProofFailure.ProverKilled>(failure, "$failure")
        val health = HealthTracker(clock, "e2e")
        health.proofOutcome("deal-e2e", failure)
        val limit = cgroupMemoryLimit()
        val used = residentBytes()
        if (ProverMemory.isLow(limit, used, ProverConfig().peakMemoryBytes)) {
            health.proverMemoryLow(limit!!, used, ProverConfig().peakMemoryBytes)
        }
        val state = HeadlessTrackerState(true, SteamId(STEAM_ID), Clock.System.now() + 2.hours, SteamId(STEAM_ID), null)
        val codes = health.snapshot(state).problems.map { it.code }
        assertTrue(ProblemCode.PROVER_KILLED in codes && ProblemCode.PROVER_MEMORY_LOW in codes, "$codes")
        record(
            "oom",
            mapOf(
                "wallMs" to wall.inWholeMilliseconds,
                "limitBytes" to limit,
                "cause" to killed.cause.name,
                "codes" to "$codes",
            ),
        )
    }

    private companion object {
        const val OFFER_ID = "5550001"
        val STAND_TIMEOUT = 5.minutes
    }
}
