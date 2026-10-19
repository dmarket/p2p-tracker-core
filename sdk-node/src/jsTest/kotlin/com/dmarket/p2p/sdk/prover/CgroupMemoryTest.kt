package com.dmarket.p2p.sdk.prover

import com.dmarket.p2p.sdk.STEAM_ID
import com.dmarket.p2p.sdk.T0
import com.dmarket.p2p.sdk.TestClock
import com.dmarket.p2p.sdk.health.HealthTracker
import com.dmarket.p2p.sdk.health.ProblemCode
import com.dmarket.p2p.sdk.node.NodeModules
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.runtime.HeadlessTrackerState
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/** The start-up memory check over a fake cgroup filesystem. */
class CgroupMemoryTest {
    private suspend fun cgroup(vararg files: Pair<String, String>): String {
        val root = NodeModules.fs.mkdtemp(NodeModules.path.join(NodeModules.os.tmpdir(), "sdk-cgroup-") as String)
            .unsafeCast<Promise<String>>().await()
        for ((name, text) in files) {
            val path = NodeModules.path.join(root, name) as String
            NodeModules.fs.mkdir(NodeModules.path.dirname(path), js("({ recursive: true })")).unsafeCast<Promise<Any?>>().await()
            NodeModules.fs.writeFile(path, text).unsafeCast<Promise<Any?>>().await()
        }
        return root
    }

    @Test
    fun a_512_mib_container_cannot_hold_a_proof_beside_the_sdk() = runTest {
        val limit = cgroupMemoryLimit(cgroup("memory.max" to "536870912\n"))
        assertEquals(536_870_912L, limit)
        val mib = 1024L * 1024L
        assertTrue(ProverMemory.isLow(limit, usedBytes = 100 * mib, peakBytes = 768 * mib))

        val health = HealthTracker(TestClock(), version = "1.2.3")
        health.proverMemoryLow(limit!!, 100 * mib, 768 * mib)
        val state = HeadlessTrackerState(true, SteamId(STEAM_ID), T0 + 24.hours, SteamId(STEAM_ID), null)
        assertTrue(health.snapshot(state).problems.any { it.code == ProblemCode.PROVER_MEMORY_LOW })
    }

    @Test
    fun cgroup_v1_and_no_limit_are_read_too() = runTest {
        assertEquals(805_306_368L, cgroupMemoryLimit(cgroup("memory/memory.limit_in_bytes" to "805306368\n")))
        assertNull(cgroupMemoryLimit(cgroup("memory.max" to "max\n")))
        assertNull(cgroupMemoryLimit(cgroup()))
    }
}
