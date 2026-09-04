package com.dmarket.p2p.tracker.model

/**
 * A short, non-reversible fingerprint of a credential — enough to answer "is this the same token as last
 * time", never enough to reconstruct it.
 *
 * Exists because several guards must remember *which* token they already tried, across a process restart,
 * while [com.dmarket.p2p.tracker.port.host.DeviceKeyValueStore] is contractually **non-secret storage**:
 * persisting the token itself there would breach the audit boundary, and persisting nothing means a
 * respawned worker re-attempts a credential already known to be refused (once per wake, forever).
 *
 * FNV-1a 64-bit, rendered as 16 lowercase hex chars. Deliberately not a cryptographic hash: no crypto
 * primitive is available in `commonMain`, and none is needed — the inputs are high-entropy tokens, only
 * equality is ever compared, and a collision costs at most one extra refresh attempt. It must **not** be
 * used anywhere an adversary chooses the input.
 */
object TokenFingerprint {

    private const val FNV_OFFSET_BASIS = -3750763034362895579L // 0xcbf29ce484222325
    private const val FNV_PRIME = 1099511628211L

    /** The fingerprint of [value], or `null` for a null/blank input (nothing to remember). */
    fun of(value: String?): String? {
        if (value.isNullOrBlank()) return null
        var hash = FNV_OFFSET_BASIS
        for (byte in value.encodeToByteArray()) {
            hash = hash xor (byte.toLong() and 0xFF)
            hash *= FNV_PRIME
        }
        return hash.toHexString16()
    }

    private fun Long.toHexString16(): String {
        val digits = "0123456789abcdef"
        val out = CharArray(16)
        for (i in 0 until 16) {
            val shift = (15 - i) * 4
            out[i] = digits[((this ushr shift) and 0xF).toInt()]
        }
        return out.concatToString()
    }
}
