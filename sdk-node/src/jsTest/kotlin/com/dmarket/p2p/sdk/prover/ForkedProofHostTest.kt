package com.dmarket.p2p.sdk.prover

import com.dmarket.p2p.sdk.STEAM_ID
import com.dmarket.p2p.sdk.SdkLog
import com.dmarket.p2p.sdk.TestClock
import com.dmarket.p2p.sdk.node.NodeModules
import com.dmarket.p2p.sdk.steamToken
import com.dmarket.p2p.tracker.notary.HostedProofOutcome
import com.dmarket.p2p.tracker.notary.HostedProofRequest
import com.dmarket.p2p.tracker.notary.KillCause
import com.dmarket.p2p.tracker.notary.NotaryProofRequest
import com.dmarket.p2p.tracker.notary.NotaryUpgradeHeaders
import com.dmarket.p2p.tracker.notary.NotaryUpgradeSigner
import com.dmarket.p2p.tracker.notary.ProofFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The SDK's side of a prover process, against a fake child: a Node script that speaks the IPC and, depending on
 * its mode, proves, refuses, wedges, exits or kills itself. It also prints what it was sent, as a careless prover's
 * trace would, so the log redaction is tested on the real output path.
 */
class ForkedProofHostTest {
    private val clock = TestClock()
    private val lines = mutableListOf<String>()
    private val log = SdkLog(clock) { lines += it }
    private val token = steamToken()
    private val signature = "dmar ed25519 " + "ef".repeat(64)
    private val upgrade = NotaryUpgradeSigner {
        NotaryUpgradeHeaders(mapOf("X-Api-Key" to API_KEY, "X-Sign-Date" to SIGN_DATE, "X-Request-Sign" to signature))
    }
    private val outcomes = mutableListOf<Pair<String, ProofFailure?>>()
    private val proof = HostedProofRequest(
        NotaryProofRequest(dealId = "deal-1", subjectSteamId = STEAM_ID, steamOfferId = "555", kind = "TRADE_OFFER"),
        steamAccessToken = token,
        steamSessionId = SESSION_ID,
    )
    private val settings = ProverSettings(notaryUrl = "wss://notary.test/provenance/v1/", threadCount = 2, assetsDir = "/nowhere")

    private suspend fun tempDir(): String {
        val prefix = NodeModules.path.join(NodeModules.os.tmpdir(), "sdk-prover-test-") as String
        return NodeModules.fs.mkdtemp(prefix).unsafeCast<Promise<String>>().await()
    }

    private val hosts = mutableListOf<ForkedProofHost>()

    @AfterTest
    fun killChildren() {
        hosts.forEach { it.close() }
    }

    private suspend fun host(mode: String, report: String = "", proofsPerChild: Int = 1, deadline: Long = 1_500): ForkedProofHost {
        val dir = tempDir()
        val script = NodeModules.path.join(dir, "fake-prover.js") as String
        NodeModules.fs.writeFile(script, FAKE_CHILD).unsafeCast<Promise<Any?>>().await()
        return ForkedProofHost(
            script = script,
            args = listOf(mode, report),
            settings = settings,
            log = log,
            clock = clock,
            limits = ProverLimits(
                connectDeadline = deadline.milliseconds,
                sessionDeadline = deadline.milliseconds,
                proofsPerChild = proofsPerChild,
                exitGrace = 500.milliseconds,
            ),
            onOutcome = { deal, failure -> outcomes += deal to failure },
        ).also { hosts += it }
    }

    /** Real time: the deadlines are the child's, not the test scheduler's. */
    private suspend fun ForkedProofHost.proveNow(): HostedProofOutcome = withContext(Dispatchers.Default) { prove(proof, upgrade) }

    private fun assertNoSecretLogged() {
        for (line in lines) {
            for (secret in listOf(API_KEY, SIGN_DATE, "ef".repeat(64), token, SESSION_ID)) {
                assertFalse(secret in line, "a log line carries a secret: $line")
            }
        }
    }

    @Test
    fun a_proof_gets_its_headers_when_it_asks_and_no_log_line_carries_one() = runTest {
        val outcome = host("prove").proveNow()

        val proved = assertIs<HostedProofOutcome.Proved>(outcome)
        assertTrue(proved.presentation.startsWith("cHJvb2Y=:"))
        assertTrue(lines.any { "prover output" in it && "<redacted>" in it }, "the child's output is logged, redacted: $lines")
        assertTrue(lines.any { "\"stream\":\"stderr\"" in it })
        assertNoSecretLogged()
        assertEquals(listOf<Pair<String, ProofFailure?>>("deal-1" to null), outcomes)
    }

    @Test
    fun the_child_gets_no_secret_in_argv_or_env_and_no_exec_argv() = runTest {
        val report = NodeModules.path.join(tempDir(), "report.json") as String
        NodeModules.process.env.DMARKET_SDK_CONFIG = "/etc/sdk/config.json"
        NodeModules.process.env.SDK_MANAGEMENT_SECRET = "management-secret-value"
        try {
            host("prove", report).proveNow()
        } finally {
            js("delete process.env.DMARKET_SDK_CONFIG")
            js("delete process.env.SDK_MANAGEMENT_SECRET")
        }
        val seen = NodeModules.fs.readFile(report, "utf8").unsafeCast<Promise<String>>().await()
        for (secret in listOf(token, SESSION_ID, API_KEY, "management-secret-value", "DMARKET_SDK_CONFIG", "NODE_OPTIONS")) {
            assertFalse(secret in seen, "the child sees $secret: $seen")
        }
        assertTrue("\"execArgv\":[]" in seen, seen)
    }

