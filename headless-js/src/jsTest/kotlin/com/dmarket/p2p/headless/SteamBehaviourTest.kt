package com.dmarket.p2p.headless

import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * What the tracker does with Steam, driven through the facade. A host's own tests cannot get here: the library
 * talks only to Steam's real hosts, so these run on a mock engine, with every cycle driven by hand. Asserted are
 * the Steam requests (method and path), the state and the events — what the host builds its health from.
 */
class SteamBehaviourTest {
    private val host = TestHost()
    private val backend = host.backend

    private fun events(): List<dynamic> = host.events.map { js("JSON.parse")(it) }

    private fun eventsNamed(name: String): List<dynamic> = events().filter { it.event == name }

    private suspend fun ready(handle: Any, token: String = steamToken()) {
        pushTradingApiKey(handle, PUBLIC_KEY, SECRET_SEED).await()
        pushSteamToken(handle, token).await()
        cycle(handle).await()
    }

    private val createLease = """
        {"activeTracking":[{"dealId":"deal-1","watch":["GetTradeOffer"],"role":"seller"}],
         "directives":[{"directiveId":"dir-1","action":"create_offer","dealId":"deal-1","partnerSteamId":"$OTHER_STEAM_ID",
           "assetIds":["asset-7"],"tradeToken":"tok","contextId":2}],
         "ttlSeconds":100,"linkedSteamId":"$STEAM_ID"
    """.trimIndent()

    @Test
    fun steam_writes_off_sends_no_offer_write_and_drops_the_lease_by_deal() = runTest {
        backend.heartbeatBody = "$createLease}"
        ready(host.tracker(steamWrites = false))

        assertEquals(emptyList(), backend.steamRequests())
        assertTrue(backend.requested.any { it == "POST /exchange/v1/p2p/ext/heartbeat" }, "${backend.requested}")
        val dropped = eventsNamed("DirectiveDropped").single()
        assertEquals(listOf("create_offer", "deal-1"), listOf(dropped.kind, dropped.dealId))
        assertEquals(reasons().offerWritesDisabled, dropped.reason)
    }

    @Test
    fun with_steam_writes_on_by_default_the_same_lease_is_created() = runTest {
        backend.heartbeatBody = "$createLease}"
        ready(host.tracker())
        assertEquals(listOf("POST /tradeoffer/new/send"), backend.steamRequests())
    }

    @Test
    fun a_cancel_leased_after_the_token_lapsed_waits_by_deal_and_no_secret_reaches_an_event() = runTest {
        val handle = host.tracker()
        val lapsing = steamToken(exp = T0 + 1.hours)
        ready(handle, lapsing)
        backend.heartbeatBody = """
            {"activeTracking":[{"dealId":"deal-1","steamOfferId":"555","watch":["GetTradeOffer"],"role":"seller"}],
             "directives":[{"directiveId":"dir-2","action":"cancel_offer","dealId":"deal-1","steamOfferId":"555"}],
             "ttlSeconds":100,"linkedSteamId":"$STEAM_ID"}
        """.trimIndent()
        host.advance(2.hours)
        cycle(handle).await()

        assertEquals(emptyList(), backend.steamRequests())
        val dropped = eventsNamed("DirectiveDropped").single()
        assertEquals(listOf("cancel_offer", "deal-1", reasons().steamSessionMissing), listOf(dropped.kind, dropped.dealId, dropped.reason))
        val state = trackerState(handle)
        assertTrue((state.steamTokenExpiresAtMs as Double) < host.now.toEpochMilliseconds(), "the held token has lapsed")
        assertEquals(listOf("deal-1"), (state.trackedDealIds as Array<String>).toList(), "the heartbeat went on without a token")
        for (text in host.events + (js("JSON.stringify")(state) as String)) {
            assertTrue(lapsing.jwtPayload() !in text, "the Steam token leaked: $text")
            assertTrue(SECRET_SEED !in text, "the secret key leaked: $text")
        }
    }

    @Test
    fun a_waiting_cancel_outlasts_its_deal_and_a_fresh_token_and_runs_once_issued_again() = runTest {
        val handle = host.tracker()
        ready(handle, steamToken(exp = T0 + 1.hours))
        val cancel = """{"directiveId":"dir-2","action":"cancel_offer","dealId":"deal-1","steamOfferId":"555"}"""
        val withCancel = """{"activeTracking":[],"directives":[$cancel],"ttlSeconds":100,"linkedSteamId":"$STEAM_ID"}"""
        val without = """{"activeTracking":[],"directives":[],"ttlSeconds":100,"linkedSteamId":"$STEAM_ID"}"""
        backend.heartbeatBody = withCancel
        host.advance(2.hours)
        cycle(handle).await()
        assertEquals(reasons().steamSessionMissing, eventsNamed("DirectiveDropped").single().reason)

        backend.heartbeatBody = without
        host.advance(2.minutes)
        cycle(handle).await()
        assertEquals("held", pushSteamToken(handle, steamToken(exp = T0 + 24.hours)).await().type)
        host.advance(2.minutes)
        cycle(handle).await()
        assertEquals(emptyList(), backend.steamRequests(), "the deal left the heartbeat and a token came, but no cancel was leased")
        assertTrue(eventsNamed("DirectiveExecuted").isEmpty())

        backend.heartbeatBody = withCancel
        host.advance(2.minutes)
        cycle(handle).await()
        assertEquals(listOf("POST /tradeoffer/555/cancel"), backend.steamRequests())
        val executed = eventsNamed("DirectiveExecuted").single()
        assertEquals(listOf("cancel_offer", "deal-1"), listOf(executed.kind, executed.dealId))
    }

    @Test
    fun below_the_minimum_version_the_create_still_goes_out() = runTest {
        backend.heartbeatBody = """$createLease,"updateRequired":true}"""
        val handle = host.tracker()
        ready(handle)

        assertEquals(true, trackerState(handle).updateRequired)
        assertEquals(listOf("POST /tradeoffer/new/send"), backend.steamRequests(), "the create still goes out")
        assertEquals("create_offer", eventsNamed("DirectiveExecuted").single().kind)
    }

    @Test
    fun the_loop_reads_the_offers_of_the_tracked_deals_and_the_state_carries_them() = runTest {
        backend.heartbeatBody = """
            {"activeTracking":[{"dealId":"deal-1","steamOfferId":"555","watch":["GetTradeOffer"],"deadlineTime":"2026-06-16T14:00:00Z"}],
             "ttlSeconds":100,"linkedSteamId":"$STEAM_ID"}
        """.trimIndent()
        val handle = host.tracker()
        ready(handle, steamToken(exp = T0 + 3.hours))

        assertTrue(backend.requested.any { it.startsWith("GET ") && "/IEconService/GetTradeOffer" in it }, "${backend.requested}")
        assertEquals(emptyList(), backend.steamRequests(), "reads only")
        val state = trackerState(handle)
        assertEquals(listOf("deal-1"), (state.trackedDealIds as Array<String>).toList())
        assertEquals((T0 + 2.hours).toEpochMilliseconds().toDouble(), state.dealDeadlines["deal-1"])
        assertEquals((T0 + 3.hours).toEpochMilliseconds().toDouble(), state.steamTokenExpiresAtMs)
        assertTrue(eventsNamed("HeartbeatSent").isNotEmpty())
    }
}
