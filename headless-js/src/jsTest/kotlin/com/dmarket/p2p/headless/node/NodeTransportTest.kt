package com.dmarket.p2p.headless.node

import com.dmarket.p2p.tracker.client.createSteamHttpClient
import com.dmarket.p2p.tracker.client.steam.HeaderSessionSteamOfferCreator
import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.model.steam.TradeDraft
import com.dmarket.p2p.tracker.port.steam.CreateOfferResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** The platform's own HTTP engine on Node, against a real socket: what a mock engine cannot show. */
class NodeTransportTest {
    @Test
    fun a_create_on_node_sends_the_cookie_referer_and_origin_it_sets() = runTest {
        val server = LoopbackServer.start { 200 to """{"tradeofferid":"555","needs_mobile_confirmation":true}""" }
        try {
            val base = server.base
            val creator = HeaderSessionSteamOfferCreator(createSteamHttpClient(requestTimeoutMs = 5_000), "sid", base)
            val credential = SteamCredential("eyJ.node.token", SteamId("76561198000000001"), Instant.fromEpochSeconds(1_900_000_000))
            val result = creator.createOffer(credential, TradeDraft(SteamId("76561198000000002"), listOf(AssetId("a1")), "tok"))

            assertEquals(CreateOfferResult.NeedsConfirmation(OfferId("555")), result)
            val request = server.seen.single()
            assertEquals("POST /tradeoffer/new/send", "${request.method} ${request.url}")
            assertEquals("steamLoginSecure=76561198000000001%7C%7CeyJ.node.token; sessionid=sid", request.headers.cookie as String?)
            assertEquals("$base/tradeoffer/new/?partner=39734274&token=tok", request.headers.referer as String?)
            assertEquals(base, request.headers.origin as String?)
        } finally {
            server.close()
        }
    }
}
