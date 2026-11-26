package com.dmarket.p2p.tracker.runtime

import com.dmarket.p2p.tracker.net.SteamProxy
import com.dmarket.p2p.tracker.support.FakeClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** What one tracker's Steam route says about the proxy in use, from what its connections found. */
class SteamRouteTest {
    private val clock = FakeClock()
    private val first = SteamProxy.parse("http://trader:s3cret-pass@203.0.113.7:3128")
    private val second = SteamProxy.parse("http://trader:other-pass@203.0.113.8:3128")
    private var held: SteamProxy? = first
    private val route = SteamRoute(clock) { held }

    @Test
    fun an_outage_keeps_its_start_and_takes_the_latest_detail_until_a_connection_gets_through() {
        val start = clock.now()
        route.unreachable(first, "could not connect to the proxy: ECONNREFUSED")
        clock.advance(30.seconds)
        route.unreachable(first, "the proxy answered CONNECT with HTTP 502")

        assertTrue(route.isUnreachable)
        assertEquals(start, route.unreachableSince, "a second failure does not restart the outage")
        assertEquals("the proxy answered CONNECT with HTTP 502", route.detail)

        route.tunnelled(first)
        assertFalse(route.isUnreachable)
        assertNull(route.detail)
        assertNull(route.unreachableSince)

        clock.advance(5.seconds)
        route.unreachable(first, "no answer from the proxy within 10000 ms")
        assertEquals(clock.now(), route.unreachableSince, "an outage after a recovery is a new one")
    }

    @Test
    fun a_report_about_a_proxy_no_longer_held_changes_nothing() {
        route.unreachable(first, "could not connect to the proxy: ECONNREFUSED")
        held = second

        assertFalse(route.isUnreachable, "another proxy ends the old one's outage")
        route.unreachable(first, "could not connect to the proxy: ECONNREFUSED")
        assertFalse(route.isUnreachable, "a late failure of the old proxy is not the new one's")

        route.unreachable(second, "could not connect to the proxy: ECONNREFUSED")
        route.tunnelled(first)
        assertTrue(route.isUnreachable, "a late success of the old proxy does not end the new one's outage")
    }

    @Test
    fun without_a_proxy_nothing_is_unreachable() {
        route.unreachable(first, "could not connect to the proxy: ECONNREFUSED")
        held = null

        assertFalse(route.isUnreachable)
        assertNull(route.detail)
        assertNull(route.unreachableSince)
        assertFalse("203.0.113.7" in route.toString() || "s3cret" in route.toString(), route.toString())
    }

    @Test
    fun a_required_proxy_is_missing_while_none_is_held_and_the_requirement_is_read_at_each_ask() {
        held = null
        var pinned = false
        val required = SteamRoute(clock, required = { pinned }) { held }
        assertFalse(required.proxyRequired)
        assertFalse(required.isProxyMissing, "not required yet: the direct route is still allowed")

        pinned = true
        assertTrue(required.proxyRequired, "turned on as the tracker runs, not fixed at construction")
        assertTrue(required.isProxyMissing)
        assertNull(required.proxy, "and there is no direct route to take instead")

        held = first
        assertFalse(required.isProxyMissing, "a proxy is held")
        assertFalse("s3cret" in required.toString() || "203.0.113.7" in required.toString(), required.toString())
    }

    @Test
    fun a_route_that_does_not_require_a_proxy_is_never_missing_one() {
        held = null
        assertFalse(route.proxyRequired)
        assertFalse(route.isProxyMissing)
    }
}
