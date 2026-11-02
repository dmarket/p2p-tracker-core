package com.dmarket.p2p.tracker.runtime

import com.dmarket.p2p.tracker.config.TrackerConfig
import com.dmarket.p2p.tracker.loop.LoopConfig
import com.dmarket.p2p.tracker.loop.PersistedDealWriteClaimStore
import com.dmarket.p2p.tracker.loop.PersistedNotaryProofThrottleStore
import com.dmarket.p2p.tracker.loop.PersistedOfferGuardStore
import com.dmarket.p2p.tracker.loop.PersistedSteamWriteThrottleStore
import com.dmarket.p2p.tracker.loop.PersistedUnprovenClaimStore
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.RuntimeSurface
import com.dmarket.p2p.tracker.model.TrackerMode
import com.dmarket.p2p.tracker.model.marketplace.HeartbeatResponse
import com.dmarket.p2p.tracker.model.marketplace.TrackedDeal
import com.dmarket.p2p.tracker.support.FakeClock
import com.dmarket.p2p.tracker.support.FakeCredentialVault
import com.dmarket.p2p.tracker.support.FakeDeviceIdStore
import com.dmarket.p2p.tracker.support.FakeMarketplaceClient
import com.dmarket.p2p.tracker.support.FakeScheduler
import com.dmarket.p2p.tracker.support.FakeSteamReadClient
import com.dmarket.p2p.tracker.support.FakeSteamSessionScraper
import com.dmarket.p2p.tracker.support.fakeSteamCredential
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TradeTrackerCoreTest {
    @Test
    fun v1_enables_a_single_game() {
        assertEquals(1, TradeTrackerCore().enabledGameCount())
    }

    @Test
    fun exposes_a_version() {
        // A shape check, not a pinned literal, so version bumps don't break this test.
        // The exact value tracks VERSION_NAME in gradle.properties.
        val version = TradeTrackerCore.VERSION
        assertTrue(
            Regex("""^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$""").matches(version),
            "VERSION should be SemVer-shaped, was: $version",
        )
    }

    @Test
    fun create_loop_hands_the_received_offers_switch_to_the_loop() = runTest {
        val reader = FakeSteamReadClient()
        val config = TrackerConfig.defaults()
        TradeTrackerCore().createLoop(
            config = LoopConfig("1.0.0", RuntimeSurface.WebChrome, TrackerMode.Background, config),
            marketplace = FakeMarketplaceClient(
                HeartbeatResponse(activeTracking = listOf(TrackedDeal(DealId("deal-1"), OfferId("offer-1"))), ttlSeconds = 60),
            ),
            steamReader = reader,
            scraper = FakeSteamSessionScraper(),
            scheduler = FakeScheduler(),
            deviceId = FakeDeviceIdStore(),
            vault = FakeCredentialVault(fakeSteamCredential()),
            clock = FakeClock(),
            claims = PersistedDealWriteClaimStore(),
            unprovenClaims = PersistedUnprovenClaimStore(),
            offerGuard = PersistedOfferGuardStore(),
            throttle = PersistedSteamWriteThrottleStore(config.steamWrites),
            notaryThrottle = PersistedNotaryProofThrottleStore(config.notary.breaker),
            scanReceivedOffers = true,
        ).runOnce()

        assertEquals(1 to 0, reader.offersCalls to reader.sentOffersCalls)
    }
}
