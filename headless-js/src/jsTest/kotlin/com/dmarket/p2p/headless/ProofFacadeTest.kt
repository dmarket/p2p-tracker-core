package com.dmarket.p2p.headless

import com.dmarket.p2p.headless.node.join
import com.dmarket.p2p.headless.node.nodeProcess
import com.dmarket.p2p.headless.node.readFile
import com.dmarket.p2p.headless.node.tempDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Proofs through the facade: a tracker built with `prover` forks the package's `prover-child.mjs` (here a fake one
 * that speaks the IPC) for a deal that needs a proof, and tells the host how each ended, what the processes printed
 * and what the host did with them. DMarket and Steam are a mock; the prover processes are real.
 */
class ProofFacadeTest {
    private val host = TestHost()
    private val backend = host.backend
    private val handles = mutableListOf<Any>()

    /** Everything the host was handed, in order: outcomes as `outcome <json>`, lines as `line <stream> <text>`. */
    private val seen = mutableListOf<String>()

    @AfterTest
    fun stopAll() {
        handles.forEach { runCatching { stop(it) } }
    }

    private fun proverOptions(vararg fields: Pair<String, Any>): dynamic {
        val o: dynamic = js("({})")
        fields.forEach { (key, value) -> o[key] = value }
        return o
    }

    private suspend fun tracker(root: String, prover: dynamic = proverOptions()): Any {
        val options = host.options(tempDir())
        options.prover = prover
        options.onProofOutcome = { outcome: dynamic -> seen += "outcome ${js("JSON.stringify")(outcome)}" }
        options.onProverLine = { stream: String, line: String -> seen += "line $stream $line" }
        val onEvent = options.onEvent
        options.onEvent = { json: String ->
            onEvent(json)
            seen += "event $json"
        }
        return createHeadlessTrackerForTest(options, backend.engine, idleScope(), proverPackageRoot = root).also { handles += it }
    }

    /** A seller deal the backend wants proven, whose offer Steam says was accepted: a decisive transition. */
    private fun dealToProve() {
        backend.heartbeatBody = """
            {"activeTracking":[{"dealId":"deal-1","steamOfferId":"555","watch":["GetTradeOffer"],"proofRequired":true,"role":"seller"}],
             "directives":[],"ttlSeconds":100,"linkedSteamId":"$STEAM_ID"}
        """.trimIndent()
        backend.offerBody = """{"response":{"offer":{"tradeofferid":"555","accountid_other":1,"trade_offer_state":3,
            "time_created":1,"time_updated":2}}}"""
    }

    private suspend fun ready(handle: Any) {
        pushTradingApiKey(handle, PUBLIC_KEY, SECRET_SEED).await()
        pushSteamToken(handle, TOKEN).await()
    }

    private fun outcomes(): List<dynamic> = seen.filter { it.startsWith("outcome ") }.map { js("JSON.parse")(it.removePrefix("outcome ")) }

    private fun hostLines(): List<dynamic> = seen.filter {
        it.startsWith("line host ")
    }.map { js("JSON.parse")(it.removePrefix("line host ")) }

    private fun assertNothingSecret() {
        for (text in seen) {
            for (secret in listOf(
                TOKEN.jwtPayload(),
                SECRET_SEED,
                SESSION_COOKIE,
            )) {
                assertFalse(secret in text, "a callback carries a secret: $text")
            }
            assertFalse(SIGNED.containsMatchIn(text), "a callback carries a signed header: $text")
            if (!text.startsWith("event ")) assertFalse(PUBLIC_KEY in text, "a prover line carries the upgrade's key header: $text")
        }
    }

    /** Real time: the prover processes' deadlines are their own, not the test scheduler's. */
    private suspend fun waitFor(timeout: Duration = 5.seconds, what: () -> Boolean) = withContext(Dispatchers.Default) {
        val started = TimeSource.Monotonic.markNow()
        while (!what()) {
            check(started.elapsedNow() < timeout) { "timed out: $seen" }
            delay(20.milliseconds)
        }
    }

