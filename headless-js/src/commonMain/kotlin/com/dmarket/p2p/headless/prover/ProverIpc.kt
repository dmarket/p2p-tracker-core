package com.dmarket.p2p.headless.prover

import com.dmarket.p2p.tracker.notary.NotaryProofRequest
import com.dmarket.p2p.tracker.notary.ProofFailure
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a prover process needs besides the proof itself, the same for every proof: where the notary is, how many
 * threads to prove on, where the prover's files are. Nothing secret: it goes to the child with each proof.
 *
 * @property assetsDir the directory holding the prover's `pkg/` and `transport/`.
 * @property rootStorePem PEM roots that replace the prover's bundled set for the target's certificate. A test
 *   stand's fixture CA; never set in production.
 * @property targetAddress `host:port` the child dials for the target leg in place of `serverName:443`, the TLS
 *   server name staying the target's. A test stand's echo server; never set in production.
 */
@Serializable
data class ProverSettings(
    @SerialName("notaryUrl") val notaryUrl: String,
    @SerialName("threadCount") val threadCount: Int,
    @SerialName("assetsDir") val assetsDir: String,
    @SerialName("rootStorePem") val rootStorePem: String? = null,
    @SerialName("targetAddress") val targetAddress: String? = null,
) {
    companion object {
        /**
         * The prover's MPC threads per process, unless a host sets its own. Each thread adds about 12 MB to a proof's
         * peak; four instead of two save about a second of a 6 s proof and need a 64 MB larger container.
         */
        const val DEFAULT_THREADS: Int = 2

        /** The most MPC threads a prover process runs. */
        const val MAX_THREADS: Int = 16

        /** The extension recycles its prover after this many proofs; a prover process never serves more. */
        const val MAX_PROOFS_PER_CHILD: Int = 5
    }
}

/**
 * The messages between the SDK and a prover process, one JSON object per IPC message.
 *
 * The SDK sends [ToChild.Prove]; the child asks for its upgrade headers ([FromChild.HeadersWanted]) just before it
 * connects to the notary, gets them in [ToChild.Headers], and answers [FromChild.Proved] or [FromChild.Failed].
 * The Steam token and the signed headers cross this channel and nothing else: never argv, the environment or a log.
 */
object ProverIpc {
    val json: Json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun encode(message: ToChild): String = json.encodeToString(ToChild.serializer(), message)

    fun encode(message: FromChild): String = json.encodeToString(FromChild.serializer(), message)

    fun toChild(text: String): ToChild = json.decodeFromString(ToChild.serializer(), text)

    fun fromChild(text: String): FromChild = json.decodeFromString(FromChild.serializer(), text)
}

@Serializable
sealed interface ToChild {
    /** Prove [request] with [steamAccessToken]; [id] tags every message about this proof. */
    @Serializable
    @SerialName("prove")
    data class Prove(
        val id: Int,
        val request: NotaryProofRequest,
        val steamAccessToken: String,
        val steamSessionId: String,
        val settings: ProverSettings,
    ) : ToChild {
        override fun toString(): String = "Prove(id=$id, request=$request, steamAccessToken=<redacted>, steamSessionId=<redacted>)"
    }

    /** The signed upgrade headers for proof [id], or why there are none ([error]). */
    @Serializable
    @SerialName("headers")
    data class Headers(val id: Int, val headers: Map<String, String>? = null, val error: String? = null) : ToChild {
        override fun toString(): String = "Headers(id=$id, headers=${headers?.keys}: <redacted>, error=$error)"
    }
}

@Serializable
sealed interface FromChild {
    val id: Int

    /** The child is about to connect to the notary for proof [id]: sign its upgrade now. */
    @Serializable
    @SerialName("headers_wanted")
    data class HeadersWanted(override val id: Int) : FromChild

    @Serializable
    @SerialName("proved")
    data class Proved(override val id: Int, val presentation: String) : FromChild

    @Serializable
    @SerialName("failed")
    data class Failed(override val id: Int, val failure: ProofFailure) : FromChild
}
