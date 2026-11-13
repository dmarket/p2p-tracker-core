package com.dmarket.p2p.tracker.loop

import com.dmarket.p2p.tracker.client.createHttpClient
import com.dmarket.p2p.tracker.client.marketplace.KtorMarketplaceClient
import com.dmarket.p2p.tracker.config.MarketplaceRetryConfig
import com.dmarket.p2p.tracker.credential.steam.SteamCredentialProvider
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.RuntimeSurface
import com.dmarket.p2p.tracker.model.TrackerMode
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.model.steam.TradeDraft
import com.dmarket.p2p.tracker.port.host.ElapsedClock
import com.dmarket.p2p.tracker.port.steam.CancelOfferResult
import com.dmarket.p2p.tracker.port.steam.CreateOfferResult
import com.dmarket.p2p.tracker.port.steam.SteamOfferCanceller
import com.dmarket.p2p.tracker.port.steam.SteamOfferCreator
import com.dmarket.p2p.tracker.support.FakeClock
import com.dmarket.p2p.tracker.support.FakeCredentialVault
import com.dmarket.p2p.tracker.support.FakeDeviceIdStore
import com.dmarket.p2p.tracker.support.FakeMarketplaceAuthenticator
import com.dmarket.p2p.tracker.support.FakeScheduler
import com.dmarket.p2p.tracker.support.FakeSteamReadClient
import com.dmarket.p2p.tracker.support.FakeSteamSessionScraper
import com.dmarket.p2p.tracker.support.fakeSteamCredential
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The lease fence (DMA-662 Q14): with a lease clock, a Steam write starts only inside its directive's lease, counted
 * from when the request that served it was sent, on a clock that runs through sleep. The default lease is 2 min and the
 * renewal margin 30 s ([LoopConfig]).
 */
class LeaseFenceTest {

    private val partner = "76561198000000002"

    /** Every heartbeat, `create-trade` call and Steam write, in order. */
    private val log = mutableListOf<String>()

    /** The lease clock: [sleep] is the device asleep, which it counts like any other time. */
    private val elapsed = object : ElapsedClock {
        var now = 10.hours
        override fun elapsed(): Duration = now
        fun sleep(by: Duration) {
            now += by
        }
    }

    private val wall = FakeClock()

    /** What each heartbeat serves, by heartbeat (1-based); the last entry repeats. */
    private var served = listOf("")

    /** How long each heartbeat takes to answer, by heartbeat; the last entry repeats. */
    private var heartbeatTakes = listOf(Duration.ZERO)

    /** Moves the wall clock while a heartbeat is in flight. */
    private var wallShift = Duration.ZERO

    private var createTradeTakes = Duration.ZERO

    /** How long the device sleeps inside each create, as a create does that waits on the network. */
    private var createSleeps = Duration.ZERO

    private var heartbeats = 0

    private val creator = object : SteamOfferCreator {
        override suspend fun createOffer(credential: SteamCredential, draft: TradeDraft): CreateOfferResult {
            val asset = draft.assetsToGive.single().value
            log += "create $asset"
            elapsed.sleep(createSleeps)
            return CreateOfferResult.NeedsConfirmation(OfferId("offer-$asset"))
        }
    }

    private val canceller = object : SteamOfferCanceller {
        override suspend fun cancelOffer(credential: SteamCredential, offerId: OfferId): CancelOfferResult {
            log += "cancel ${offerId.value}"
            return CancelOfferResult.Cancelled
        }
    }

    private val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private val engine = MockEngine { request ->
        when (request.url.encodedPath.substringAfterLast('/')) {
            "heartbeat" -> {
                heartbeats++
                log += "heartbeat"
                elapsed.sleep(heartbeatTakes[minOf(heartbeats, heartbeatTakes.size) - 1])
                wall.advance(wallShift)
                val directives = served[minOf(heartbeats, served.size) - 1]
                respond("""{"activeTracking":[],"directives":[$directives],"ttlSeconds":60}""", headers = json)
            }
            "create-trade" -> {
                log += "create-trade"
                elapsed.sleep(createTradeTakes)
                respond(
                    """{"state":"COMMITTED","createOffer":{"directiveId":"fe","partnerSteamId":"$partner",
                       "assetIds":["asset-fe"],"tradeToken":"tok","contextId":2}}""",
                    headers = json,
                )
            }
            "trade-actions" -> respond("""{"results":[]}""", headers = json)
            else -> respond("{}", headers = json)
        }
    }

    private fun create(tag: String, extra: String = "") = """{"directiveId":"$tag","action":"create_offer","dealId":"deal-$tag",
           "partnerSteamId":"$partner","assetIds":["asset-$tag"],"tradeToken":"tok","contextId":2$extra}"""

    private fun cancel(tag: String) = """{"directiveId":"$tag","action":"cancel_offer","dealId":"deal-$tag","steamOfferId":"offer-$tag"}"""

