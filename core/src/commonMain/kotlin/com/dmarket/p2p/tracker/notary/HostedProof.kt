package com.dmarket.p2p.tracker.notary

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One proof a host runs in a context of its own, for [HostedNotaryProver].
 *
 * [request] is credential-free; the two Steam values the proven read needs travel beside it, as named
 * properties, and [toString] prints neither.
 *
 * @property steamAccessToken the Steam JWT that authenticates the proven read. Withheld from the presentation
 *   (`revealRequestTarget = false`) and never sent to DMarket.
 * @property steamSessionId the `sessionid` double-submit value a cookie-authenticated read sends beside the
 *   `steamLoginSecure` cookie built from the token. The same value the tracker's own Steam writes send.
 */
class HostedProofRequest(val request: NotaryProofRequest, val steamAccessToken: String, val steamSessionId: String) {
    override fun toString(): String = "HostedProofRequest(request=$request, steamAccessToken=<redacted>, steamSessionId=<redacted>)"
}

/**
 * The signed headers that authenticate one notary upgrade (`X-Api-Key`, `X-Sign-Date`, `X-Request-Sign`). Good
 * for one connect inside the signature's window: ask [NotaryUpgradeSigner] again for the next one.
 *
 * [toString] names the headers and never their values, so the set can reach a log line by accident and say
 * nothing.
 */
class NotaryUpgradeHeaders(val values: Map<String, String>) {
    override fun toString(): String = "NotaryUpgradeHeaders(${values.keys.joinToString()}: <redacted>)"
}

/**
 * Signs a notary upgrade for the account the tracker serves. Each call signs afresh, so a host asks right before
 * it connects: the signature's ±2 minute window then starts at the connect, not at whatever came before it.
 * Throws when nothing can sign (no key held).
 */
fun interface NotaryUpgradeSigner {
    suspend fun sign(): NotaryUpgradeHeaders
}

/**
 * Runs one proof where the prover can run, and says how it went. The headless tracker's prover hands each proof
 * here ([HostedNotaryProver]); a Node host runs it in a child process.
 *
 * The host gets the Steam token with the request, and [upgrade] in place of any DMarket credential: the signing
 * key stays with the caller, and the host asks [upgrade] for headers only when its prover is about to connect.
 *
 * Implementations answer every outcome with a [HostedProofOutcome] rather than throwing, so the failure keeps
 * its structure; a throw is read as [ProofFailure.Other].
 */
fun interface ProofHost {
    suspend fun prove(proof: HostedProofRequest, upgrade: NotaryUpgradeSigner): HostedProofOutcome
}

/** How a hosted proof ended. */
sealed interface HostedProofOutcome {
    /** [presentation] is the base64 `postcard` `Presentation`, ready for `POST /notary`. */
    data class Proved(val presentation: String) : HostedProofOutcome

    data class Failed(val failure: ProofFailure) : HostedProofOutcome
}

/**
 * Why a hosted proof produced no presentation, kept apart by what a host does about each: the notary refused the
 * upgrade (and with which status) or closed the session (and with which code), the notary could not be reached,
 * the protocol versions differ, the Steam leg failed, the read did not fit the MPC limits, the prover was killed,
 * or the proof was not started at all.
 *
 * Every text here is safe to log: it comes from the prover, the notary or Steam, and carries no credential. A
 * host should still pass it through its log redaction, as for any text from the other side of a wire.
 */
@Serializable
sealed interface ProofFailure {
    /** One line for logs and for the loop's `ProofFailed` event. */
    val summary: String

    /** The notary answered the upgrade with [status] instead of `101 Switching Protocols`. */
    @Serializable
    @SerialName("upgrade_refused")
    data class UpgradeRefused(val status: Int) : ProofFailure {
        override val summary: String get() = "the notary refused the upgrade: HTTP $status"
    }

    /** The notary closed the session after the `101` with [code] and, when it gave one, [reason]. */
    @Serializable
    @SerialName("session_closed")
    data class SessionClosed(val code: Int, val reason: String? = null) : ProofFailure {
        override val summary: String get() = "the notary closed the session: $code${reason?.let { " $it" }.orEmpty()}"
    }

    /** The notary did not answer the upgrade at all: DNS, TCP or TLS to the notary failed. */
    @Serializable
    @SerialName("notary_unreachable")
    data class NotaryUnreachable(val detail: String) : ProofFailure {
        override val summary: String get() = "the notary could not be reached: $detail"
    }

    /** The notary runs another version of the protocol than this prover. Nothing but an update fixes it. */
    @Serializable
    @SerialName("version_mismatch")
    data class VersionMismatch(val detail: String) : ProofFailure {
        override val summary: String get() = "the notary's protocol version is not this prover's: $detail"
    }

    /** The Steam leg failed: no connection or no TLS ([status] `null`), or Steam answered [status], not 2xx. */
    @Serializable
    @SerialName("steam_failed")
    data class SteamFailed(val status: Int? = null, val detail: String) : ProofFailure {
        override val summary: String get() = "the proven Steam read failed${status?.let { ": HTTP $it" }.orEmpty()}: $detail"
    }

