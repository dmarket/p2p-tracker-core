package com.dmarket.p2p.sdk.prover

import com.dmarket.p2p.sdk.SdkLog
import com.dmarket.p2p.sdk.node.NodeModules
import com.dmarket.p2p.tracker.notary.HostedProofOutcome
import com.dmarket.p2p.tracker.notary.HostedProofRequest
import com.dmarket.p2p.tracker.notary.KillCause
import com.dmarket.p2p.tracker.notary.NotaryUpgradeSigner
import com.dmarket.p2p.tracker.notary.ProofFailure
import com.dmarket.p2p.tracker.port.host.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Bounds on a prover process.
 *
 * @property connectDeadline from the proof's start (the fork, for a fresh child) to the child asking for its upgrade
 *   headers: catches a child that wedges before it connects.
 * @property sessionDeadline from that ask to the answer: the notary's queue wait plus the session.
 * @property proofsPerChild proofs one child serves before it is ended; 1 means a fresh process per proof.
 * @property exitGrace how long an ended child gets to exit before it is killed.
 */
class ProverLimits(
    val connectDeadline: Duration = 30.seconds,
    val sessionDeadline: Duration = 240.seconds,
    val proofsPerChild: Int = 1,
    val exitGrace: Duration = 5.seconds,
) {
    init {
        require(proofsPerChild in 1..MAX_PROOFS_PER_CHILD) { "proofsPerChild must be 1..$MAX_PROOFS_PER_CHILD, was $proofsPerChild" }
    }

    companion object {
        /** The extension recycles its prover after this many proofs; a child never serves more. */
        const val MAX_PROOFS_PER_CHILD: Int = 5
    }
}

/**
 * Runs each proof in a forked Node process ([script] with [args]), the Node counterpart of the extension's
 * offscreen document. A process, not a worker thread: where the kernel kills by process, an out-of-memory kill takes
 * the child and the SDK lives on.
 *
 * Per proof: the request and the Steam token go to the child over IPC; when the child asks, the upgrade is signed
 * ([NotaryUpgradeSigner]) and the headers go back the same way; the child answers a presentation or a
 * [ProofFailure]. A child that does not ask within [ProverLimits.connectDeadline], or does not answer within
 * [ProverLimits.sessionDeadline] of asking, is killed: [KillCause.CONNECT_DEADLINE] / [KillCause.SESSION_DEADLINE].
 * One killed by a signal the SDK did not send died for memory ([KillCause.OUT_OF_MEMORY]).
 *
 * The child is forked with [env] only (no secret, no config path), no inherited `execArgv`, and nothing secret in
 * its argv. Its stdout and stderr go to [log] line by line, redacted ([ProverOutput]); nothing the IPC carries is
 * ever logged.
 *
 * A failure [ProofProblems] makes wait holds the next proofs back ([backoff]) without forking: they fail at once as
 * [ProofFailure.NotStarted]. Every finished proof is reported to [onOutcome] (`null`: proved).
 */