    @Test
    fun a_proof_made_in_a_prover_process_is_reported_without_a_failure_and_submitted() = runTest {
        dealToProve()
        val handle = tracker(fakeProverPackage("prove"))
        ready(handle)
        cycle(handle).await()

        val outcome = outcomes().single()
        assertEquals("deal-1", outcome.dealId)
        assertNull(outcome.failure)
        assertTrue(backend.requested.contains("POST /exchange/v1/p2p/ext/notary"), "${backend.requested}")
        assertEquals(listOf("proof started", "proof made"), hostLines().map { it.msg as String }.filter { it.startsWith("proof") })
        assertTrue(seen.any { it.startsWith("line stdout ") && "<redacted>" in it }, "the child's output reaches the host, redacted: $seen")
        assertTrue(seen.any { it.startsWith("line stderr ") })
        assertNothingSecret()
    }

    @Test
    fun a_prover_killed_at_its_deadline_is_reported_before_the_loops_proof_failed_event() = runTest {
        dealToProve()
        val handle = tracker(fakeProverPackage("hang-before-connect"), proverOptions("connectDeadlineMs" to 1_500))
        ready(handle)
        cycle(handle).await()

        val failure = outcomes().single().failure
        assertEquals(listOf("prover_killed", "connect_deadline", null), listOf(failure.type, failure.cause, failure.detail))
        assertEquals("the prover was killed: no connect before the deadline", failure.summary)
        val outcomeAt = seen.indexOfFirst { it.startsWith("outcome ") }
        val eventAt = seen.indexOfFirst { it.startsWith("event ") && "\"ProofFailed\"" in it }
        assertTrue(eventAt > outcomeAt && outcomeAt >= 0, "the outcome comes before the event: $seen")
        val warning = hostLines().single { it.msg == "proof failed" }
        assertEquals(listOf("warn", "deal-1"), listOf(warning.level, warning.dealId))
        assertNothingSecret()
    }

    @Test
    fun a_failure_that_quotes_the_token_reaches_the_host_redacted() = runTest {
        dealToProve()
        val handle = tracker(fakeProverPackage("fail-with-token"))
        ready(handle)
        cycle(handle).await()

        val failure = outcomes().single().failure
        assertEquals("other", failure.type)
        assertEquals("GET failed: access_token=<redacted> <redacted>", failure.detail)
        assertEquals(failure.detail, failure.summary)
        assertNothingSecret()
    }

    @Test
    fun the_child_runs_the_package_script_with_no_argument_a_minimal_env_and_no_exec_argv() = runTest {
        dealToProve()
        val report = join(tempDir(), "report.json")
        val root = fakeProverPackage("prove", report)
        val handle = tracker(root)
        ready(handle)
        nodeProcess.env.DMARKET_SDK_CONFIG = "/etc/sdk/config.json"
        nodeProcess.env.SDK_MANAGEMENT_SECRET = "management-secret-value"
        try {
            cycle(handle).await()
        } finally {
            js("delete process.env.DMARKET_SDK_CONFIG")
            js("delete process.env.SDK_MANAGEMENT_SECRET")
        }
        val seenByChild = js("JSON.parse")(readFile(report, "utf8").await())
        val argv = (seenByChild.argv as Array<String>).toList()
        assertEquals(2, argv.size, "node and the script, nothing else: $argv")
        assertEquals(join(root, "prover-child.mjs"), argv[1])
        assertEquals(0, (seenByChild.execArgv as Array<String>).size)
        // macOS adds `__CF_USER_TEXT_ENCODING` to every process it starts; the host passes nothing of the kind.
        val env = (js("Object.keys")(seenByChild.env) as Array<String>).filterNot { it.startsWith("__CF_") }.toSet()
        assertTrue(env.all { it in setOf("PATH", "TZ", "LANG", "LC_ALL", "TMPDIR", "SYSTEMROOT") }, "$env")
    }

    @Test
    fun a_rotated_key_after_a_refused_upgrade_lets_the_next_proof_start() = runTest {
        dealToProve()
        val handle = tracker(fakeProverPackage("refuse-401-once"))
        ready(handle)
        cycle(handle).await()
        assertEquals("upgrade_refused", outcomes().last().failure.type)

        host.advance(5.seconds)
        cycle(handle).await()
        val held = outcomes().last().failure
        assertEquals("not_started", held.type, "the 401 holds the next proof back")

        assertEquals("ROTATED", pushTradingApiKey(handle, OTHER_PUBLIC_KEY, OTHER_SECRET_SEED).await().change)
        host.advance(5.seconds)
        cycle(handle).await()
        assertNull(outcomes().last().failure, "the rotated key lifted the wait: ${outcomes().map { js("JSON.stringify")(it) }}")
        assertEquals(2, hostLines().count { it.msg == "proof started" })
    }

