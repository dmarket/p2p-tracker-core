package com.dmarket.p2p.sdk.prover

import com.dmarket.p2p.sdk.SdkLog

/** A prover process's output line, already redacted, into the SDK's log: a warning from stderr, info from stdout. */
fun SdkLog.proverLine(pid: String, stream: String, line: String) {
    val fields = arrayOf("pid" to pid, "stream" to stream, "line" to line)
    if (stream == "stderr") warn("prover output", *fields) else info("prover output", *fields)
}

/** One of the prover host's own lines into the SDK's log. */
fun SdkLog.proverNotice(message: String, warning: Boolean, fields: List<Pair<String, String?>>) {
    if (warning) warn(message, *fields.toTypedArray()) else info(message, *fields.toTypedArray())
}