    private fun loop(fence: Boolean = true) = TradeTrackerLoop(
        config = LoopConfig("1.0.0", RuntimeSurface.AndroidNative, TrackerMode.Background),
        marketplace = KtorMarketplaceClient(
            createHttpClient(engine),
            "https://gateway.dmarket.com",
            FakeMarketplaceAuthenticator(token = "t"),
            MarketplaceRetryConfig(retryBaseDelayMs = 1, retryMaxDelayMs = 1),
        ),
        steamReader = FakeSteamReadClient(),
        credentials = SteamCredentialProvider(
            vault = FakeCredentialVault(steamCredential = fakeSteamCredential()),
            scraper = FakeSteamSessionScraper(result = fakeSteamCredential()),
            clock = wall,
        ),
        scheduler = FakeScheduler(),
        clock = wall,
        deviceId = FakeDeviceIdStore(),
        offerCreator = creator,
        offerCanceller = canceller,
        directivesEnabled = true,
        leaseClock = elapsed.takeIf { fence },
    )

    @Test
    fun a_write_inside_the_window_goes_without_another_heartbeat() = runTest {
        served = listOf(create("a"))
        heartbeatTakes = listOf(80.seconds) // 40 s left: inside the window, above the margin

        loop().runOnce()

        assertEquals(listOf("heartbeat", "create asset-a"), log)
    }

    @Test
    fun past_the_window_the_write_waits_for_a_heartbeat_that_serves_the_directive_again() = runTest {
        served = listOf(create("a"))
        heartbeatTakes = listOf(3.minutes, Duration.ZERO)

        loop().runOnce()

        assertEquals(listOf("heartbeat", "heartbeat", "create asset-a"), log)
    }

    @Test
    fun past_the_window_a_heartbeat_that_does_not_serve_the_directive_again_stops_the_write() = runTest {
        served = listOf(create("a"), "")
        heartbeatTakes = listOf(3.minutes, Duration.ZERO)

        loop().runOnce()

        assertEquals(listOf("heartbeat", "heartbeat"), log)
    }

    @Test
    fun under_30_seconds_left_a_heartbeat_goes_before_the_write() = runTest {
        served = listOf(create("a"))
        heartbeatTakes = listOf(100.seconds, Duration.ZERO) // 20 s left

        loop().runOnce()

        assertEquals(listOf("heartbeat", "heartbeat", "create asset-a"), log)
    }

    @Test
    fun a_late_heartbeat_answer_does_not_extend_the_window() = runTest {
        served = listOf(create("a"))
        heartbeatTakes = listOf(3.minutes) // every answer comes after the lease it served has run out

        loop().runOnce()

        assertEquals(listOf("heartbeat", "heartbeat"), log)
    }

    @Test
    fun a_moved_wall_clock_changes_nothing() = runTest {
        served = listOf(create("a"), create("b"))
        wallShift = 1.hours
        val loop = loop()
        loop.runOnce()
        wallShift = (-3).hours
        loop.forceHeartbeatNow()
        loop.runOnce()

        assertEquals(listOf("heartbeat", "create asset-a", "heartbeat", "create asset-b"), log)
    }

    @Test
    fun the_device_asleep_inside_the_window_counts() = runTest {
        // Two creates for one partner run one after the other; the device sleeps 100 s inside the first, so the
        // second has 20 s left and renews first. The wall clock does not move at all.
        served = listOf(create("a") + "," + create("b"))
        createSleeps = 100.seconds

        loop().runOnce()

        assertEquals(listOf("heartbeat", "create asset-a", "heartbeat", "create asset-b"), log)
    }

    @Test
    fun the_backend_lease_duration_replaces_the_default() = runTest {
        served = listOf(create("a", extra = ""","leaseTtlSeconds":600"""))
        heartbeatTakes = listOf(3.minutes)

        loop().runOnce()

        assertEquals(listOf("heartbeat", "create asset-a"), log)
    }

    @Test
    fun a_cancel_is_fenced_like_a_create() = runTest {
        served = listOf(cancel("c"), "")
        heartbeatTakes = listOf(3.minutes, Duration.ZERO)

        loop().runOnce()

        assertEquals(listOf("heartbeat", "heartbeat"), log)
    }

    @Test
    fun a_create_from_create_trade_counts_its_lease_from_that_call() = runTest {
        val loop = loop()
        loop.runOnce() // a heartbeat with nothing leased, so the call needs none of its own first
        log.clear()
        createTradeTakes = 3.minutes

        // Real time: MockEngine answers off the test dispatcher, and a virtual timeout would fire before it does.
        val result = withContext(Dispatchers.Default) { loop.awaitDirectiveOutcome(DealId("deal-fe"), 30.seconds, this) }

        // The heartbeat cannot serve a create `create-trade` parked under the caller, so the lease is not renewed.
        assertEquals(CreateOfferResult.Failed(LEASE_LAPSED_DROP_REASON), result)
        assertEquals(listOf("create-trade", "heartbeat"), log)
    }

    @Test
    fun a_create_from_create_trade_inside_its_window_goes() = runTest {
        val loop = loop()
        loop.runOnce()
        log.clear()

        // Real time: MockEngine answers off the test dispatcher, and a virtual timeout would fire before it does.
        val result = withContext(Dispatchers.Default) { loop.awaitDirectiveOutcome(DealId("deal-fe"), 30.seconds, this) }

        assertEquals(CreateOfferResult.NeedsConfirmation(OfferId("offer-asset-fe")), result)
        assertEquals("create asset-fe", log[1])
    }

    @Test
    fun without_a_lease_clock_the_fence_is_off() = runTest {
        served = listOf(create("a"))
        heartbeatTakes = listOf(3.minutes)

        loop(fence = false).runOnce()

        assertEquals(listOf("heartbeat", "create asset-a"), log)
    }
}