class ForkedProofHost(
    private val script: String,
    private val args: List<String>,
    private val settings: ProverSettings,
    private val log: SdkLog,
    private val clock: Clock,
    private val limits: ProverLimits = ProverLimits(),
    private val env: Map<String, String> = minimalProverEnv(),
    private val backoff: ProverBackoff = ProverBackoff(),
    private val onOutcome: (dealId: String, failure: ProofFailure?) -> Unit = { _, _ -> },
) : SdkProofHost {
    private val lock = Mutex()
    private val idle = ArrayDeque<ForkedChild>()
    private val live = mutableSetOf<ForkedChild>()
    private var nextProofId = 1

    /** Children forked so far; for tests and diagnostics. */
    var forks: Int = 0
        private set

    override suspend fun prove(proof: HostedProofRequest, upgrade: NotaryUpgradeSigner): HostedProofOutcome {
        val dealId = proof.request.dealId
        backoff.blocked(clock.now())?.let { reason ->
            val held = ProofFailure.NotStarted(reason)
            onOutcome(dealId, held)
            return HostedProofOutcome.Failed(held)
        }
        val (child, id) = lock.withLock { (takeIdle() ?: fork()) to nextProofId++ }
        log.info("proof started", "dealId" to dealId, "pid" to child.pid)
        val outcome = try {
            run(child, id, proof, upgrade)
        } catch (e: CancellationException) {
            child.kill()
            throw e
        }
        lock.withLock { release(child, outcome) }
        when (outcome) {
            is HostedProofOutcome.Proved -> {
                backoff.succeeded()
                log.info("proof made", "dealId" to dealId, "pid" to child.pid)
                onOutcome(dealId, null)
            }
            is HostedProofOutcome.Failed -> {
                backoff.failed(outcome.failure, clock.now())
                log.warn("proof failed", "dealId" to dealId, "pid" to child.pid, "reason" to ProverOutput.redact(outcome.failure.summary))
                onOutcome(dealId, outcome.failure)
            }
        }
        return outcome
    }

    /** A newly held key may answer a notary 401: the next proof need not wait for the old one's backoff. */
    override fun keyChanged() = backoff.keyChanged()

    /** Kills every prover process, idle or proving: the SDK is stopping. */
    override fun close() {
        live.forEach { it.kill() }
        live.clear()
        idle.clear()
    }

    private suspend fun run(child: ForkedChild, id: Int, proof: HostedProofRequest, upgrade: NotaryUpgradeSigner): HostedProofOutcome {
        val started = TimeSource.Monotonic.markNow()
        var connected: TimeMark? = null
        if (!child.send(ProverIpc.encode(ToChild.Prove(id, proof.request, proof.steamAccessToken, proof.steamSessionId, settings)))) {
            return killed(KillCause.EXITED, "the prover's channel was closed")
        }
        child.served++
        while (true) {
            val left = connected?.let { limits.sessionDeadline - it.elapsedNow() } ?: (limits.connectDeadline - started.elapsedNow())
            val event = withTimeoutOrNull(left.coerceAtLeast(Duration.ZERO)) { child.events.receive() }
            if (event == null) {
                child.kill()
                return killed(if (connected == null) KillCause.CONNECT_DEADLINE else KillCause.SESSION_DEADLINE, null)
            }
            when (event) {
                is ChildEvent.Exited -> return killed(event.cause(), event.detail())
                is ChildEvent.Message -> {
                    val message = runCatching { ProverIpc.fromChild(event.text) }.getOrNull()?.takeIf { it.id == id } ?: continue
                    when (message) {
                        is FromChild.HeadersWanted -> {
                            if (connected == null) connected = TimeSource.Monotonic.markNow()
                            val reply = try {
                                ToChild.Headers(id, headers = upgrade.sign().values)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                ToChild.Headers(id, error = "the upgrade could not be signed: ${e.message ?: "no detail"}")
                            }
                            if (!child.send(ProverIpc.encode(reply))) return killed(KillCause.EXITED, "the prover's channel was closed")
                        }
                        is FromChild.Proved -> return HostedProofOutcome.Proved(message.presentation)
                        is FromChild.Failed -> return HostedProofOutcome.Failed(message.failure)
                    }
                }
            }
        }
    }

    private fun killed(cause: KillCause, detail: String?) = HostedProofOutcome.Failed(ProofFailure.ProverKilled(cause, detail))

    private fun takeIdle(): ForkedChild? {
        while (true) {
            val child = idle.removeFirstOrNull() ?: return null
            if (!child.gone) return child
        }
    }

    private fun release(child: ForkedChild, outcome: HostedProofOutcome) {
        val killed = outcome is HostedProofOutcome.Failed && outcome.failure is ProofFailure.ProverKilled
        if (killed || child.gone || child.served >= limits.proofsPerChild) {
            live.remove(child)
            child.end(limits.exitGrace)
        } else {
            idle.addLast(child)
        }
    }

    private fun fork(): ForkedChild {
        val options: dynamic = js("({})")
        options.env = env.toJsObject()
        options.execArgv = emptyArray<String>()
        options.stdio = arrayOf("ignore", "pipe", "pipe", "ipc")
        options.serialization = "json"
        val process = NodeModules.childProcess.fork(script, args.toTypedArray(), options)
        forks++
        return ForkedChild(process, log).also { live += it }
    }
}

