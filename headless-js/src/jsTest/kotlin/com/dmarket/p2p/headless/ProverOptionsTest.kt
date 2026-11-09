package com.dmarket.p2p.headless

import com.dmarket.p2p.tracker.notary.IssuanceMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProverOptionsTest {
    @Test
    fun mode_is_an_explicit_validated_option() {
        assertEquals("direct", ProverOptions.parse(js("({})")).transport)
        assertEquals("egress", ProverOptions.parse(js("({mode: 'proxy', transport: 'egress'})")).transport)
        assertFailsWith<IllegalArgumentException> { ProverOptions.parse(js("({transport: 'egress'})")) }
        assertFailsWith<IllegalArgumentException> { ProverOptions.parse(js("({mode: 'proxy', transport: 'auto'})")) }
        assertFailsWith<IllegalArgumentException> { ProverOptions.parse(js("({mode: 'proxy', transport: 1})")) }
        assertEquals(IssuanceMode.MPC, ProverOptions.parse(js("({})")).mode)
        assertEquals(IssuanceMode.PROXY, ProverOptions.parse(js("({mode: 'proxy'})")).mode)
        assertFailsWith<IllegalArgumentException> { ProverOptions.parse(js("({mode: 'auto'})")) }
        assertFailsWith<IllegalArgumentException> { ProverOptions.parse(js("({mode: 1})")) }
    }
}
