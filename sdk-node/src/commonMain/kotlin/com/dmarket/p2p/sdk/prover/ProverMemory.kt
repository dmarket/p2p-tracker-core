package com.dmarket.p2p.sdk.prover

/**
 * Whether one proof's peak fits beside the SDK inside its container's memory limit.
 *
 * Under plain Docker an out-of-memory kill takes the prover process and the SDK lives; under Kubernetes on cgroup
 * v2 the whole container goes. Only headroom protects the SDK there, so the SDK reports `PROVER_MEMORY_LOW` at
 * start when the limit leaves less than a proof's peak.
 */
object ProverMemory {
    /** A cgroup v1 limit at or above this is "no limit" (the kernel writes a page-rounded `LONG_MAX`). */
    private const val V1_UNLIMITED: Long = 1L shl 62

    /**
     * The memory limit in bytes, from the text of cgroup v2's `memory.max` or, without it, v1's
     * `memory.limit_in_bytes`. `null` when neither names a limit.
     */
    fun limitBytes(v2MemoryMax: String?, v1LimitInBytes: String?): Long? {
        v2MemoryMax?.trim()?.let { text -> return if (text == "max") null else text.toLongOrNull() }
        return v1LimitInBytes?.trim()?.toLongOrNull()?.takeIf { it < V1_UNLIMITED }
    }

    /** `true` when [limitBytes] leaves less than [peakBytes] beside [usedBytes]. */
    fun isLow(limitBytes: Long?, usedBytes: Long, peakBytes: Long): Boolean = limitBytes != null && limitBytes - usedBytes < peakBytes
}