/**
 * The environment a prover process gets: the few variables a Node process and its locale need, never the SDK's own
 * (no management secret, no config path, no `NODE_OPTIONS`).
 */
internal fun minimalProverEnv(from: dynamic = NodeModules.process.env): Map<String, String> =
    PROVER_ENV_ALLOWED.mapNotNull { name -> (from[name] as? String)?.let { name to it } }.toMap()

private val PROVER_ENV_ALLOWED = listOf("PATH", "TZ", "LANG", "LC_ALL", "TMPDIR", "SYSTEMROOT")

private fun Map<String, String>.toJsObject(): dynamic {
    val target: dynamic = js("({})")
    forEach { (key, value) -> target[key] = value }
    return target
}

private sealed interface ChildEvent {
    data class Message(val text: String) : ChildEvent

    data class Exited(val code: Int?, val signal: String?, val error: String? = null) : ChildEvent {
        /** A `SIGKILL` the SDK did not send is the kernel's out-of-memory kill; anything else ended it otherwise. */
        fun cause(): KillCause = if (signal == "SIGKILL") KillCause.OUT_OF_MEMORY else KillCause.EXITED

        fun detail(): String = error ?: signal?.let { "signal $it" } ?: "exit code $code"
    }
}

/** One forked prover process: its IPC messages and its end as [events], its output into the log. */
private class ForkedChild(private val process: dynamic, private val log: SdkLog) {
    val events = Channel<ChildEvent>(Channel.UNLIMITED)
    val pid: String = (process.pid as? Number)?.toString() ?: "?"
    var served = 0
    var gone = false
        private set
    private var killedBySdk = false

    init {
        process.on("message") { message: dynamic ->
            if (jsTypeOf(message) == "string") events.trySend(ChildEvent.Message(message.unsafeCast<String>()))
        }
        process.on("exit") { code: dynamic, signal: dynamic ->
            gone = true
            val exit = ChildEvent.Exited((code as? Number)?.toInt(), signal as? String)
            log.info(
                "prover exited",
                "pid" to pid,
                "code" to exit.code?.toString(),
                "signal" to exit.signal,
                "killedBySdk" to killedBySdk.toString(),
            )
            events.trySend(exit)
        }
        process.on("error") { error: dynamic ->
            gone = true
            events.trySend(ChildEvent.Exited(null, null, "the prover process failed: ${error?.message ?: "no detail"}"))
        }
        pipe(process.stdout, "stdout")
        pipe(process.stderr, "stderr")
    }

    /** Sends [text] over IPC; `false` when the channel is gone. */
    fun send(text: String): Boolean = try {
        if (gone || process.connected != true) {
            false
        } else {
            process.send(text)
            true
        }
    } catch (_: Throwable) {
        false
    }

    fun kill() {
        if (gone) return
        killedBySdk = true
        runCatching { process.kill("SIGKILL") }
    }

    /** Closes the IPC channel, which ends the child; kills it if it is still there after [grace]. */
    fun end(grace: Duration) {
        if (gone) return
        runCatching { if (process.connected == true) process.disconnect() }
        val timer = js("setTimeout")(
            {
                if (!gone) kill()
            },
            grace.inWholeMilliseconds.toDouble(),
        )
        runCatching { timer.unref() }
    }

    private fun pipe(stream: dynamic, name: String) {
        if (stream == null) return
        var pending = ""
        stream.setEncoding("utf8")
        stream.on("data") { chunk: String ->
            val lines = (pending + chunk).split('\n')
            pending = lines.last()
            lines.dropLast(1).forEach { emit(name, it) }
        }
        stream.on("end") {
            if (pending.isNotEmpty()) emit(name, pending)
            pending = ""
        }
    }

    private fun emit(stream: String, line: String) {
        val text = line.trimEnd('\r')
        if (text.isBlank()) return
        val fields = arrayOf("pid" to pid, "stream" to stream, "line" to ProverOutput.redact(text))
        if (stream == "stderr") log.warn("prover output", *fields) else log.info("prover output", *fields)
    }
}
