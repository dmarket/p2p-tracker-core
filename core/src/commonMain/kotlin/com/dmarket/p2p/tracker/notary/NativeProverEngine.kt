package com.dmarket.p2p.tracker.notary

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.io.encoding.Base64
import kotlin.time.Duration

/**
 * The native prover as [NativeNotaryProver] sees it: the steam-provenance C ABI behind a platform's bridge and its
 * connections (Android: `NativeProverBridge` over JNI, the notary over OkHttp's WebSocket, the target over TCP).
 * The platform part only connects, issues, presents and reports what it observed; every decision is in common code.
 */
interface NativeProverEngine {
    /** Why the native prover cannot run here (no library for this device's ABI), or `null` when it can. */
    fun unavailableReason(): String?

    /**
     * Runs [issuance] and builds its presentation. Answers every outcome, failures included, with what it observed;
     * throws only [kotlinx.coroutines.CancellationException]. Cancelling the call cancels the issuance through the
     * bridge and frees its handles before the cancellation propagates.
     */
    suspend fun issue(issuance: NativeIssuance, listener: NativeIssuanceListener): NativeIssuanceOutcome
}

/** Progress of one issuance, from any thread. */
interface NativeIssuanceListener {
    /** The prover's stage: [index] 0–6 (stable), its [stage] name and an advisory [fraction]. */
    fun onStage(index: Int, stage: String, fraction: Float)

    /** [sent] bytes went to a connection, [received] bytes came from one (deltas, notary and target together). */
    fun onBytes(sent: Long, received: Long)
}

/**
 * One native issuance. [config] and [policy] are the C ABI's JSON ([nativeConfigJson], [nativePolicyJson]); [config]
 * carries the Steam token in its request target and [notaryToken] is the DMarket session, so [toString] prints
 * neither.
 *
 * @property targetHost the host the MPC target connection dials (port [targetPort]); unused in proxy mode, where the
 *   notary dials it.
 */
class NativeIssuance(
    val mode: IssuanceMode,
    val notaryUrl: String,
    val notaryToken: String,
    val subprotocol: String,
    val targetHost: String,
    val config: String,
    val body: ByteArray?,
    val policy: String,
    val deadline: Duration,
    val targetPort: Int = 443,
) {
    override fun toString(): String =
        "NativeIssuance(mode=$mode, notaryUrl=$notaryUrl, notaryToken=<redacted>, target=$targetHost:$targetPort, " +
            "config=<redacted>, body=${body?.let { "<${it.size} bytes>" }}, policy=$policy, deadline=$deadline)"
}

/**
 * What one issuance ended with: the [presentation] (postcard bytes) or, on a failure, the C ABI [status] of the call
 * that failed and its [diagnostic], plus what the connections saw, for [ProofFailures.classify].
 *
 * @property upgradeStatus the HTTP status the notary answered the upgrade with, when it was not 101.
 * @property notaryConnected whether the notary's WebSocket opened.
 * @property closeCode the notary's close code after the 101, with [closeReason].
 * @property targetStatus the HTTP status of the proven Steam response.
 * @property targetError why the dial to Steam failed.
 */
class NativeIssuanceOutcome(
    val presentation: ByteArray? = null,
    val status: Int? = null,
    val diagnostic: String? = null,
    val upgradeStatus: Int? = null,
    val notaryConnected: Boolean = true,
    val closeCode: Int? = null,
    val closeReason: String? = null,
    val targetStatus: Int? = null,
    val targetError: String? = null,
) {
    companion object {
        /** `PC_TIMEOUT`: the issuance's own deadline passed. */
        const val STATUS_TIMEOUT: Int = 7

        /** `PC_CANCELLED`. */
        const val STATUS_CANCELLED: Int = 8
    }
}

/**
 * The C ABI's issuance config (`client-cffi/src/config.rs`: `abi_version` 1, unknown fields refused): the target's
 * name, the request without its body, the roots when a test supplies them, and exactly one mode's limits.
 */
internal fun nativeConfigJson(mode: IssuanceMode, request: ProveRequest): String {
    val http = request.proverHttpRequest()
    return buildJsonObject {
        put("abi_version", 1)
        put("server_name", request.spec.serverName)
        putJsonObject("request") {
            put("method", http.method)
            put("uri", http.uri)
            putJsonArray("headers") {
                http.headers.forEach { (name, value) -> add(JsonArray(listOf(JsonPrimitive(name), JsonPrimitive(value)))) }
            }
        }
        request.rootStorePem?.let { pem -> putJsonArray("roots_der_hex") { pemToDerHex(pem).forEach { add(JsonPrimitive(it)) } } }
        when (mode) {
            IssuanceMode.MPC -> putJsonObject("mpc") {
                val mpc = request.mpcIssuance()
                put("max_sent_data", mpc.maxSentData)
                put("max_recv_data", mpc.maxRecvData)
                put("max_recv_data_online", mpc.maxRecvDataOnline)
                mpc.maxRecvRecordsOnline?.let { put("max_recv_records_online", it) }
            }
            IssuanceMode.PROXY -> putJsonObject("proxy") {
                val proxy = request.proxyIssuance()
                put("max_sent_data", proxy.maxSentData)
                put("max_recv_data", proxy.maxRecvData)
            }
        }
    }.toString()
}

/**
 * The C ABI's presentation policy, every field set. Never omitted: the ABI's defaults reveal the whole request
 * target, and on both trade axes that target carries the Steam token.
 */
internal fun nativePolicyJson(policy: ProverRevealPolicy): String = buildJsonObject {
    put("redact_request_header_values", buildJsonArray { policy.redactRequestHeaderValues.forEach { add(JsonPrimitive(it)) } })
    put("reveal_request_target", policy.revealRequestTarget)
    put("reveal_response_headers", policy.revealResponseHeaders)
    when (val body = policy.responseBody) {
        ResponseBodyReveal.All -> put("body", "all")
        is ResponseBodyReveal.JsonPaths -> {
            put("body", "json_paths")
            put("json_paths", buildJsonArray { body.paths.forEach { add(JsonPrimitive(it)) } })
        }
    }
}.toString()

/** Each `CERTIFICATE` block of [pem] as DER hex, the form the C ABI takes custom roots in. */
internal fun pemToDerHex(pem: String): List<String> =
    Regex("""-----BEGIN CERTIFICATE-----([\s\S]*?)-----END CERTIFICATE-----""").findAll(pem)
        .map { block ->
            Base64.Default.decode(block.groupValues[1].filterNot { it.isWhitespace() })
                .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        }
        .toList()
        .also { require(it.isNotEmpty()) { "rootStorePem holds no CERTIFICATE block" } }
