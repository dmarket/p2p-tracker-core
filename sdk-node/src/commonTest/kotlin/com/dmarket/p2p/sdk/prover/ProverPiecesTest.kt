package com.dmarket.p2p.sdk.prover

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProverPiecesTest {
    @Test
    fun a_cgroup_limit_is_read_from_either_version() {
        assertEquals(536_870_912L, ProverMemory.limitBytes("536870912\n", null))
        assertNull(ProverMemory.limitBytes("max\n", "1"))
        assertEquals(805_306_368L, ProverMemory.limitBytes(null, "805306368"))
        assertNull(ProverMemory.limitBytes(null, "9223372036854771712"))
        assertNull(ProverMemory.limitBytes(null, null))
    }

    @Test
    fun memory_is_low_when_one_peak_does_not_fit_beside_the_sdk() {
        val mib = 1024L * 1024L
        assertTrue(ProverMemory.isLow(512 * mib, 120 * mib, 768 * mib))
        assertFalse(ProverMemory.isLow(1024 * mib, 120 * mib, 768 * mib))
        assertFalse(ProverMemory.isLow(null, 120 * mib, 768 * mib))
    }

    @Test
    fun the_prover_config_defaults_to_one_proof_per_process_and_bounds_its_knobs() {
        val config = com.dmarket.p2p.sdk.SdkConfig.parse("""{"managementSecret":"${"s".repeat(32)}","dataDir":"/data"}""").prover
        assertTrue(config.enabled)
        assertEquals(1, config.maxConcurrency)
        assertEquals(1, config.proofsPerChild)
        assertEquals("wss://api.dmarket.com/provenance/v1/", config.notaryUrl)
        val parsed = com.dmarket.p2p.sdk.SdkConfig.parse(
            """{"managementSecret":"${"s".repeat(
                32,
            )}","dataDir":"/data","prover":{"notaryUrl":"wss://api-dev2.devss.xyz/provenance/v1/","maxConcurrency":2,"proofsPerChild":5}}""",
        ).prover
        assertEquals(2, parsed.maxConcurrency)
        assertEquals(5, parsed.proofsPerChild)
        kotlin.test.assertFailsWith<IllegalArgumentException> { ProverConfig(maxConcurrency = 3) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { ProverConfig(proofsPerChild = 6) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { ProverConfig(notaryUrl = "https://notary") }
    }
}