    @Test
    fun a_child_that_never_connects_is_killed_at_the_connect_deadline() = runTest {
        val outcome = host("hang-before-connect").proveNow()
        assertEquals(ProofFailure.ProverKilled(KillCause.CONNECT_DEADLINE), assertIs<HostedProofOutcome.Failed>(outcome).failure)
    }

    @Test
    fun a_child_still_proving_at_the_session_deadline_is_killed() = runTest {
        val outcome = host("hang-after-connect").proveNow()
        assertEquals(ProofFailure.ProverKilled(KillCause.SESSION_DEADLINE), assertIs<HostedProofOutcome.Failed>(outcome).failure)
        assertNoSecretLogged()
    }

    @Test
    fun a_child_killed_by_the_kernel_is_prover_killed_and_the_sdk_proves_again() = runTest {
        val host = host("sigkill-once")
        val first = assertIs<HostedProofOutcome.Failed>(host.proveNow()).failure
        assertEquals(KillCause.OUT_OF_MEMORY, assertIs<ProofFailure.ProverKilled>(first).cause)
        assertIs<HostedProofOutcome.Proved>(host.proveNow(), "a fresh child proves after the kill")
        assertEquals(2, host.forks)
    }

    @Test
    fun a_child_that_exits_without_an_answer_is_prover_killed() = runTest {
        val failure = assertIs<HostedProofOutcome.Failed>(host("exit").proveNow()).failure
        assertEquals(ProofFailure.ProverKilled(KillCause.EXITED, "exit code 3"), failure)
    }

    @Test
    fun a_refusal_comes_back_whole_and_holds_the_next_proof_without_a_fork() = runTest {
        val host = host("refuse-503")
        assertEquals(ProofFailure.UpgradeRefused(503), assertIs<HostedProofOutcome.Failed>(host.proveNow()).failure)
        val held = assertIs<HostedProofOutcome.Failed>(host.proveNow()).failure
        assertIs<ProofFailure.NotStarted>(held)
        assertEquals(1, host.forks)
        assertEquals(listOf<Pair<String, ProofFailure?>>("deal-1" to ProofFailure.UpgradeRefused(503), "deal-1" to held), outcomes)
    }

    @Test
    fun a_child_serves_proofs_per_child_proofs_then_a_new_one_forks() = runTest {
        val host = host("prove", proofsPerChild = 2)
        val pids = List(3) { assertIs<HostedProofOutcome.Proved>(host.proveNow()).presentation.split(':')[1] }
        assertEquals(pids[0], pids[1])
        assertNotEquals(pids[1], pids[2])
        assertEquals(2, host.forks)
    }

    @Test
    fun an_ended_child_exits() = runTest {
        val report = NodeModules.path.join(tempDir(), "report.json") as String
        host("prove", report).proveNow()
        withContext(Dispatchers.Default) { delay(1.seconds) }
        assertTrue(lines.any { "prover exited" in it }, "$lines")
    }

    private companion object {
        const val API_KEY = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9"
        const val SIGN_DATE = "1781611234"
        const val SESSION_ID = "feedfacecafebeef01234567"

        /** Mode in argv[2]; argv[3], when set, is a file to report argv, execArgv and env into. */
        const val FAKE_CHILD = """
            const fs = require('fs');
            const [mode, report] = process.argv.slice(2);
            if (report) fs.writeFileSync(report, JSON.stringify({ argv: process.argv, execArgv: process.execArgv, env: process.env }));
            let served = 0;
            let token = '';
            process.on('message', (raw) => {
              const msg = JSON.parse(raw);
              if (msg.type === 'prove') {
                served++;
                token = msg.steamAccessToken;
                console.log('proving deal ' + msg.request.dealId + ' with access_token=' + token + ' sessionid=' + msg.steamSessionId);
                if (mode === 'hang-before-connect') return;
                if (mode === 'sigkill-once' && !fs.existsSync(__filename + '.killed')) {
                  fs.writeFileSync(__filename + '.killed', '1');
                  process.kill(process.pid, 'SIGKILL');
                  return;
                }
                if (mode === 'exit') process.exit(3);
                if (mode === 'refuse-503') {
                  process.send(JSON.stringify({ type: 'failed', id: msg.id, failure: { type: 'upgrade_refused', status: 503 } }));
                  return;
                }
                process.send(JSON.stringify({ type: 'headers_wanted', id: msg.id }));
              } else if (msg.type === 'headers') {
                console.log('upgrade headers ' + JSON.stringify(msg.headers));
                console.error('X-Request-Sign: ' + msg.headers['X-Request-Sign'] + ' X-Api-Key=' + msg.headers['X-Api-Key']);
                console.log('bare ' + token);
                if (mode === 'hang-after-connect') return;
                process.send(JSON.stringify({ type: 'proved', id: msg.id, presentation: 'cHJvb2Y=:' + process.pid + ':' + served }));
              }
            });
            process.on('disconnect', () => process.exit(0));
        """
    }
}