    @Test
    fun stop_kills_a_proving_child_at_once() = runTest {
        dealToProve()
        val handle = tracker(fakeProverPackage("hang-after-connect"), proverOptions("sessionDeadlineMs" to 60_000))
        ready(handle)
        val running = cycle(handle)
        running.catch { }
        waitFor { seen.any { it.startsWith("line stderr ") } }

        val stopped = TimeSource.Monotonic.markNow()
        stop(handle).await()
        waitFor { hostLines().any { it.msg == "prover exited" } }
        val exited = hostLines().single { it.msg == "prover exited" }
        assertEquals(listOf("SIGKILL", "true"), listOf(exited.signal, exited.killedBySdk))
        assertTrue(stopped.elapsedNow() < 2.seconds, "killed without waiting out the exit grace")
    }

    @Test
    fun invalid_prover_options_are_refused_by_name_and_never_by_value() = runTest {
        val root = fakeProverPackage("prove")
        val cases = listOf(
            "prover.maxConcurrency" to proverOptions("maxConcurrency" to 3),
            "prover.maxConcurrency" to proverOptions("maxConcurrency" to 1.5),
            "prover.proofsPerChild" to proverOptions("proofsPerChild" to 0),
            "prover.proofsPerChild" to proverOptions("proofsPerChild" to 6),
            "prover.threadCount" to proverOptions("threadCount" to 17),
            "prover.notaryUrl" to proverOptions("notaryUrl" to "https://$MARKER"),
            "prover.connectDeadlineMs" to proverOptions("connectDeadlineMs" to 0),
            "prover.sessionDeadlineMs" to proverOptions("sessionDeadlineMs" to -1),
            "prover.connectDeadlineMs" to proverOptions("connectDeadlineMs" to MARKER),
            "prover.assetsDir" to proverOptions("assetsDir" to 7),
            "prover.unknownField" to proverOptions("unknownField" to MARKER),
            "prover" to MARKER,
        )
        for ((field, prover) in cases) {
            val error = assertFailsWith<Throwable>(field) { tracker(root, prover) }
            val message = error.message.orEmpty()
            assertTrue(field in message, "names $field: $message")
            assertFalse(MARKER in message || MARKER in error.stackTraceToString(), "quotes the value: $message")
        }
    }

    @Test
    fun missing_prover_files_are_refused_when_the_tracker_is_built() = runTest {
        val noPkg = tempDir()
        val error = assertFailsWith<Throwable> { tracker(fakeProverPackage("prove"), proverOptions("assetsDir" to noPkg)) }
        assertEquals("prover files not found: ${join(join(noPkg, "pkg"), "client_wasm_bg.wasm")} is missing", error.message)

        val noScript = tempDir()
        val script = assertFailsWith<Throwable> { tracker(noScript) }
        assertEquals("prover files not found: ${join(noScript, "prover-child.mjs")} is missing", script.message)

        // Outside the package, from the public entry point, the package itself cannot be found.
        val options = host.options(tempDir())
        options.prover = proverOptions()
        val installed = assertFailsWith<Throwable> { createHeadlessTracker(options) }
        assertEquals("prover files not found: @dmarket/p2p-tracker-headless/package.json cannot be resolved", installed.message)
    }

    private companion object {
        val TOKEN = steamToken()
        const val MARKER = "value-that-must-not-show"

        /** A signed header's value next to its name, or a bare signature: what must never reach a host. */
        val SIGNED = Regex("""dmar ed25519 [0-9a-f]{8}|X-Sign-Date\W+\d|X-Request-Sign\W+dmar""")

        /** The `steamLoginSecure` cookie value a proven read builds from the token: its `steamid%7C%7C` prefix. */
        const val SESSION_COOKIE = "$STEAM_ID%7C%7C"
    }
}
