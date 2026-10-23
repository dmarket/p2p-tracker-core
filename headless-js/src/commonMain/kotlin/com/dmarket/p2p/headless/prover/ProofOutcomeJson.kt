package com.dmarket.p2p.headless.prover

import com.dmarket.p2p.tracker.notary.ProofFailure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * What a host is told about a proof and the prover processes: a failure as JSON and the host's own lines, every
 * text in them already redacted ([ProverOutput]). A host passes them on as they are and redacts nothing itself.
 */
object ProofOutcomeJson {
    /** Every field written, an absent text as `null`: a host reads the same keys for every failure of a type. */
    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
    }

    /**
     * [failure] as a JSON object: `type` (its serial name), its fields with every text redacted, and its redacted
     * `summary`, which serialization alone would leave out.
     */
    fun failure(failure: ProofFailure): String {
        val fields = json.encodeToJsonElement(ProofFailure.serializer(), failure.redacted()).jsonObject
        return JsonObject(fields + ("summary" to JsonPrimitive(ProverOutput.redact(failure.summary)))).toString()
    }

    /**
     * One of the host's own lines (a proof started, made or failed, a prover exited) as a JSON object: `msg`,
     * `level` (`info` or `warn`), then [fields] in order, an absent one as `null`. The fields carry no secret.
     */
    fun notice(message: String, warning: Boolean, fields: List<Pair<String, String?>>): String {
        val head = listOf("msg" to JsonPrimitive(message), "level" to JsonPrimitive(if (warning) "warn" else "info"))
        val rest = fields.map { (key, value) -> key to (value?.let(::JsonPrimitive) ?: JsonNull) }
        return JsonObject((head + rest).toMap()).toString()
    }

    private fun ProofFailure.redacted(): ProofFailure = when (this) {
        is ProofFailure.UpgradeRefused -> this
        is ProofFailure.SessionClosed -> copy(reason = reason?.let(ProverOutput::redact))
        is ProofFailure.NotaryUnreachable -> copy(detail = ProverOutput.redact(detail))
        is ProofFailure.VersionMismatch -> copy(detail = ProverOutput.redact(detail))
        is ProofFailure.SteamFailed -> copy(detail = ProverOutput.redact(detail))
        is ProofFailure.LimitsExceeded -> copy(detail = ProverOutput.redact(detail))
        is ProofFailure.ProverKilled -> copy(detail = detail?.let(ProverOutput::redact))
        is ProofFailure.NotStarted -> copy(reason = ProverOutput.redact(reason))
        is ProofFailure.Other -> copy(detail = ProverOutput.redact(detail))
    }
}
