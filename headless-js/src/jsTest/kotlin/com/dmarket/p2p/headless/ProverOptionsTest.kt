package com.dmarket.p2p.headless

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProverOptionsTest {
    @Test
    fun mode_is_an_explicit_validated_option() {
        assertEquals("mpc", ProverOptions.parse(js("({})")).mode)
        assertEquals("proxy", ProverOptions.parse(js("({mode: 'proxy'})")).mode)
        assertFailsWith<IllegalArgumentException> { ProverOptions.parse(js("({mode: 'auto'})")) }
        assertFailsWith<IllegalArgumentException> { ProverOptions.parse(js("({mode: 1})")) }
    }
}