    /** The read did not fit the MPC limits the proof was configured with. */
    @Serializable
    @SerialName("limits_exceeded")
    data class LimitsExceeded(val detail: String) : ProofFailure {
        override val summary: String get() = "the read exceeds the proof's MPC limits: $detail"
    }

    /** The host killed the prover, or it died: see [KillCause]. */
    @Serializable
    @SerialName("prover_killed")
    data class ProverKilled(val cause: KillCause, val detail: String? = null) : ProofFailure {
        override val summary: String get() = "the prover was killed: ${cause.wireName}${detail?.let { " ($it)" }.orEmpty()}"
    }

    /** No prover was started: [reason]. */
    @Serializable
    @SerialName("not_started")
    data class NotStarted(val reason: String) : ProofFailure {
        override val summary: String get() = "proof not started: $reason"
    }

    /** Any other failure, with the prover's own words. */
    @Serializable
    @SerialName("other")
    data class Other(val detail: String) : ProofFailure {
        override val summary: String get() = detail
    }
}

/** Why a prover process ended before its proof did. */
@Serializable
enum class KillCause(val wireName: String) {
    /** The kernel killed it for memory (`SIGKILL` with no deadline passed). */
    @SerialName("out_of_memory")
    OUT_OF_MEMORY("out of memory"),

    /** It had not asked for its upgrade headers by the deadline after it started. */
    @SerialName("connect_deadline")
    CONNECT_DEADLINE("no connect before the deadline"),

    /** It was still proving at the deadline after it connected. */
    @SerialName("session_deadline")
    SESSION_DEADLINE("still proving at the deadline"),

    /** It exited, or lost its channel to the host, without an answer. */
    @SerialName("exited")
    EXITED("exited without an answer"),
}

/** A hosted proof failed; [failure] says how. The message is [ProofFailure.summary], which carries no credential. */
class HostedProofException(val failure: ProofFailure) : RuntimeException(failure.summary)

/**
 * Reads what ended a proof out of what the prover's host could observe: the notary socket's refused upgrade or
 * close code, Steam's response status, a failed dial to Steam, and the prover's own error text.
 *
 * The order is what the evidence is worth. A refused upgrade is the notary's own word and comes first. The
 * protocol's version error is next, because the notary closes the socket after it and that close would otherwise
 * read as the cause. A close code other than a normal one is the notary's answer for the session. Steam's
 * failures come after the notary's, since a session the notary ended stops the Steam leg too.
 */
object ProofFailures {
    /** The wording of the protocol's version error (tlsn's verifier), matched case-insensitively. */
    const val VERSION_ERROR: String = "prover version does not match with verifier"

    private val LIMITS = Regex("""online phase|max_sent|max_recv|exceeds the (?:sent|recv|received)""", RegexOption.IGNORE_CASE)
    private val TARGET_TLS = Regex("""invalid peer certificate|certificate|tls handshake|handshake failure""", RegexOption.IGNORE_CASE)

    /** A close the notary sends when the session ended as agreed: not a cause. */
    private val NORMAL_CLOSES = setOf(1000, 1005)

    /**
     * @param upgradeStatus the HTTP status the notary answered the upgrade with, when it was not 101.
     * @param closeCode the notary socket's close code, when it closed after the 101.
     * @param closeReason the close frame's reason text.
     * @param targetStatus Steam's response status, when the prover got that far.
     * @param targetError why the dial to Steam failed, when it did.
     * @param notaryConnected whether the notary socket opened; `false` with no [upgradeStatus] means it never
     *   answered at all.
     * @param message the prover's error, or `null` when it produced a presentation.
     */
    fun classify(
        upgradeStatus: Int? = null,
        closeCode: Int? = null,
        closeReason: String? = null,
        targetStatus: Int? = null,
        targetError: String? = null,
        notaryConnected: Boolean = true,
        message: String? = null,
    ): ProofFailure? {
        val text = message.orEmpty()
        return when {
            upgradeStatus != null && upgradeStatus != SWITCHING_PROTOCOLS -> ProofFailure.UpgradeRefused(upgradeStatus)
            text.contains(VERSION_ERROR, ignoreCase = true) -> ProofFailure.VersionMismatch(text)
            closeCode != null && closeCode !in NORMAL_CLOSES -> ProofFailure.SessionClosed(
                closeCode,
                closeReason?.takeIf {
                    it.isNotBlank()
                },
            )
            !notaryConnected -> ProofFailure.NotaryUnreachable(text.ifBlank { "no answer to the upgrade" })
            targetError != null -> ProofFailure.SteamFailed(detail = targetError)
            targetStatus != null && targetStatus !in SUCCESS -> ProofFailure.SteamFailed(targetStatus, "Steam answered HTTP $targetStatus")
            message == null -> null
            LIMITS.containsMatchIn(text) -> ProofFailure.LimitsExceeded(text)
            TARGET_TLS.containsMatchIn(text) -> ProofFailure.SteamFailed(detail = text)
            else -> ProofFailure.Other(text.ifBlank { "the prover failed without a message" })
        }
    }

    private const val SWITCHING_PROTOCOLS = 101
    private val SUCCESS = 200..299
}
