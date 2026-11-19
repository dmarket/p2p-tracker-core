package com.dmarket.p2p.tracker.loop

import com.dmarket.p2p.tracker.adapter.host.InMemoryMarketplaceRefreshStateStore
import com.dmarket.p2p.tracker.client.createHttpClient
import com.dmarket.p2p.tracker.client.marketplace.CredentialMarketplaceAuthenticator
import com.dmarket.p2p.tracker.client.marketplace.KtorMarketplaceClient
import com.dmarket.p2p.tracker.client.marketplace.KtorMarketplaceTokenRefreshClient
import com.dmarket.p2p.tracker.config.MarketplaceRetryConfig
import com.dmarket.p2p.tracker.credential.marketplace.DefaultMarketplaceCredentialProvider
import com.dmarket.p2p.tracker.credential.marketplace.HostTokenMarketplaceCredentialProvider
import com.dmarket.p2p.tracker.credential.steam.SteamCredentialProvider
import com.dmarket.p2p.tracker.engine.TrackerBlock
import com.dmarket.p2p.tracker.model.LifecycleEvent
import com.dmarket.p2p.tracker.model.RuntimeSurface
import com.dmarket.p2p.tracker.model.TrackerMode
import com.dmarket.p2p.tracker.model.marketplace.StoredMarketplaceTokens
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceCredentialProvider
import com.dmarket.p2p.tracker.support.FakeClock
import com.dmarket.p2p.tracker.support.FakeCredentialVault
import com.dmarket.p2p.tracker.support.FakeDeviceIdStore
import com.dmarket.p2p.tracker.support.FakeMarketplaceTokenStore
import com.dmarket.p2p.tracker.support.FakeScheduler
import com.dmarket.p2p.tracker.support.FakeSteamReadClient
import com.dmarket.p2p.tracker.support.FakeSteamSessionScraper
import com.dmarket.p2p.tracker.support.RecordingEventObserver
import com.dmarket.p2p.tracker.support.fakeMarketplaceCredential
import com.dmarket.p2p.tracker.support.fakeSteamCredential
import com.dmarket.p2p.tracker.support.jwtExpiringAt
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * DMarket refuses an access token whose own `exp` is still ahead (a session revoked server-side). The real
 * [KtorMarketplaceClient] and refresh client over one [MockEngine], with the library's own provider: the refused
 * token goes out once, then not again for the hold (DMA-1007).
 */
class RefusedTokenCycleTest {

    private val clock = FakeClock()
    private val revoked = jwtExpiringAt(clock.now() + 3.days)
    private var refreshStatus = HttpStatusCode.Unauthorized

    private var heartbeats = 0
    private var refreshes = 0

    private val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private val engine = MockEngine { request ->
        val path = request.url.encodedPath
        when {
            path.endsWith("/refresh-token") -> {
                refreshes++
                respond("", refreshStatus)
            }
            path.endsWith("/heartbeat") -> {
                heartbeats++
                if (request.headers[HttpHeaders.Authorization] == revoked) {
                    respond("", HttpStatusCode.Unauthorized)
                } else {
                    respond("""{"activeTracking":[],"directives":[],"ttlSeconds":60}""", headers = json)
                }
            }
            else -> respond("{}", headers = json)
        }
    }

    private val tokens = FakeMarketplaceTokenStore(StoredMarketplaceTokens(revoked, "refresh-1", clock.now() + 30.days))
    private val state = InMemoryMarketplaceRefreshStateStore()
    private val observer = RecordingEventObserver()

    private fun provider() = DefaultMarketplaceCredentialProvider(
        store = tokens,
        refreshClient = KtorMarketplaceTokenRefreshClient({ createHttpClient(engine) }, "https://gateway.dmarket.com/refresh-token"),
        clock = clock,
        config = DefaultMarketplaceCredentialProvider.Config(
            usableSkew = 60.seconds,
            refreshHeadroom = 10.minutes,
            refreshTokenMinLife = 60.seconds,
            minRefreshInterval = 60.seconds,
        ),
        state = state,
    )

    private fun loop(provider: MarketplaceCredentialProvider = provider()) = TradeTrackerLoop(
        config = LoopConfig("1.0.0", RuntimeSurface.WebChrome, TrackerMode.Background),
        marketplace = KtorMarketplaceClient(
            createHttpClient(engine),
            "https://gateway.dmarket.com",
            CredentialMarketplaceAuthenticator(provider),
            MarketplaceRetryConfig(maxRetries = 3, retryBaseDelayMs = 1, retryMaxDelayMs = 1),
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

    private val marketplaceReLogins get() = observer.events.filterIsInstance<LifecycleEvent.ReLoginNeeded>().filter {
        it.axis ==
            "marketplace"
    }

    private suspend fun TradeTrackerLoop.nextCycle() {
        clock.advance(1.minutes)
        forceHeartbeatNow()
        runOnce()
    }

    @Test
    fun a_refused_refresh_sends_the_refused_token_once_and_signs_out() = runTest {
        val loop = loop()
        loop.runOnce()
        repeat(3) { loop.nextCycle() }

        assertEquals(1, heartbeats)
        assertEquals(1, refreshes)
        assertEquals(TrackerBlock.DM_SESSION_MISSING, loop.blockingState)
    }

    @Test
    fun a_failing_refresh_is_a_connection_error_not_a_sign_out() = runTest {
        refreshStatus = HttpStatusCode.ServiceUnavailable
        val loop = loop()
        loop.runOnce()

        assertEquals(1, heartbeats)
        assertEquals(1, refreshes)
        assertEquals(emptyList(), marketplaceReLogins)

        repeat(3) { loop.nextCycle() }
        assertEquals(1, heartbeats, "the refused token is not resent while the refresh keeps failing")
        assertEquals(4, refreshes)
        assertEquals(TrackerBlock.DM_CONNECTION_ERROR, loop.blockingState)
        assertEquals(emptyList(), marketplaceReLogins)

        loop.nextCycle()
        assertEquals(5, refreshes)
        assertEquals(TrackerBlock.DM_SESSION_MISSING, loop.blockingState, "the budget is spent")
    }

    @Test
    fun a_respawned_loop_holds_the_refused_token_until_the_store_changes_or_the_hold_ends() = runTest {
        loop().runOnce()
        assertEquals(1, heartbeats)

        // A respawn: a new loop and provider over the same token store and refresh state.
        loop().nextCycle()
        assertEquals(1, heartbeats, "the refused token stays home")

        clock.advance(15.minutes)
        loop().nextCycle()
        assertEquals(2, heartbeats, "re-offered once after the hold")
        loop().nextCycle()
        assertEquals(2, heartbeats)

        tokens.stored = StoredMarketplaceTokens(jwtExpiringAt(clock.now() + 3.days), "refresh-2", clock.now() + 30.days)
        loop().nextCycle()
        assertEquals(3, heartbeats, "a new token from a site sign-in goes out normally")
    }

    @Test
    fun a_host_answering_a_401_with_the_same_token_costs_one_heartbeat() = runTest {
        val host = HostTokenMarketplaceCredentialProvider { fakeMarketplaceCredential(revoked) }
        loop(host).runOnce()
        assertEquals(1, heartbeats)
    }
}
