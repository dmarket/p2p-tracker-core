package com.dmarket.p2p.headless

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
        assertEquals("mpc", ProverOptions.parse(js("({})")).mode)
        assertEquals("proxy", ProverOptions.parse(js("({mode: 'proxy'})")).mode)
        assertFailsWith<IllegalArgumentException> { ProverOptions.parse(js("({mode: 'auto'})")) }
        assertFailsWith<IllegalArgumentException> { ProverOptions.parse(js("({mode: 1})")) }
    }
}
