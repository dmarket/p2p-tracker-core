package com.dmarket.p2p.tracker.loop

import com.dmarket.p2p.tracker.client.createHttpClient
import com.dmarket.p2p.tracker.client.marketplace.CredentialMarketplaceAuthenticator
import com.dmarket.p2p.tracker.client.marketplace.KtorMarketplaceClient
import com.dmarket.p2p.tracker.config.MarketplaceRetryConfig
import com.dmarket.p2p.tracker.credential.marketplace.HostTokenMarketplaceCredentialProvider
import com.dmarket.p2p.tracker.credential.steam.SteamCredentialProvider
import com.dmarket.p2p.tracker.engine.TrackerBlock
import com.dmarket.p2p.tracker.model.LifecycleEvent
import com.dmarket.p2p.tracker.model.RuntimeSurface
import com.dmarket.p2p.tracker.model.TrackerMode
import com.dmarket.p2p.tracker.model.marketplace.MarketplaceCredential
import com.dmarket.p2p.tracker.support.FakeClock
import com.dmarket.p2p.tracker.support.FakeCredentialVault
import com.dmarket.p2p.tracker.support.FakeDeviceIdStore
import com.dmarket.p2p.tracker.support.FakeScheduler
import com.dmarket.p2p.tracker.support.FakeSteamReadClient
import com.dmarket.p2p.tracker.support.FakeSteamSessionScraper
import com.dmarket.p2p.tracker.support.RecordingEventObserver
import com.dmarket.p2p.tracker.support.fakeMarketplaceCredential
import com.dmarket.p2p.tracker.support.fakeSteamCredential
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The mobile host's token supplier ([HostTokenMarketplaceCredentialProvider] with `treatFailureAsLoggedOut = false`)
 * through whole cycles: a throw is a temporary DMarket failure, a `null` is a logout. DMarket is the real
 * [KtorMarketplaceClient] over a [MockEngine] that refuses the token `stale` with a 401.
 */
class HostTokenFailureCycleTest {

    /** What the host's supplier does for `supply(null)` and for `supply(rejected)`. */
    private var current: () -> MarketplaceCredential? = { fakeMarketplaceCredential("fresh") }
    private var refreshed: () -> MarketplaceCredential? = { fakeMarketplaceCredential("fresh") }

    private var heartbeats = 0

    private val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private val engine = MockEngine { request ->
        if (!request.url.encodedPath.endsWith("/heartbeat")) return@MockEngine respond("{}", headers = json)
        heartbeats++
        if (request.headers[HttpHeaders.Authorization] == "stale") {
            respond("", HttpStatusCode.Unauthorized)
        } else {
            respond("""{"activeTracking":[],"directives":[],"ttlSeconds":60}""", headers = json)
        }
    }

    private val clock = FakeClock()
    private val observer = RecordingEventObserver()

    private val provider = HostTokenMarketplaceCredentialProvider(treatFailureAsLoggedOut = false) { rejected ->
        if (rejected == null) current() else refreshed()
    }

    private fun loop() = TradeTrackerLoop(
        config = LoopConfig("1.0.0", RuntimeSurface.AndroidNative, TrackerMode.Background),
        marketplace = KtorMarketplaceClient(
            createHttpClient(engine),
            "https://gateway.dmarket.com",
            CredentialMarketplaceAuthenticator(provider),
            MarketplaceRetryConfig(retryBaseDelayMs = 1, retryMaxDelayMs = 1),
        ),
        steamReader = FakeSteamReadClient(),
        credentials = SteamCredentialProvider(
            vault = FakeCredentialVault(steamCredential = fakeSteamCredential()),
            scraper = FakeSteamSessionScraper(result = fakeSteamCredential()),
            clock = clock,
        ),
        scheduler = FakeScheduler(),
        clock = clock,
        deviceId = FakeDeviceIdStore(),
        eventObserver = observer,
        marketplaceCredentials = provider,
    )

    private val reLogins get() = observer.events.filterIsInstance<LifecycleEvent.ReLoginNeeded>()

    private suspend fun TradeTrackerLoop.nextCycle() {
        clock.advance(1.minutes)
        forceHeartbeatNow()
        runOnce()
    }

    @Test
    fun a_supplier_that_throws_for_the_current_token_is_a_connection_failure_not_a_logout() = runTest {
        current = { error("no network") }
        val loop = loop()

        loop.runOnce()
        assertEquals(TrackerBlock.NONE, loop.blockingState, "one failure is debounced")
        loop.nextCycle()

        assertEquals(TrackerBlock.DM_CONNECTION_ERROR, loop.blockingState)
        assertEquals(emptyList(), reLogins)
        assertFalse(loop.needsMarketplaceReLogin)
        assertEquals(0, heartbeats, "no unauthenticated heartbeat")

        current = { fakeMarketplaceCredential("fresh") }
        loop.nextCycle()
        assertEquals(TrackerBlock.NONE, loop.blockingState)
        assertEquals(1, heartbeats)
    }

    @Test
    fun a_supplier_that_throws_on_the_refresh_after_a_401_is_a_connection_failure_not_a_logout() = runTest {
        current = { fakeMarketplaceCredential("stale") }
        refreshed = { error("refresh timed out") }
        val loop = loop()

        loop.runOnce()
        loop.nextCycle()

        assertEquals(TrackerBlock.DM_CONNECTION_ERROR, loop.blockingState)
        assertEquals(emptyList(), reLogins)
        assertFalse(loop.needsMarketplaceReLogin)
    }

    @Test
    fun a_supplier_with_no_session_still_signs_the_user_out() = runTest {
        current = { null }
        val loop = loop()

        loop.runOnce()

        assertEquals(TrackerBlock.DM_SESSION_MISSING, loop.blockingState)
        assertEquals(listOf("marketplace"), reLogins.map { it.axis })
    }

    @Test
    fun a_refresh_after_a_401_that_finds_no_session_still_signs_the_user_out() = runTest {
        current = { fakeMarketplaceCredential("stale") }
        refreshed = { null }
        val loop = loop()

        loop.runOnce()

        assertEquals(TrackerBlock.DM_SESSION_MISSING, loop.blockingState)
        assertTrue(reLogins.isNotEmpty())
    }
}
